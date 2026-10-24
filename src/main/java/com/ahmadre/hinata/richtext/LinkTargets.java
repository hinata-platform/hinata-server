package com.ahmadre.hinata.richtext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Locale;

/**
 * Where a link in a stored text may lead (HIN-176): {@code http}, {@code https}, {@code mailto}
 * and the app's own paths. Anything else ({@code javascript:}, {@code data:}, a path the browser
 * reads as another host such as {@code //evil.example}) loses its link and keeps its words.
 */
final class LinkTargets {

	private LinkTargets() {
	}

	/** Whether [url] leads somewhere a link may lead. */
	static boolean isSafe(String url) {
		if (url == null) {
			return false;
		}
		// A browser ignores tabs, line breaks and spaces inside an address; so does this check.
		StringBuilder compact = new StringBuilder(url.length());
		for (int i = 0; i < url.length(); i++) {
			char c = url.charAt(i);
			if (c > 0x20 && c != 0x7F) {
				compact.append(c);
			}
		}
		if (compact.isEmpty()) {
			return false;
		}
		if (compact.charAt(0) == '/') {
			// One slash is a path of the app; a second, or a backslash, names another host.
			return compact.length() == 1 || compact.charAt(1) != '/' && compact.charAt(1) != '\\';
		}
		int colon = compact.indexOf(":");
		if (colon <= 0) {
			return false;
		}
		String scheme = compact.substring(0, colon).toLowerCase(Locale.ROOT);
		return scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto");
	}

	/** Replaces every link of [document] that may not lead where it leads by its words; true when one was. */
	static boolean unwrapUnsafe(JsonNode document) {
		return document != null && unwrap(document.get("root"));
	}

	private static boolean unwrap(JsonNode node) {
		if (node == null || !(node.get("children") instanceof ArrayNode children)) {
			return false;
		}
		boolean changed = false;
		for (int i = 0; i < children.size(); i++) {
			JsonNode child = children.get(i);
			if (isLink(child) && !isSafe(child.path("url").asText(null))) {
				ArrayNode words = child.get("children") instanceof ArrayNode inner ? inner : null;
				children.remove(i);
				if (words != null) {
					for (int w = 0; w < words.size(); w++) {
						children.insert(i + w, words.get(w));
					}
				}
				changed = true;
				// The words that took its place are looked at in their turn.
				i--;
				continue;
			}
			changed |= unwrap(child);
		}
		return changed;
	}

	private static boolean isLink(JsonNode node) {
		String type = node.path("type").asText("");
		return type.equals("link") || type.equals("autolink");
	}
}
