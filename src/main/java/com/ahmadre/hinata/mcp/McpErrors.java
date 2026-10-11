package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Turns a refusal into something an agent can read and act on.
 *
 * <p>A tool that throws hands the framework an exception, and what reaches the client is that
 * exception's message. For an {@link ApiException} that is a message <em>key</em> —
 * {@code error.time.locked} — with the facts that explain it left behind in
 * {@link ApiException#getDetails()}. The REST handler renders both; nothing did that here. So an
 * agent asked to correct an invoiced entry was told a key, not that the entry sits on
 * invoice INV-2026-00001 and what would free it.
 *
 * <p>{@link #readable} renders the sentence in the caller's language and appends the
 * {@code reason}/{@code holder}/{@code remedy} triple the app reads, so a client sees the same
 * explanation a person in the app does. The thrown {@link Refusal} carries no cause on purpose:
 * the framework prints the message of the innermost cause, and that would be the key again.
 *
 * <p>The time tools use it; the older tools keep their wording, which some clients already match.
 */
@Component
@RequiredArgsConstructor
public class McpErrors {

	/** The details a refusal may carry, in the order a reader wants them. */
	private static final List<String> DETAIL_KEYS = List.of("reason", "holder", "remedy");

	private final UserWords words;

	/** A tool refusal whose message is already the text the client shows. */
	public static final class Refusal extends RuntimeException {

		Refusal(String message) {
			super(message, null, false, false);
		}
	}

	/** Runs [call], rendering any refusal it raises for [caller]. */
	public <T> T readable(User caller, Supplier<T> call) {
		try {
			return call.get();
		}
		catch (ApiException refusal) {
			throw new Refusal(describe(caller, refusal));
		}
	}

	/** As {@link #readable(User, Supplier)}, for a call that answers nothing. */
	public void readable(User caller, Runnable call) {
		readable(caller, () -> {
			call.run();
			return null;
		});
	}

	/**
	 * Refuses unless [enabled], with the same key the REST gates answer: a module that is off is
	 * off for every way in. MCP runs outside the HTTP interceptors, so each tool asks itself.
	 */
	public void requireEnabled(boolean enabled, User caller) {
		if (!enabled) {
			throw new Refusal(words.of(caller, "error.feature.disabled"));
		}
	}

	/** Refuses a text longer than [max] characters, which the REST routes do by Bean Validation. */
	public void requireLength(String value, int max, String field, User caller) {
		if (value != null && value.length() > max) {
			throw new Refusal(words.of(caller, "error.mcp.tooLong", field, max));
		}
	}

	/** Refuses a list of more than [max] values, or one of them longer than [maxLength]. */
	public void requireValues(List<String> values, int max, int maxLength, String field, User caller) {
		if (values == null) {
			return;
		}
		if (values.size() > max) {
			throw new Refusal(words.of(caller, "error.mcp.tooMany", field, max));
		}
		for (String value : values) {
			requireLength(value, maxLength, field, caller);
		}
	}

	/** Refuses a number outside [min]..[max]. */
	public void requireRange(Integer value, int min, int max, String field, User caller) {
		if (value != null && (value < min || value > max)) {
			throw new Refusal(words.of(caller, "error.mcp.outOfRange", field, min, max));
		}
	}

	/** The refusal for a value [field] does not accept, such as an unknown enum name. */
	public Refusal badValue(String field, User caller) {
		return new Refusal(words.of(caller, "error.mcp.badValue", field));
	}

	String describe(User caller, ApiException refusal) {
		StringBuilder text = new StringBuilder(
				words.of(caller, refusal.getMessageKey(), refusal.getArgs()));
		Map<String, String> details = refusal.getDetails();
		// The three a client acts on first, then the rest (a lock date, an approval's period, an
		// invoice id) by name: the exception keeps the details in a map without an order.
		List<String> facts = details.entrySet().stream()
				.filter(fact -> fact.getValue() != null && !fact.getValue().isBlank())
				.sorted(Comparator.comparingInt((Map.Entry<String, String> fact) -> rank(fact.getKey()))
						.thenComparing(Map.Entry::getKey))
				.map(fact -> fact.getKey() + ": " + fact.getValue())
				.toList();
		if (!facts.isEmpty()) {
			text.append(" (").append(String.join("; ", facts)).append(')');
		}
		return text.toString();
	}

	private static int rank(String key) {
		int index = DETAIL_KEYS.indexOf(key);
		return index < 0 ? DETAIL_KEYS.size() : index;
	}
}
