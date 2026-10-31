package com.ahmadre.hinata.richtext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Documents with a mail's layout, built for tests the way the converter builds them. */
public final class LayoutDocs {

	public static final ObjectMapper MAPPER = new ObjectMapper();
	public static final LexicalNodes NODES = new LexicalNodes(MAPPER);

	private LayoutDocs() {
	}

	public static ObjectNode paragraph(String text) {
		ArrayNode children = NODES.array();
		children.add(NODES.text(text, 0));
		return NODES.paragraph(children, 0);
	}

	public static ObjectNode imageParagraph(String src) {
		ArrayNode children = NODES.array();
		children.add(NODES.image(src, "Logo", 120, 40, 120));
		return NODES.paragraph(children, 0);
	}

	public static ObjectNode item(ObjectNode... blocks) {
		ArrayNode children = NODES.array();
		for (ObjectNode block : blocks) children.add(block);
		return NODES.layoutItem(children);
	}

	public static ObjectNode columns(String template, ObjectNode... items) {
		ArrayNode children = NODES.array();
		for (ObjectNode item : items) children.add(item);
		return NODES.layoutContainer(template, children);
	}

	public static String doc(ObjectNode... blocks) {
		ArrayNode children = NODES.array();
		for (ObjectNode block : blocks) children.add(block);
		return write(NODES.document(children));
	}

	public static String write(JsonNode node) {
		try {
			return MAPPER.writeValueAsString(node);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public static JsonNode read(String json) {
		try {
			return MAPPER.readTree(json);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** Every node of [json] of [type], in document order. */
	public static List<JsonNode> nodes(String json, String type) {
		List<JsonNode> found = new ArrayList<>();
		collect(read(json), type, found);
		return found;
	}

	private static void collect(JsonNode node, String type, List<JsonNode> out) {
		if (type.equals(node.path("type").asText())) out.add(node);
		node.forEach(child -> collect(child, type, out));
	}
}
