package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The tag catalogue: what the picker offers, what an entry is allowed to carry,
 * and what a rename or a delete reaches.
 *
 * <p>Three rules meet here and they are separate on purpose. {@code limitTagAccess}
 * decides who may add a <em>word</em> — with it on, only an administrator, which
 * is how an organisation keeps its reporting vocabulary from growing a synonym a
 * week. Renaming or deleting one is an administrator's act whatever that flag
 * says, because it rewrites a label on every entry that carries it, in projects
 * the actor may not even be able to see. And neither of them decides who may put
 * an existing tag on their own entry: that is their record of their own work, and
 * a policy that let one person choose another's labels would be a different
 * feature with a different legal question.
 *
 * <p>Entries keep storing tags as plain strings — the shape the published app
 * writes and reads — so the catalogue is joined to them by name, not by id. That
 * is what {@link #resolve} is for: every write path canonicalises what it was
 * handed, so "Meeting" typed on a phone and "meeting" typed on a laptop end up
 * as one word on the entries and one row in every report.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeTagService {

	/** Largest page of tags handed out — the module's one answer to that question. */
	public static final int PAGE_MAX = TimeTrackingService.PAGE_MAX;

	/**
	 * How many entries one cascade step rewrites. The point of the number is that
	 * a rename on a collection with a million entries is a sequence of bounded
	 * writes rather than one unbounded one — the size the epic's jobs and
	 * migrations already use.
	 */
	static final int CASCADE_BATCH = 500;

	/**
	 * A ceiling on one cascade, so a rename cannot hold a request open forever.
	 * Reaching it is reported rather than hidden: the tag is renamed, the entries
	 * that were reached are rewritten, and the answer says how many.
	 */
	static final int CASCADE_MAX = 200_000;

	/** The collection the cascades write to, named rather than mapped — see {@link #cascade}. */
	private static final String ENTRIES = "work_items";

	private final TimeTagRepository tags;
	private final TimeTrackingSettings policy;
	private final MongoTemplate mongo;
	private final AuditService audit;
	private final Clock clock;

	/** A tag plus how many entries carry it. */
	public record TagUsage(TimeTag tag, long entries) {
	}

	// --- reading ---------------------------------------------------------------

	/**
	 * One page of the catalogue, optionally narrowed by a prefix.
	 *
	 * <p>A prefix and not a substring: the picker types into it, and a
	 * contains-search over a catalogue that only grows is a collection scan per
	 * keystroke. Matched on the normalized name, so the search is
	 * case-insensitive without a regex flag doing the work.
	 */
	public Page<TimeTag> page(String query, int page, int size) {
		PageRequest request = PageRequest.of(
				Math.clamp(page, 0, TimeTrackingService.PAGE_INDEX_MAX),
				Math.clamp(size, 1, PAGE_MAX), TimeTagRepository.BY_NAME);
		String prefix = TimeTag.normalize(query);
		// The raw prefix: Spring Data escapes a derived StartingWith itself, and
		// quoting it here would send the escape sequence to Mongo as the pattern.
		return prefix == null ? tags.findAll(request)
				: tags.findByNormalizedStartingWith(prefix, request);
	}

	public TimeTag require(String id) {
		return tags.findById(id).orElseThrow(() -> ApiException.notFound("timeTag"));
	}

	/**
	 * How many entries carry this tag — what an admin sees before renaming or
	 * deleting one.
	 *
	 * <p>An equality, served by the {@code tags} index. Deliberately not a
	 * case-insensitive match: a regex cannot use an index, and a count that reads
	 * the whole entries collection is one an administrator would pay for once per
	 * row of a list. What it can therefore miss is stated at {@link #carries}.
	 */
	public long usage(TimeTag tag) {
		return mongo.count(Query.query(carries(tag.getName())), WorkItem.class);
	}

	// --- writing the catalogue -------------------------------------------------

	/**
	 * Adds a word to the catalogue.
	 *
	 * <p>Gated by {@code limitTagAccess}: with it on only an administrator may,
	 * and the editor's "create it" affordance is hidden rather than offered and
	 * refused. Two devices creating the same tag collide on the unique index and
	 * the loser is told so, rather than producing a duplicate the reports would
	 * split into two rows.
	 */
	public TimeTag create(String name, Integer hue, User actor) {
		assertMayCoin(actor);
		TimeTag saved = insert(name, hue, actor);
		// Recorded here and not in {@link #insert}, which the write paths also
		// reach: this is an operator-shaped act on a route somebody chose to
		// call, and the CONFIGURATION category is where an administrator looks
		// for those. Coining a word by using it on one's own entry is ordinary
		// use of the module by the person it belongs to — see
		// {@link #register} — and recording that, on by default, would be the
		// behaviour log the DATA events are switched off to avoid.
		audit.event(AuditAction.TIME_TAG_CREATED).actor(actor)
				.target(saved.getId(), saved.getName())
				.meta("tag", saved.getId())
				.meta("name", saved.getName())
				.meta("hue", String.valueOf(saved.getHue()))
				.log();
		return saved;
	}

	/**
	 * Renames a tag, or recolours it, and carries the entries with it.
	 *
	 * <p>The rename is the whole reason the catalogue exists. It runs in bounded
	 * batches, and each entry gets the new name added before the old one is
	 * pulled — so a cascade interrupted halfway leaves entries carrying both
	 * names, which repeating the rename tidies, rather than neither. Losing a
	 * label off somebody's record because a request timed out is the one outcome
	 * worth ruling out.
	 */
	public TagUsage update(String id, String name, Integer hue, User actor) {
		assertMayCurate(actor);
		TimeTag tag = require(id);
		String before = tag.getName();
		String renamed = name == null ? before : cleanName(name);
		String normalized = TimeTag.normalize(renamed);
		if (!normalized.equals(tag.getNormalized()) && tags.findByNormalized(normalized).isPresent()) {
			throw ApiException.conflict("error.time.tagExists");
		}
		tag.setName(renamed);
		tag.setNormalized(normalized);
		if (hue != null) {
			tag.setHue(hueOf(hue));
		}
		tag.setUpdatedAt(clock.instant());
		TimeTag saved = store(tag);
		long rewritten = before.equals(renamed) ? 0 : cascadeRename(before, renamed);
		audit.event(AuditAction.TIME_TAG_UPDATED).actor(actor)
				.target(saved.getId(), saved.getName())
				.meta("tag", saved.getId())
				.meta("nameBefore", before)
				.meta("nameAfter", saved.getName())
				.meta("hue", String.valueOf(saved.getHue()))
				.meta("entries", String.valueOf(rewritten))
				.log();
		return new TagUsage(saved, rewritten);
	}

	/**
	 * Removes a tag from the catalogue and from every entry that carries it.
	 *
	 * <p>The entries survive: a label going away is not a reason to lose the
	 * hours it sat on.
	 */
	public long delete(String id, User actor) {
		assertMayCurate(actor);
		TimeTag tag = require(id);
		long cleared = cascade(tag.getName(), ids -> pull(ids, tag.getName()));
		tags.deleteById(tag.getId());
		audit.event(AuditAction.TIME_TAG_DELETED).actor(actor)
				.target(tag.getId(), tag.getName())
				.meta("tag", tag.getId())
				.meta("name", tag.getName())
				.meta("entries", String.valueOf(cleared))
				.log();
		return cleared;
	}

	// --- what an entry may carry -------------------------------------------------

	/**
	 * The tags an entry will actually be stored with.
	 *
	 * <p>Every write path — the app, an MCP tool, a smart commit, a timer — comes
	 * through here, so the catalogue is never bypassed and an entry never carries
	 * a word the reports cannot group by.
	 *
	 * <p>A word already in the catalogue becomes the catalogue's spelling. A word
	 * that is not: with {@code limitTagAccess} on it is refused, naming the tag,
	 * so the person is told what happened instead of watching a label vanish on
	 * save; with it off it is added as they typed it — the "create it on the fly"
	 * the editor offers, and what makes a later rename able to find it.
	 */
	public List<String> resolve(List<String> raw, User actor) {
		List<String> cleaned = TimeTrackingService.normalizeTags(raw);
		if (cleaned.isEmpty()) {
			return cleaned;
		}
		LinkedHashSet<String> keys = new LinkedHashSet<>();
		for (String tag : cleaned) {
			String key = TimeTag.normalize(tag);
			if (key != null) {
				keys.add(key);
			}
		}
		Map<String, String> known = new HashMap<>();
		for (TimeTag tag : tags.findByNormalizedIn(keys)) {
			known.put(tag.getNormalized(), tag.getName());
		}
		boolean curated = policy.limitTagAccess();
		// A set, because two spellings of one word collapse into one tag and an
		// entry must not come out carrying it twice.
		LinkedHashSet<String> resolved = new LinkedHashSet<>();
		for (String tag : cleaned) {
			String key = TimeTag.normalize(tag);
			String canonical = known.get(key);
			if (canonical == null) {
				if (curated) {
					throw ApiException.forbidden("error.time.tagNotAllowed", tag);
				}
				canonical = register(tag, actor);
				known.put(key, canonical);
			}
			resolved.add(canonical);
		}
		return new ArrayList<>(resolved);
	}

	/**
	 * Adds a word somebody just used to the catalogue, without the curation gate.
	 *
	 * <p>Reached only when {@code limitTagAccess} is off, which is exactly the
	 * state in which any member may coin a tag. Two people typing the same new
	 * word at once end on the unique index, and the loser reads the row the
	 * winner wrote rather than failing a save over a label.
	 */
	private String register(String name, User actor) {
		try {
			return insert(name, null, actor).getName();
		}
		catch (ApiException failed) {
			if (failed.getStatus() != HttpStatus.CONFLICT) {
				// A name this method cannot fix — too long, or blank. Only the
				// collision is worth retrying as a read.
				throw failed;
			}
			return tags.findByNormalized(TimeTag.normalize(name))
					.map(TimeTag::getName)
					.orElseThrow(() -> failed);
		}
	}

	// --- internals ----------------------------------------------------------------

	private TimeTag insert(String name, Integer hue, User actor) {
		String clean = cleanName(name);
		String normalized = TimeTag.normalize(clean);
		TimeTag saved = store(TimeTag.builder()
				.name(clean)
				.normalized(normalized)
				// Derived from the word, so a tag keeps its colour across
				// instances and two tags coined in the same minute do not come out
				// the same.
				.hue(hue != null ? hueOf(hue) : Project.labelHueAt(normalized.hashCode()))
				.createdBy(actor == null ? null : actor.getId())
				.build());
		return saved;
	}

	/**
	 * {@code insert} for a document with no id yet: the unique index is what makes
	 * two devices coining the same tag safe, and {@code save} would upsert past it.
	 */
	private TimeTag store(TimeTag tag) {
		try {
			return tag.getId() == null ? tags.insert(tag) : tags.save(tag);
		}
		catch (DuplicateKeyException taken) {
			throw ApiException.conflict("error.time.tagExists");
		}
	}

	/**
	 * Who may add a word: anyone, unless the operator reserved it.
	 *
	 * <p>This is the rule {@code limitTagAccess} was written for — an
	 * organisation keeping its reporting vocabulary from growing a synonym a
	 * week. Coining a word is cheap and undoable.
	 */
	private void assertMayCoin(User actor) {
		if (policy.limitTagAccess() && (actor == null || !actor.isAdmin())) {
			throw ApiException.forbidden("error.time.tagsRestricted");
		}
	}

	/**
	 * Who may rename or delete one: an administrator, always.
	 *
	 * <p>A different question from {@link #assertMayCoin}, and the difference is
	 * the whole reason there are two methods. Renaming a tag rewrites a label on
	 * every entry that carries it — other people's records of their own working
	 * time, in projects the actor may not be able to see, possibly thousands of
	 * them. Reading {@code limitTagAccess} here would have meant that on a
	 * default instance, where the flag is off, any member could rewrite or strip
	 * a label across the whole organisation.
	 */
	private void assertMayCurate(User actor) {
		if (actor == null || !actor.isAdmin()) {
			throw ApiException.forbidden("error.time.tagsRestricted");
		}
	}

	private static String cleanName(String name) {
		String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty() || trimmed.length() > TimeTag.MAX_NAME) {
			throw ApiException.badRequest("error.time.tagNameInvalid");
		}
		return trimmed;
	}

	private static int hueOf(int hue) {
		return Math.floorMod(hue, 360);
	}

	/**
	 * Entries carrying exactly this word.
	 *
	 * <p>An equality, because {@code work_items} has a multikey index on
	 * {@code tags} and a case-insensitive regex could not use it: matching
	 * loosely would turn every rename, every delete and every usage count into a
	 * read of the whole entries collection, and the count is one an admin list
	 * pays per row.
	 *
	 * <p>What it does not reach: an entry written before this catalogue existed
	 * that carries the word in another capitalisation. Nothing writes one any
	 * more — {@link #resolve} canonicalises every write path — and the next save
	 * of such an entry folds it onto the catalogue's spelling. A rename before
	 * that leaves it behind, which is a stated limit rather than an oversight;
	 * the alternative is a collection scan on every curation.
	 */
	private static Criteria carries(String name) {
		return Criteria.where("tags").is(name);
	}

	/**
	 * Rewrites {@code before} to {@code after} on every entry that carries it.
	 *
	 * <p>Add first, then remove — an interruption between the two leaves the
	 * entry with both names, which repeating the rename tidies, rather than with
	 * neither. {@code $addToSet} is what makes a rename that only changes
	 * capitalisation ("meeting" → "Meeting") land: the new name is added, the old
	 * exact string is pulled, and an entry that somehow carried both ends with
	 * one.
	 */
	private long cascadeRename(String before, String after) {
		return cascade(before, ids -> {
			mongo.updateMulti(byId(ids), new Update().addToSet("tags", after), ENTRIES);
			pull(ids, before);
		});
	}

	private void pull(List<Object> ids, String name) {
		mongo.updateMulti(byId(ids), new Update().pull("tags", name), ENTRIES);
	}

	private static Query byId(List<Object> ids) {
		return Query.query(Criteria.where("_id").in(ids));
	}

	/**
	 * Walks the entries carrying {@code name} in bounded batches and applies one
	 * step per batch.
	 *
	 * <p>Frozen days are left out. The lock date binds every other write path in
	 * the module, administrators included, and a rename is a write to every entry
	 * it touches: without this, renaming a tag would rewrite a closed payroll
	 * period, and the only record of it would be one row saying how many entries
	 * moved.
	 *
	 * <p>Paged on {@code _id} rather than by re-asking which entries still match:
	 * a rename adds the new name before it pulls the old one, so for one batch
	 * the entry matches both, and a loop that trusted the match to shrink would
	 * be trusting an ordering it does not control.
	 */
	private long cascade(String name, Consumer<List<Object>> step) {
		Criteria matches = carries(name);
		LocalDate lock = policy.lockBefore();
		if (lock != null) {
			matches = new Criteria().andOperator(matches, Criteria.where("date").gte(lock));
		}
		Object cursor = null;
		long touched = 0;
		while (touched < CASCADE_MAX) {
			Query batch = Query.query(cursor == null ? matches
					: new Criteria().andOperator(matches, Criteria.where("_id").gt(cursor)));
			batch.fields().include("_id");
			batch.with(Sort.by("_id")).limit(CASCADE_BATCH);
			// Raw documents, and the ids left exactly as MongoDB hands them over.
			// A projection that names one field cannot be mapped back onto the
			// entity — {@code durationMinutes} is a primitive with no null to
			// carry — and a hex string handed back as an id would be converted
			// again on the way in, which is a conversion this loop has no reason
			// to depend on.
			List<Document> found = mongo.find(batch, Document.class, ENTRIES);
			if (found.isEmpty()) {
				break;
			}
			List<Object> ids = found.stream().map(row -> row.get("_id")).toList();
			cursor = ids.get(ids.size() - 1);
			step.accept(ids);
			touched += ids.size();
		}
		if (touched >= CASCADE_MAX) {
			log.warn("[time] tag cascade for '{}' stopped at the {}-entry ceiling", name,
					CASCADE_MAX);
		}
		return touched;
	}
}
