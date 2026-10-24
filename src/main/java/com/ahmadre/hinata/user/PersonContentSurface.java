package com.ahmadre.hinata.user;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentMark;
import com.ahmadre.hinata.content.ContentOrigin;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.ContentSurface;
import com.ahmadre.hinata.content.MediaRef;
import com.ahmadre.hinata.content.Placeholders;
import com.ahmadre.hinata.content.Quarantine;
import com.ahmadre.hinata.content.Removal;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.me.AvatarService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * People as moderation reaches them (HIN-169): the picture, the words on the profile, or both.
 *
 * <p>The words become neutral rather than empty: the name a placeholder like {@code Person 4f2a},
 * the position and pronouns blank, the user name {@code user-} and six hex digits. Signing in by
 * e-mail address keeps working; the notice about the decision names the new user name.
 */
@Component
@RequiredArgsConstructor
class PersonContentSurface implements ContentSurface {

	static final String AVATAR = "avatar";
	static final String PROFILE = "profile";

	private final UserRepository users;
	private final Quarantine quarantine;
	private final MongoTemplate mongo;

	/** Whether moderation removed [field] of [person] and nobody wrote it anew. */
	static boolean isRemoved(User person, String field) {
		ContentMark mark = person.getModeration() == null ? null : person.getModeration().get(field);
		return mark != null && mark.isRemoved();
	}

	/** Turns the mark of [field] into {@code REWRITTEN} on [update] when moderation removed it. */
	static void markRewritten(User person, String field, Update update) {
		if (isRemoved(person, field)) {
			update.set("moderation." + field, person.getModeration().get(field).rewritten());
		}
	}

	@Override
	public ContentKind kind() {
		return ContentKind.PERSON;
	}

	@Override
	public Set<RemovalScope> scopes() {
		return Set.of(RemovalScope.AVATAR, RemovalScope.PROFILE_TEXT, RemovalScope.WHOLE);
	}

	/** Every member sees every profile in the organisation. */
	@Override
	public Optional<ContentSnapshot> snapshot(TargetRef target, User viewer) {
		return viewer == null ? Optional.empty() : find(target).map(PersonContentSurface::snapshotOf);
	}

	@Override
	public Optional<ContentSnapshot> forModeration(TargetRef target) {
		return find(target).map(PersonContentSurface::snapshotOf);
	}

	private Optional<User> find(TargetRef target) {
		return target == null || target.id() == null ? Optional.empty() : users.findById(target.id());
	}

	private static ContentRef refOf(User person) {
		return new ContentRef(ContentKind.PERSON, person.getId(), null, null);
	}

	private static ContentSnapshot snapshotOf(User person) {
		String words = String.join(" · ", List.of(blankIfNull(person.getTitle()), blankIfNull(person.getPronouns())))
				.replaceAll("^ · | · $", "");
		return new ContentSnapshot(
				refOf(person),
				ContentOrigin.MEMBER,
				null,
				Set.of(person.getId()),
				person.getId(),
				person.getDisplayName(),
				ContentSnapshot.clip(words, ContentSnapshot.EXCERPT_MAX),
				ContentSnapshot.clip(person.getUsername(), ContentSnapshot.CONTEXT_MAX),
				"@" + person.getUsername(),
				person.getAvatarUrl() == null ? List.of() : List.of(MediaRef.of(AvatarService.objectKey(person.getId()))),
				person.getCreatedAt(),
				person.getUpdatedAt(),
				ContentSnapshot.hashOf(person.getDisplayName(), person.getTitle(), person.getPronouns(),
						person.getUsername(), person.getAvatarUrl()));
	}

	private static String blankIfNull(String value) {
		return value == null ? "" : value.strip();
	}

