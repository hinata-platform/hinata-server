package com.ahmadre.hinata.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ahmadre.hinata.auth.CurrentUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * The array-shaped user directory must be bounded (never stream the whole
 * org) and the batch id-resolver must de-duplicate, cap and drop inactive users.
 */
class UserControllerTest {

	private UserRepository users;
	private CurrentUser currentUser;
	private UserController controller;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(User.builder().id("me").build());
		controller = new UserController(users, currentUser);
	}

	private User user(String id, boolean active) {
		return User.builder().id(id).username(id).displayName(id.toUpperCase()).active(active).build();
	}

	@Test
	void directory_isBoundedByAHardPageCap() {
		when(users.searchActive(any(), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(user("a", true), user("b", true))));

		var result = controller.directory("");

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		org.mockito.Mockito.verify(users).searchActive(any(), pageable.capture());
		// Never an unbounded findAll — always a capped first page.
		assertThat(pageable.getValue().getPageSize()).isEqualTo(500);
		assertThat(result).extracting(UserController.DirectoryUser::id).containsExactly("a", "b");
	}

	@Test
	void byIds_dedupesAndDropsInactive() {
		when(users.findAllById(any()))
				.thenReturn(List.of(user("a", true), user("b", false), user("c", true)));

		var result = controller.byIds(List.of("a", "a", "b", "c"));

		// De-duped before hitting the repo…
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Iterable<String>> ids = ArgumentCaptor.forClass(Iterable.class);
		org.mockito.Mockito.verify(users).findAllById(ids.capture());
		assertThat(ids.getValue()).containsExactly("a", "b", "c");
		// …and inactive users are excluded from the response.
		assertThat(result).extracting(UserController.DirectoryUser::id).containsExactly("a", "c");
	}

	@Test
	void byIds_returnsEmptyForNullOrEmptyWithoutQuerying() {
		assertThat(controller.byIds(null)).isEmpty();
		assertThat(controller.byIds(List.of())).isEmpty();
		org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).findAllById(any());
	}
}
