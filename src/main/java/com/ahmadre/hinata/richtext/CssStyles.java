package com.ahmadre.hinata.richtext;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The CSS a stored document may carry (HIN-181), and nothing else. Two allow-lists: the block style
 * an element keeps in its NodeState ({@code "$":{"style":…}}) and the text style a text node keeps
 * in {@code style}. Every value is parsed and written back in one normal form ({@code pt} and
 * {@code em} become {@code px}, colors lower case); a declaration whose property or value is not
 * understood is dropped, never passed through. So whatever an HTML mail or a client sends, what is
 * stored is a short list of known declarations a renderer can read without a CSS parser.
 */
public final class CssStyles {

	private CssStyles() {
	}

	/** Longest style string kept, in either list. */
	public static final int MAX_CHARS = 512;

	/** Longest single value read; anything longer is no value a mail needs. */
	private static final int MAX_VALUE_CHARS = 200;

	private static final Pattern HEX = Pattern.compile("#(?:[0-9a-f]{3}|[0-9a-f]{6})");
	private static final Pattern RGB = Pattern.compile(
			"rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*(?:,\\s*(0|1|0?\\.\\d{1,3}|1\\.0+)\\s*)?\\)");
	private static final Pattern NUMBER_UNIT = Pattern.compile("(-?\\d{1,5}(?:\\.\\d{1,4})?|-?\\.\\d{1,4})(px|pt|em|%)?");
	private static final Pattern FONT_FAMILY = Pattern.compile("[a-z0-9 ,'\"\\-]{1,120}");

	/** The CSS named colors. */
	private static final Set<String> NAMED_COLORS = Set.of("aliceblue", "antiquewhite", "aqua",
			"aquamarine", "azure", "beige", "bisque", "black", "blanchedalmond", "blue", "blueviolet", "brown",
			"burlywood", "cadetblue", "chartreuse", "chocolate", "coral", "cornflowerblue", "cornsilk",
			"crimson", "cyan", "darkblue", "darkcyan", "darkgoldenrod", "darkgray", "darkgreen", "darkgrey",
			"darkkhaki", "darkmagenta", "darkolivegreen", "darkorange", "darkorchid", "darkred", "darksalmon",
			"darkseagreen", "darkslateblue", "darkslategray", "darkslategrey", "darkturquoise", "darkviolet",
			"deeppink", "deepskyblue", "dimgray", "dimgrey", "dodgerblue", "firebrick", "floralwhite",
			"forestgreen", "fuchsia", "gainsboro", "ghostwhite", "gold", "goldenrod", "gray", "green",
			"greenyellow", "grey", "honeydew", "hotpink", "indianred", "indigo", "ivory", "khaki", "lavender",
			"lavenderblush", "lawngreen", "lemonchiffon", "lightblue", "lightcoral", "lightcyan",
			"lightgoldenrodyellow", "lightgray", "lightgreen", "lightgrey", "lightpink", "lightsalmon",
			"lightseagreen", "lightskyblue", "lightslategray", "lightslategrey", "lightsteelblue",
			"lightyellow", "lime", "limegreen", "linen", "magenta", "maroon", "mediumaquamarine",
			"mediumblue", "mediumorchid", "mediumpurple", "mediumseagreen", "mediumslateblue",
			"mediumspringgreen", "mediumturquoise", "mediumvioletred", "midnightblue", "mintcream",
			"mistyrose", "moccasin", "navajowhite", "navy", "oldlace", "olive", "olivedrab", "orange",
			"orangered", "orchid", "palegoldenrod", "palegreen", "paleturquoise", "palevioletred",
			"papayawhip", "peachpuff", "peru", "pink", "plum", "powderblue", "purple", "rebeccapurple", "red",
			"rosybrown", "royalblue", "saddlebrown", "salmon", "sandybrown", "seagreen", "seashell", "sienna",
			"silver", "skyblue", "slateblue", "slategray", "slategrey", "snow", "springgreen", "steelblue",
			"tan", "teal", "thistle", "tomato", "turquoise", "violet", "wheat", "white", "whitesmoke",
			"yellow", "yellowgreen", "transparent");

