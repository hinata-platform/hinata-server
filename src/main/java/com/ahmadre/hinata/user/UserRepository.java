package com.ahmadre.hinata.user;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {

	Optional<User> findByEmailIgnoreCase(String email);

	Optional<User> findByUsernameIgnoreCase(String username);

	boolean existsByEmailIgnoreCase(String email);

	boolean existsByUsernameIgnoreCase(String username);

	long countByRolesContaining(Role role);

	/** Active admins other than {@code id} – used to prevent locking out the last admin. */
	long countByRolesContainingAndActiveIsTrueAndIdNot(Role role, String id);
}
