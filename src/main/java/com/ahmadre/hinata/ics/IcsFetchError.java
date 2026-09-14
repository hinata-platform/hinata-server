package com.ahmadre.hinata.ics;

/** Why a calendar could not be fetched, each with the sentence a person reads about it. */
public enum IcsFetchError {

	/** Not an https or webcal address, or no address at all. */
	URL_INVALID("error.ics.urlInvalid"),

	/** A user name or password in the address. Calendar services put their token in the query instead. */
	CREDENTIALS_IN_URL("error.ics.credentialsInUrl"),

	/** A port other than 443 or 8443. */
	PORT_NOT_ALLOWED("error.ics.portNotAllowed"),

	/**
	 * An address literal, a name that does not resolve, a name that resolves to an
	 * address off the public internet, or a host the operator's lists refuse. One
	 * answer for all of them, so that a refusal tells nothing about which internal
	 * names exist.
	 */
	HOST_NOT_ALLOWED("error.ics.hostNotAllowed"),

	/** Any 3xx other than 304. Redirects are never followed. */
	REDIRECT("error.ics.redirect"),

	/** Any other status than 200, including a 304 nobody asked for; the result carries it, the message takes it as {0}. */
	HTTP_STATUS("error.ics.httpStatus"),

	/** Neither labelled {@code text/calendar} nor named {@code .ics}, or empty. */
	NOT_A_CALENDAR("error.ics.notACalendar"),

	/**
	 * A {@code Content-Encoding} other than gzip, or a body labelled gzip that is not.
	 * gzip itself is unpacked, because iCloud sends it whatever is asked for.
	 */
	ENCODING("error.ics.encoding"),

	/** More than 2 MB, declared or read. */
	TOO_LARGE("error.ics.tooLarge"),

	/** The lookup, the connection or the body took longer than the fetch may. */
	TIMEOUT("error.ics.timeout"),

	/** The TLS handshake failed, including a certificate that does not fit the host. */
	TLS_FAILED("error.ics.tlsFailed"),

	/** Anything else on the way there: refused connection, reset, closed socket. */
	UNREACHABLE("error.ics.unreachable"),

	/** The fetch queue is full. Nothing was attempted; try again later. */
	BUSY("error.ics.busy");

	private final String messageKey;

	IcsFetchError(String messageKey) {
		this.messageKey = messageKey;
	}

	/** Key into {@code messages*.properties}. */
	public String messageKey() {
		return messageKey;
	}
}
