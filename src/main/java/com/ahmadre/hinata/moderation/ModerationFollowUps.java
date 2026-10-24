package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.ContentSurface;
import com.ahmadre.hinata.content.CopiesScrub;
import com.ahmadre.hinata.content.CopySource;
import com.ahmadre.hinata.content.MediaRef;
import com.ahmadre.hinata.content.Placeholders;
import com.ahmadre.hinata.content.Quarantine;
import com.ahmadre.hinata.content.Removal;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.content.ScrubReason;
import com.ahmadre.hinata.moderation.quarantine.QuarantinedContent;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The work that follows a moderation step once it is saved (HIN-169, HIN-171): the removal itself,
 * scrubbing the copies of what it took, the statement to the person responsible, the outcome to
 * the reporters, and telling the person when their notice changed.
 *
 * <p>Called right after the step's own write, never as an after-commit listener: the module runs
 * without transactions too, and {@code @TransactionalEventListener} outside a transaction silently
 * never fires. On a single thread of its own, like the shift follow-ups, so the moderator's request
 * does not wait for a scan of the bell. Every step is marked on the case once done and can run
 * twice without harm; the follow-up job (HIN-170) drives what a restart dropped.
 */
@Slf4j
@Component
public class ModerationFollowUps {

	public static final String EXECUTOR = "moderationFollowUpExecutor";

	private final CopiesScrub copies;
	private final MessageSource messages;
	private final SettingsService settings;
	private final Executor executor;
	private final MongoTemplate mongo;
	private final List<ContentSurface> surfaces;
	private final Quarantine quarantine;
	private final DecisionNotices notices;
	private final Clock clock;

	public ModerationFollowUps(CopiesScrub copies, MessageSource messages, SettingsService settings,
			@Qualifier(EXECUTOR) Executor executor, MongoTemplate mongo, List<ContentSurface> surfaces,
			Quarantine quarantine, DecisionNotices notices, Clock clock) {
		this.copies = copies;
		this.messages = messages;
		this.settings = settings;
		this.executor = executor;
		this.mongo = mongo;
		this.surfaces = surfaces;
		this.quarantine = quarantine;
		this.notices = notices;
		this.clock = clock;
	}

	/** The placeholders in the language of the instance, read once per step. */
	public Placeholders placeholders() {
		return Placeholders.in(messages, settings.instanceLocale());
	}