	/**
	 * The block style of an element, sanitised: {@code background-color}, {@code color},
	 * {@code padding} and its sides, {@code margin-top}, {@code margin-bottom}, centering
	 * ({@code margin-left:auto;margin-right:auto}, also from {@code margin:0 auto}),
	 * {@code width}, {@code max-width}, {@code border-radius}, {@code border},
	 * {@code border-top}, {@code border-bottom}. Empty when nothing is left.
	 */
	public static String block(String css) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String[] declaration : declarations(css)) {
			String property = declaration[0];
			String value = declaration[1];
			switch (property) {
				case "background-color", "color" -> put(out, property, color(value));
				case "background" -> put(out, "background-color", color(value));
				case "padding" -> put(out, property, lengths(value, 1, 4, false));
				case "padding-top", "padding-right", "padding-bottom", "padding-left", "margin-top",
						"margin-bottom" -> put(out, property, length(value, false));
				case "margin" -> margin(value, out);
				case "margin-left", "margin-right" -> {
					if ("auto".equals(value)) put(out, property, "auto");
				}
				case "width", "max-width" -> put(out, property, length(value, true));
				case "border-radius" -> put(out, property, lengths(value, 1, 4, false));
				case "border", "border-top", "border-bottom" -> put(out, property, border(value));
				default -> {
					// Not on the list: dropped.
				}
			}
		}
		return join(out);
	}

	/**
	 * The text style of a text node, sanitised: {@code color}, {@code background-color},
	 * {@code font-size}, {@code font-family}, {@code font-weight}, {@code font-style},
	 * {@code line-height}, {@code letter-spacing}, {@code text-transform},
	 * {@code text-decoration}. Empty when nothing is left.
	 */
	public static String text(String css) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String[] declaration : declarations(css)) {
			String property = declaration[0];
			String value = declaration[1];
			switch (property) {
				case "color", "background-color" -> put(out, property, color(value));
				case "font-size", "letter-spacing" -> put(out, property, length(value, false));
				case "font-family" -> put(out, property, fontFamily(value));
				case "font-weight" -> put(out, property, fontWeight(value));
				case "font-style" -> put(out, property, oneOf(value, "normal", "italic", "oblique"));
				case "line-height" -> put(out, property, lineHeight(value));
				case "text-transform" -> put(out, property,
						oneOf(value, "none", "uppercase", "lowercase", "capitalize"));
				case "text-decoration", "text-decoration-line" -> put(out, "text-decoration",
						oneOf(value, "none", "underline", "line-through"));
				default -> {
					// Not on the list: dropped.
				}
			}
		}
		return join(out);
	}

	/** {@code prop:value} pairs of [css], lower-cased, {@code !important} dropped. */
	private static Iterable<String[]> declarations(String css) {
		java.util.List<String[]> out = new java.util.ArrayList<>();
		if (css == null || css.isBlank()) return out;
		// A bound on what is read at all: a style attribute of a mail can be long, but a
		// declaration past this point would be dropped by the output bound anyway.
		String source = css.length() > 8 * MAX_CHARS ? css.substring(0, 8 * MAX_CHARS) : css;
		for (String part : source.split(";")) {
			int colon = part.indexOf(':');
			if (colon <= 0) continue;
			String property = part.substring(0, colon).strip().toLowerCase(Locale.ROOT);
			String value = part.substring(colon + 1).strip().toLowerCase(Locale.ROOT);
			value = value.replace("!important", "").strip();
			if (value.isEmpty() || value.length() > MAX_VALUE_CHARS) continue;
			out.add(new String[] { property, value });
		}
		return out;
	}

	private static void put(Map<String, String> out, String property, String value) {
		if (value != null) out.put(property, value);
	}

	private static String join(Map<String, String> declarations) {
		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, String> entry : declarations.entrySet()) {
			String declaration = entry.getKey() + ":" + entry.getValue();
			int length = out.length() + (out.isEmpty() ? 0 : 1) + declaration.length();
			if (length > MAX_CHARS) break;
			if (!out.isEmpty()) out.append(';');
			out.append(declaration);
		}
		return out.toString();
	}

	/** A color in normal form, or null. */
	static String color(String value) {
		if (HEX.matcher(value).matches()) return value;
		var rgb = RGB.matcher(value);
		if (rgb.matches()) {
			int r = Integer.parseInt(rgb.group(1));
			int g = Integer.parseInt(rgb.group(2));
			int b = Integer.parseInt(rgb.group(3));
			if (r > 255 || g > 255 || b > 255) return null;
			String alpha = rgb.group(4);
			return alpha == null ? "rgb(" + r + "," + g + "," + b + ")"
					: "rgba(" + r + "," + g + "," + b + "," + alpha + ")";
		}
		return NAMED_COLORS.contains(value) ? value : null;
	}

	/**
	 * One length in px, or null. {@code pt} counts 4/3 px, {@code em} 16 px; a bare number is
	 * px (the HTML attribute spelling); [percent] admits {@code %}.
	 */
	static String length(String value, boolean percent) {
		var matcher = NUMBER_UNIT.matcher(value);
		if (!matcher.matches()) return null;
		double number = Double.parseDouble(matcher.group(1));
		String unit = matcher.group(2);
		if (number < 0 || number > 10_000) return null;
		if ("%".equals(unit)) {
			return percent && number <= 100 ? format(number) + "%" : null;
		}
		double px = switch (unit == null ? "px" : unit) {
			case "pt" -> number * 4 / 3;
			case "em" -> number * 16;
			default -> number;
		};
		return format(px) + "px";
	}

	/** Between [min] and [max] space-separated lengths, or null. */
	private static String lengths(String value, int min, int max, boolean percent) {
		String[] parts = value.split("\\s+");
		if (parts.length < min || parts.length > max) return null;
		StringBuilder out = new StringBuilder();
		for (String part : parts) {
			String length = length(part, percent);
			if (length == null) return null;
			if (!out.isEmpty()) out.append(' ');
			out.append(length);
		}
		return out.toString();
	}

	/** {@code margin}: its vertical sides, and centering when both horizontal ones are auto. */
	private static void margin(String value, Map<String, String> out) {
		String[] parts = value.split("\\s+");
		String top;
		String right;
		String bottom;
		String left;
		switch (parts.length) {
			case 1 -> top = right = bottom = left = parts[0];
			case 2 -> {
				top = bottom = parts[0];
				right = left = parts[1];
			}
			case 3 -> {
				top = parts[0];
				right = left = parts[1];
				bottom = parts[2];
			}
			case 4 -> {
				top = parts[0];
				right = parts[1];
				bottom = parts[2];
				left = parts[3];
			}
			default -> {
				return;
			}
		}
		put(out, "margin-top", length(top, false));
		put(out, "margin-bottom", length(bottom, false));
		if ("auto".equals(left) && "auto".equals(right)) {
			out.put("margin-left", "auto");
			out.put("margin-right", "auto");
		}
	}

	/** {@code Npx solid color} in any order, or null; {@code none} and other styles drop it. */
	private static String border(String value) {
		String width = null;
		String color = null;
		boolean solid = false;
		for (String part : value.split("\\s+(?![^(]*\\))")) {
			if ("solid".equals(part)) {
				solid = true;
			}
			else if (width == null && length(part, false) != null) {
				width = length(part, false);
			}
			else if (color == null && color(part) != null) {
				color = color(part);
			}
			else {
				return null;
			}
		}
		if (!solid || width == null) return null;
		return width + " solid " + (color == null ? "#000000" : color);
	}

	private static String fontFamily(String value) {
		return FONT_FAMILY.matcher(value).matches() ? value.replace('"', '\'') : null;
	}

	private static String fontWeight(String value) {
		if (value.equals("bold") || value.equals("normal") || value.equals("bolder") || value.equals("lighter")) {
			return value;
		}
		return value.matches("[1-9]00") ? value : null;
	}

	private static String lineHeight(String value) {
		if ("normal".equals(value)) return value;
		if (value.matches("\\d{1,2}(?:\\.\\d{1,3})?")) return value;
		return length(value, true);
	}

	private static String oneOf(String value, String... allowed) {
		for (String option : allowed) {
			if (option.equals(value)) return value;
		}
		return null;
	}

	private static String format(double value) {
		double rounded = Math.round(value * 100) / 100.0;
		return rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Double.toString(rounded);
	}
}
