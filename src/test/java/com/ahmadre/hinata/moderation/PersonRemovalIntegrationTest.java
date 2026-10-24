package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.MarkState;
import com.ahmadre.hinata.content.Removal;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.me.AvatarController;
import com.ahmadre.hinata.me.AvatarService;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Removing a person's picture and the words on their profile (HIN-169), and the public picture
 * route that must not keep serving either.
 */
class PersonRemovalIntegrationTest extends RemovalIntegrationSupport {

	@Autowired
	private AvatarController avatars;

	private User withProfile() {
		author.setAvatarUrl("/api/v1/users/" + author.getId() + "/avatar?v=1");
		author.setTitle("Hassprediger");
		author.setPronouns("er/ihm");
		author.setDisplayName("Jonas der Schreckliche");
		store.put(AvatarService.objectKey(author.getId()), "jpeg");
		return users.save(author);
	}

	@Test
	void aRemovedProfileShowsNeutralWordsAndNoPicture() {
		User person = withProfile();

		Removal removal = remove(ContentKind.PERSON, person.getId(), null, RemovalScope.WHOLE, ReportCategory.HARASSMENT);

		User removed = users.findById(person.getId()).orElseThrow();
		assertThat(removed.getDisplayName()).matches("Person [0-9a-f]{4}");
		assertThat(removed.getUsername()).matches("user-[0-9a-f]{6}");
		assertThat(removed.getTitle()).isNull();
		assertThat(removed.getPronouns()).isNull();
		assertThat(removed.getAvatarUrl()).isNull();
		assertThat(removed.getModeration().get("profile").state()).isEqualTo(MarkState.REMOVED);
		assertThat(removal.fields().get("neutralUsername")).isEqualTo(removed.getUsername());
		assertThat(store.has(AvatarService.objectKey(person.getId()))).isFalse();
		assertThatThrownBy(() -> avatars.get(person.getId()))
				.isInstanceOfSatisfying(ApiException.class, missing -> assertThat(missing.getStatus().value()).isEqualTo(404));

		assertThat(surface(ContentKind.PERSON).restore(removal)).isEqualTo(RestoreOutcome.RESTORED);
		User back = users.findById(person.getId()).orElseThrow();
		assertThat(back.getDisplayName()).isEqualTo("Jonas der Schreckliche");
		assertThat(back.getUsername()).isEqualTo("jonas");
		assertThat(back.getAvatarUrl()).isNotNull();
		assertThat(store.has(AvatarService.objectKey(person.getId()))).isTrue();
	}

	@Test
	void thePictureRouteIsPrivateForAnHourAndTagged() {
		User person = withProfile();

		ResponseEntity<byte[]> picture = avatars.get(person.getId());

		assertThat(picture.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=3600, private");
		assertThat(picture.getHeaders().getETag()).isNotBlank();
	}

	@Test
	void noPictureIsServedForADeactivatedAccountOrOneWithoutAPicture() {
		User person = withProfile();
		person.setActive(false);
		users.save(person);
		assertThatThrownBy(() -> avatars.get(person.getId())).isInstanceOf(ApiException.class);

		store.put(AvatarService.objectKey(reader.getId()), "leftover");
		assertThatThrownBy(() -> avatars.get(reader.getId())).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> avatars.get("no-such-person")).isInstanceOf(ApiException.class);
	}
}