	/** Scrubs the copies of what [removals] took, off the request. */
	public CompletableFuture<Void> scrubCopies(List<Removal> removals) {
		List<CopySource> sources = removals.stream()
				.map(Removal::copies)
				.filter(Objects::nonNull)
				.toList();
		if (sources.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		Placeholders placeholders = placeholders();
		return CompletableFuture.runAsync(() -> copies.scrub(sources, ScrubReason.REMOVED, placeholders), executor);
	}

	/** Runs what case [caseId] still owes, off the request. */
	public void later(String caseId) {
		executor.execute(() -> {
			try {
				complete(caseId);
			}
			catch (RuntimeException ex) {
				// The case stays due; the follow-up job tries again.
				log.warn("[moderation] follow-up of case {} failed", caseId, ex);
			}
		});
	}

	/**
	 * Takes the decided removal out of the content and records what it took. Never throws: the
	 * decision stands whatever the store says, and an unfinished removal is tried again.
	 */
	public ModerationCase removeFor(ModerationCase c) {
		Decision decision = c.getDecision();
		Removal removal;
		try {
			removal = surface(c).remove(CaseService.targetOf(c.getTarget()), decision.removal(), c.getId(),
					decision.category(), placeholders());
		}
		catch (ApiException gone) {
			if (gone.getStatus() != HttpStatus.NOT_FOUND) {
				log.warn("[moderation] removal for case {} failed: {}", c.getId(), gone.getMessageKey());
				return c;
			}
			// Deleted meanwhile: there is nothing left to take.
			removal = Removal.nothing(c.getTarget(), decision.removal());
		}
		catch (RuntimeException failed) {
			log.warn("[moderation] removal for case {} failed", c.getId(), failed);
			return c;
		}
		Update update = new Update()
				.set("pendingDeletes", removal.pendingDeletes())
				.set("removalState", removal.complete() ? RemovalState.DONE : RemovalState.INCOMPLETE);
		if (removal.quarantineId() != null) {
			update.set("decision.quarantineId", removal.quarantineId());
		}
		if (removal.copies() != null) {
			update.set("pendingCopies", List.of(removal.copies()));
		}
		if (removal.otherUses() != null && !removal.otherUses().isEmpty()) {
			update.set("otherUses", otherUsesOf(removal.otherUses()));
		}
		return mongo.findAndModify(byId(c.getId()), update, FindAndModifyOptions.options().returnNew(true),
				ModerationCase.class);
	}

	/** [refs] with where each is found, at most {@link OtherUse#MAX}; one read per place. */
	private List<OtherUse> otherUsesOf(List<ContentRef> refs) {
		return refs.stream().distinct().limit(OtherUse.MAX)
				.map(ref -> new OtherUse(ref.kind(), ref.id(), ref.parentId(), locationOf(ref)))
				.toList();
	}

	/**
	 * The key of the issue [ref] stands in, and nothing for a page or a person: a title would show
	 * moderators words of content nobody reported. Null as well when it cannot be read, which must
	 * not undo the removal.
	 */
	private String locationOf(ContentRef ref) {
		if (ref.kind() != ContentKind.ISSUE && ref.kind() != ContentKind.COMMENT
				&& ref.kind() != ContentKind.ATTACHMENT) {
			return null;
		}
		try {
			return surfaceOf(ref.kind()).forModeration(CaseService.targetOf(ref))
					.map(ContentSnapshot::location).orElse(null);
		}
		catch (RuntimeException unresolved) {
			log.warn("[moderation] could not name another use {}:{}", ref.kind(), ref.id());
			return null;
		}
	}

	/**
	 * Puts back what the removal of [c] took, read from the quarantine the case holds. Throws 502
	 * when the store would not give the files back, so whoever asked can try again.
	 */
	public RestoreOutcome restoreFor(ModerationCase c) {
		QuarantinedContent held = mongo.findOne(Query.query(Criteria.where("caseId").is(c.getId())),
				QuarantinedContent.class);
		if (held == null) {
			// The removal never took anything: the content is as it was.
			return RestoreOutcome.RESTORED;
		}
		List<MediaRef> objects = held.getObjects() == null ? List.of() : held.getObjects().stream()
				.map(object -> new MediaRef(object.originalKey(), object.fileName(), object.contentType(), object.size()))
				.toList();
		Removal removal = new Removal(held.getRef(), held.getScope(), held.getFields(), objects, held.getId(), List.of(),
				List.of(), null);
		return surface(c).restore(removal);
	}

	/**
	 * Every step [caseId] still owes, each marked once done: the removal, its originals, the copies,
	 * the statement, the reporters, a change of the notice. Unmarks the case as due when nothing is
	 * left.
	 */
	public void complete(String caseId) {
		ModerationCase c = mongo.findById(caseId, ModerationCase.class);
		if (c == null || c.getStatus() != CaseStatus.DECIDED || c.getDecision() == null) {
			return;
		}
		c = finishRemoval(c);
		Instant now = clock.instant();
		scrubCopiesOf(c, now);
		sendStatement(c, now);
		tellReporters(c, now);
		tellChange(c);
		ModerationCase after = mongo.findById(caseId, ModerationCase.class);
		if (after != null && settled(after)) {
			mongo.updateFirst(byId(caseId), new Update().unset("followUpDue"), ModerationCase.class);
		}
	}

	private ModerationCase finishRemoval(ModerationCase c) {
		if (c.getRemovalState() != RemovalState.INCOMPLETE || c.getRestoration() != null) {
			return c;
		}
		return c.getPendingDeletes() == null || c.getPendingDeletes().isEmpty() ? removeFor(c) : deleteOriginals(c);
	}

	/**
	 * Whether what the decision says is so: no removal was decided, or it rewrote the content
	 * (originals may still wait for deletion), or it was undone. Until then the copies still show
	 * what is there, and a statement would tell of a removal that has not happened.
	 */
	static boolean taken(ModerationCase c) {
		return c.getRemovalState() != RemovalState.INCOMPLETE || c.getRestoration() != null
				|| c.getPendingDeletes() != null && !c.getPendingDeletes().isEmpty();
	}

	private void scrubCopiesOf(ModerationCase c, Instant now) {
		if (c.getCopiesScrubbedAt() != null || !taken(c)) {
			return;
		}
		if (c.getPendingCopies() != null && !c.getPendingCopies().isEmpty()) {
			copies.scrub(c.getPendingCopies(), ScrubReason.REMOVED, placeholders());
		}
		mark(c, new Update().set("copiesScrubbedAt", now).unset("pendingCopies"));
	}

	/** Marked once the statement is out, or when none is owed. */
	private void sendStatement(ModerationCase c, Instant now) {
		if (c.getStatementSentAt() != null || !taken(c)) {
			return;
		}
		if (DecisionNotices.owesStatement(c.getDecision()) && c.getRestoration() == null) {
			notices.statement(c);
		}
		mark(c, new Update().set("statementSentAt", now));
	}

	private void tellReporters(ModerationCase c, Instant now) {
		if (c.getReportersNotifiedAt() == null && taken(c) && notices.reporters(c)) {
			mark(c, new Update().set("reportersNotifiedAt", now));
		}
	}

	private void tellChange(ModerationCase c) {
		if (c.getNoticeChangedAt() != null && (c.getNoticeChangeSentAt() == null
				|| c.getNoticeChangeSentAt().isBefore(c.getNoticeChangedAt()))) {
			notices.changed(c);
			mark(c, new Update().set("noticeChangeSentAt", c.getNoticeChangedAt()));
		}
	}

	/** Whether [c] owes nothing more. */
	static boolean settled(ModerationCase c) {
		boolean removed = c.getRemovalState() != RemovalState.INCOMPLETE || c.getRestoration() != null;
		boolean changeTold = c.getNoticeChangedAt() == null || c.getNoticeChangeSentAt() != null
				&& !c.getNoticeChangeSentAt().isBefore(c.getNoticeChangedAt());
		return removed && (c.getPendingDeletes() == null || c.getPendingDeletes().isEmpty())
				&& c.getCopiesScrubbedAt() != null && c.getStatementSentAt() != null
				&& c.getReportersNotifiedAt() != null && changeTold;
	}

	/** Originals a removal could not confirm gone, deleted again. */
	private ModerationCase deleteOriginals(ModerationCase c) {
		List<String> left = quarantine.deleteOriginals(c.getPendingDeletes());
		Update update = new Update().set("pendingDeletes", left);
		if (left.isEmpty()) {
			update.set("removalState", RemovalState.DONE);
		}
		else {
			log.warn("[moderation] case {} still has {} original(s) the store would not delete", c.getId(), left.size());
		}
		return mongo.findAndModify(byId(c.getId()), update, FindAndModifyOptions.options().returnNew(true),
				ModerationCase.class);
	}

	private void mark(ModerationCase c, Update update) {
		mongo.updateFirst(byId(c.getId()), update, ModerationCase.class);
	}

	private ContentSurface surface(ModerationCase c) {
		return surfaceOf(c.getTarget().kind());
	}

	private ContentSurface surfaceOf(ContentKind kind) {
		return surfaces.stream().filter(surface -> surface.kind() == kind).findFirst()
				.orElseThrow(() -> ApiException.badRequest("error.moderation.scopeNotAllowed"));
	}

	private static Query byId(String id) {
		return Query.query(Criteria.where("_id").is(id));
	}

	/**
	 * One thread, a queue of a thousand, and the caller's own thread once that is full: nothing is
	 * dropped. Not a default candidate, so nothing else lands on it.
	 */
	@Configuration
	static class Threads {

		@Bean(name = EXECUTOR, defaultCandidate = false)
		ThreadPoolTaskExecutor moderationFollowUpExecutor() {
			ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
			executor.setCorePoolSize(1);
			executor.setMaxPoolSize(1);
			executor.setQueueCapacity(1000);
			executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
			executor.setWaitForTasksToCompleteOnShutdown(true);
			executor.setAwaitTerminationSeconds(30);
			executor.setThreadNamePrefix("moderation-follow-up-");
			return executor;
		}
	}
}
