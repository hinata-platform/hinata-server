package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.user.HouseRules;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A token issued before the house rules keeps reading and stops writing until its owner accepts
 * them (HIN-175), whatever the credential.
 */
class ScopeGuardRulesTest {

	private final User owner = User.builder().id("u1").build();
	private final HouseRules rules = mock(HouseRules.class);
	private ScopeGuard guard;

	@BeforeEach
	void setUp() {
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(owner);
		@SuppressWarnings("unchecked")
		ObjectProvider<HouseRules> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(rules);
		guard = new ScopeGuard(currentUser, provider);
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u1", null,
				"SCOPE_" + Scopes.ISSUES_READ, "SCOPE_" + Scopes.ISSUES_WRITE));
		when(rules.pending(owner)).thenReturn(true);
		doCallRealMethod().when(rules).requireAccepted(owner);
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void readingGoesOnAndWritingWaitsForTheRules() {
		assertThatCode(() -> guard.require(Scopes.ISSUES_READ)).doesNotThrowAnyException();
		assertThatThrownBy(() -> guard.require(Scopes.ISSUES_WRITE))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(ex.getMessageKey()).isEqualTo("error.rules.acceptRequired");
				});
	}

	@Test
	void onceAcceptedTheTokenWritesAgain() {
		when(rules.pending(owner)).thenReturn(false);

		assertThatCode(() -> guard.require(Scopes.ISSUES_WRITE)).doesNotThrowAnyException();
	}

	@Test
	void aMissingScopeIsStillTheFirstAnswer() {
		assertThatThrownBy(() -> guard.require(Scopes.KB_WRITE))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getMessageKey()).isEqualTo("error.mcp.scopeMissing"));
	}
}