	@Override
	public Removal remove(TargetRef target, RemovalScope scope, String caseId, ReportCategory category,
			Placeholders placeholders) {
		requireScope(scope);
		User person = find(target).orElseThrow(() -> ApiException.notFound("user"));
		ContentRef ref = refOf(person);
		boolean avatar = scope != RemovalScope.PROFILE_TEXT && person.getAvatarUrl() != null && !isRemoved(person, AVATAR);
		boolean profile = scope != RemovalScope.AVATAR && !isRemoved(person, PROFILE);
		if (!avatar && !profile) {
			return Removal.nothing(ref, scope);
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		if (avatar) {
			fields.put("avatarUrl", person.getAvatarUrl());
		}
		String username = profile ? neutralUsername(person.getId()) : null;
		if (profile) {
			fields.put("displayName", person.getDisplayName());
			fields.put("username", person.getUsername());
			putPresent(fields, "title", person.getTitle());
			putPresent(fields, "pronouns", person.getPronouns());
			// What the notice about the decision tells the person to sign in with from now on.
			fields.put("neutralUsername", username);
		}
		List<MediaRef> move = avatar ? List.of(MediaRef.of(AvatarService.objectKey(person.getId()))) : List.of();
		Quarantine.Held held = quarantine.hold(caseId, ref, scope, fields, move, List.of());
		try {
			Update update = new Update();
			ContentMark mark = ContentMark.removed(caseId);
			if (avatar) {
				update.set("avatarUrl", null).set("moderation." + AVATAR, mark);
			}
			if (profile) {
				update.set("displayName", placeholders.personName(code(person.getId(), 4)))
						.set("username", username)
						.unset("title")
						.unset("pronouns")
						.set("moderation." + PROFILE, mark);
			}
			mongo.updateFirst(Query.query(Criteria.where("_id").is(person.getId())), update, User.class);
		}
		catch (RuntimeException ex) {
			quarantine.discard(held);
			throw ex;
		}
		List<String> pending = quarantine.deleteOriginals(held);
		return new Removal(ref, scope, fields, held.media(), held.id(), pending, List.of(), null);
	}

	/**
	 * A user name that says nothing about the person and is free: {@code user-} and six hex digits
	 * of their id's hash, more where six are taken.
	 */
	private String neutralUsername(String userId) {
		for (int length = 6; length <= 16; length += 2) {
			String candidate = "user-" + code(userId, length);
			if (!users.existsByUsernameIgnoreCase(candidate)) {
				return candidate;
			}
		}
		return "user-" + code(userId, 24);
	}

	private static String code(String userId, int length) {
		return ContentSnapshot.hashOf(userId).substring(0, length);
	}

	@Override
	public RestoreOutcome restore(Removal removal) {
		User person = users.findById(removal.ref().id()).orElse(null);
		if (person == null) {
			return RestoreOutcome.GONE;
		}
		Map<String, Object> fields = removal.fields();
		boolean avatar = fields.containsKey("avatarUrl");
		boolean profile = fields.containsKey("displayName");
		if ((avatar && !isRemoved(person, AVATAR)) || (profile && !isRemoved(person, PROFILE))) {
			return RestoreOutcome.REWRITTEN_SINCE;
		}
		if (removal.quarantineId() != null && !quarantine.restoreObjects(removal.quarantineId())) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
		}
		Update update = new Update();
		if (avatar) {
			update.set("avatarUrl", fields.get("avatarUrl")).unset("moderation." + AVATAR);
		}
		if (profile) {
			String username = (String) fields.get("username");
			// Somebody may have taken the old name meanwhile; then the neutral one stays.
			boolean free = users.findByUsernameIgnoreCase(username)
					.map(other -> other.getId().equals(person.getId()))
					.orElse(true);
			update.set("displayName", fields.get("displayName")).unset("moderation." + PROFILE);
			if (free) {
				update.set("username", username);
			}
			setOrUnset(update, "title", fields.get("title"));
			setOrUnset(update, "pronouns", fields.get("pronouns"));
		}
		mongo.updateFirst(Query.query(Criteria.where("_id").is(person.getId())), update, User.class);
		return RestoreOutcome.RESTORED;
	}

	private static void putPresent(Map<String, Object> fields, String name, Object value) {
		if (value != null) {
			fields.put(name, value);
		}
	}

	private static void setOrUnset(Update update, String name, Object value) {
		if (value == null) {
			update.unset(name);
		}
		else {
			update.set(name, value);
		}
	}
}
