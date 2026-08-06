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
 * The array-shaped user directory must be bounded (never stream the whole org) and
 * the batch id-resolver must de-duplicate and cap.
 *
 * <p>"Drops inactive users" used to be asserted here and is no longer the
 * controller's to do. It moved into {@link UserDirectoryService}, together with the
 * freeze exclusion, and it is a property of a query rather than of a stream — so it
 * is proven in {@code UserDirectoryMongoTest} against a real MongoDB. Asserting it
 * here over a stubbed repository would assert the shape of something nobody ran.
 */
class UserControllerTest {

	private UserDirectoryService directory;
	private CurrentUser currentUser;
	private UserController controller;

	@BeforeEach
	void setUp() {
		directory = mock(UserDirectoryService.class);
		currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(User.builder().id("me").build());
		controller = new UserController(mock(UserRepository.class), currentUser,
				mock(UserService.class), directory);
	}

	private User user(String id) {
		return User.builder().id(id).username(id).displayName(id.toUpperCase()).active(true).build();
	}

	@Test
	void directory_isBoundedByAHardPageCap() {
		when(directory.search(any(), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(user("a"), user("b"))));

		var result = controller.list("");

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		org.mockito.Mockito.verify(directory).search(any(), pageable.capture());
		// Never an unbounded findAll — always a capped first page.
		assertThat(pageable.getValue().getPageSize()).isEqualTo(500);
		assertThat(result).extracting(UserController.DirectoryUser::id).containsExactly("a", "b");
	}

	@Test
	void byIds_dedupesBeforeQuerying() {
		when(directory.byIds(any())).thenReturn(List.of(user("a"), user("c")));

		var result = controller.byIds(List.of("a", "a", "b", "c"));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
		org.mockito.Mockito.verify(directory).byIds(ids.capture());
		assertThat(ids.getValue()).containsExactly("a", "b", "c");
		assertThat(result).extracting(UserController.DirectoryUser::id).containsExactly("a", "c");
	}

	@Test
	void byIds_returnsEmptyForNullOrEmptyWithoutQuerying() {
		assertThat(controller.byIds(null)).isEmpty();
		assertThat(controller.byIds(List.of())).isEmpty();
		org.mockito.Mockito.verify(directory, org.mockito.Mockito.never()).byIds(any());
	}
}
