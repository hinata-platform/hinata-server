package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import org.springframework.http.HttpStatus;

/**
 * A sign-in refused for one of the {@link SignInRefusal} reasons. Answers 403 with the reason's
 * message like any {@link ApiException}, and carries what a door needs to record it: the reason,
 * and the account that was found, or null when there is none the person may be named as.
 *
 * <p>What the person is told and what the audit log records can differ: somebody who only typed
 * an address is not told the state of the account behind it (see
 * {@link com.ahmadre.hinata.user.SsoAccounts}).
 */
public class SignInRefusedException extends ApiException {

	private final transient SignInRefusal refusal;
	private final transient User account;
	private final transient SignInRefusal recorded;

	public SignInRefusedException(SignInRefusal refusal, User account) {
		this(refusal, account, refusal);
	}

	/** Tells the person [refusal] and records [recorded], the reason it really was. */
	public SignInRefusedException(SignInRefusal refusal, User account, SignInRefusal recorded) {
		super(HttpStatus.FORBIDDEN, refusal.messageKey());
		this.refusal = refusal;
		this.account = account;
		this.recorded = recorded;
	}

	/** The reason the person is told. */
	public SignInRefusal refusal() {
		return refusal;
	}

	/** The reason the audit log records. */
	public SignInRefusal recorded() {
		return recorded;
	}

	/** The account the sign-in was refused for, or null. */
	public User account() {
		return account;
	}
}
