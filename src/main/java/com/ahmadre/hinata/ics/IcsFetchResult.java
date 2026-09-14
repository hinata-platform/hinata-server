package com.ahmadre.hinata.ics;

import java.util.Arrays;
import java.util.Objects;

/**
 * What one fetch of an external calendar came to.
 *
 * @param outcome      a new calendar, an unchanged one, or nothing
 * @param body         the calendar's bytes when {@link Outcome#FETCHED}, at most 2 MB; otherwise null
 * @param etag         the validator to send with the next fetch, when the server gave a usable one
 * @param lastModified likewise
 * @param error        why nothing came back, when {@link Outcome#FAILED}
 * @param httpStatus   the server's status when there was a response, otherwise 0
 */
public record IcsFetchResult(Outcome outcome, byte[] body, String etag, String lastModified,
		IcsFetchError error, int httpStatus) {

	public enum Outcome {
		FETCHED, NOT_MODIFIED, FAILED
	}

	static IcsFetchResult fetched(byte[] body, String etag, String lastModified) {
		return new IcsFetchResult(Outcome.FETCHED, body, etag, lastModified, null, 200);
	}

	static IcsFetchResult notModified(String etag, String lastModified) {
		return new IcsFetchResult(Outcome.NOT_MODIFIED, null, etag, lastModified, null, 304);
	}

	static IcsFetchResult failed(IcsFetchError error) {
		return failed(error, 0);
	}

	static IcsFetchResult failed(IcsFetchError error, int httpStatus) {
		return new IcsFetchResult(Outcome.FAILED, null, null, null, error, httpStatus);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof IcsFetchResult that
				&& outcome == that.outcome
				&& Arrays.equals(body, that.body)
				&& Objects.equals(etag, that.etag)
				&& Objects.equals(lastModified, that.lastModified)
				&& error == that.error
				&& httpStatus == that.httpStatus;
	}

	@Override
	public int hashCode() {
		return Objects.hash(outcome, Arrays.hashCode(body), etag, lastModified, error, httpStatus);
	}

	/** Never the calendar itself: its content is somebody's schedule. */
	@Override
	public String toString() {
		return "IcsFetchResult[outcome=" + outcome + ", bytes=" + (body == null ? 0 : body.length)
				+ ", error=" + error + ", httpStatus=" + httpStatus + "]";
	}
}
