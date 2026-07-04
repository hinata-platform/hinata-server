package com.ahmadre.hinata.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

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
}
