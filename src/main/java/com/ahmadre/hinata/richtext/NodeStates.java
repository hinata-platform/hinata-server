package com.ahmadre.hinata.richtext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The NodeState of a stored document (HIN-181): upstream Lexical keeps a node's extra state under
 * {@code "$"}, and this server reads one key of it, the block style ({@code "$":{"style":…}}).
 * A client may send any string there, so every one is put through {@link CssStyles#block} before
 * it is stored: what a document keeps is the allow-listed CSS, never what somebody typed.
 */
final class NodeStates {

	private NodeStates() {
	}

	/**
	 * Sanitises the block style of every node of [document] in place and says whether one changed.
	 * A style with nothing left is removed, and an emptied {@code "$"} with it. Other NodeState
	 * keys are kept: upstream and other clients may write their own, and none of them is rendered
	 * as CSS. Iterative and bounded like every walk in {@link LexicalJson}.
	 */
	static boolean sanitize(JsonNode document) {
		JsonNode root = document == null ? null : document.get("root");
		if (root == null) return false;
		boolean changed = false;
		Deque<JsonNode> work = new ArrayDeque<>();
		work.push(root);
		int nodes = 0;
		while (!work.isEmpty()) {
			JsonNode node = work.pop();
			if (!(node instanceof ObjectNode object)) continue;
			if (++nodes > LexicalJson.MAX_NODES) break;
			changed |= sanitizeOne(object);
			JsonNode caption = node.path("caption").path("editorState").path("root");
			if (caption.isObject()) work.push(caption);
			JsonNode children = node.get("children");
			if (children != null && children.isArray()) {
				for (JsonNode child : children) work.push(child);
			}
		}
		return changed;
	}

	private static boolean sanitizeOne(ObjectNode node) {
		JsonNode state = node.get(LexicalNodes.STATE);
		if (state == null) return false;
		if (!(state instanceof ObjectNode own)) {
			// Upstream only ever writes an object here.
			node.remove(LexicalNodes.STATE);
			return true;
		}
		JsonNode style = own.get("style");
		if (style == null) return false;
		String clean = style.isTextual() ? CssStyles.block(style.asText()) : "";
		if (style.isTextual() && clean.equals(style.asText())) return false;
		if (clean.isEmpty()) {
			own.remove("style");
			if (own.isEmpty()) node.remove(LexicalNodes.STATE);
		}
		else {
			own.put("style", clean);
		}
		return true;
	}
}
