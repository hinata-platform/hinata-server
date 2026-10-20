package com.ahmadre.hinata.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {

	Optional<User> findByEmailIgnoreCase(String email);

	Optional<User> findByUsernameIgnoreCase(String username);

	boolean existsByEmailIgnoreCase(String email);

	boolean existsByUsernameIgnoreCase(String username);

	long countByRolesContaining(Role role);

	/** Active admins other than {@code id} – used to prevent locking out the last admin. */
	long countByRolesContainingAndActiveIsTrueAndIdNot(Role role, String id);

	/** Every active user holding {@code role} – used to notify admins of pending approvals. */
	List<User> findByRolesContainingAndActiveIsTrue(Role role);

	List<User> findByRolesContaining(Role role);

	long countByRolesContainingAndActiveIsTrue(Role role);

	/** All active users – used to fan out the periodic digest. */
	List<User> findByActiveIsTrue();

	/**
	 * A page of the active directory, by name.
	 *
	 * <p>For a list that shows everybody rather than a search result. {@link #searchActive} answers
	 * the same thing for an empty term, but only by fetching every active document and running
	 * three unanchored regexes over it — so a screen whose normal state is "no filter" asks this
	 * instead, and the {@code active_displayName} index serves it pre-sorted.
	 */
	Page<User> findByActiveIsTrue(Pageable pageable);

	/** Self-registrations that have verified their email but await an admin's approval. */
	long countByAwaitingApprovalIsTrue();

	/**
	 * Paginated directory type-ahead: active users whose name, username or title
	 * matches [regex] (case-insensitive). The caller passes a regex-escaped term;
	 * an escaped empty string matches everyone (first page of the directory).
	 */
	@Query("{ 'active': true, $or: [ "
			+ "{ 'displayName': { $regex: ?0, $options: 'i' } }, "
			+ "{ 'username': { $regex: ?0, $options: 'i' } }, "
			+ "{ 'title': { $regex: ?0, $options: 'i' } } ] }")
	Page<User> searchActive(String regex, Pageable pageable);

	/**
	 * Just the pronouns of the given accounts, for a screen that renders a list
	 * of names it already has. Projected rather than loaded whole: the callers
	 * want one field, and a full {@link User} carries the TOTP secret and the
	 * recovery-code hashes — no reason to page those into memory to print
	 * "she/her" beside a row.
	 *
	 * <p>An interface projection, not a field-limited {@link User}: a partial
	 * document cannot build a User at all, because its primitive fields
	 * ({@code active} and friends) have no null to be absent as.
	 */
	@Query("{ '_id': { $in: ?0 } }")
	List<PronounsView> findPronounsByIdIn(Collection<String> ids);

	/**
	 * What a notice worded per reader needs of each reader ({@code NotificationService.notifyEach}):
	 * the address, the language, the zone, whether the account is on and the delivery preferences,
	 * plus the names. A fan-out to thousands of people then reads a few hundred bytes each instead
	 * of whole accounts with their security fields.
	 */
	@Query("{ '_id': { $in: ?0 } }")
	List<NoticeReaderView> findNoticeReadersByIdIn(Collection<String> ids);

	/** The fields of {@link #findNoticeReadersByIdIn}. */
	interface NoticeReaderView {

		String getId();

		String getUsername();

		String getDisplayName();

		String getEmail();

		String getLocale();

		String getTimezone();

		Boolean getActive();

		com.ahmadre.hinata.me.NotificationPreferences getNotificationPreferences();

		/**
		 * The reader as the delivery code takes it, with exactly the values a whole account read
		 * would have for these fields (a missing one stays missing).
		 */
		default User asUser() {
			return User.builder().id(getId()).username(getUsername()).displayName(getDisplayName())
					.email(getEmail()).locale(getLocale()).timezone(getTimezone())
					.active(Boolean.TRUE.equals(getActive()))
					.notificationPreferences(getNotificationPreferences()).build();
		}
	}

	/** Id + pronouns, and nothing else off the wire. */
	interface PronounsView {

		String getId();

		String getPronouns();
	}
}
