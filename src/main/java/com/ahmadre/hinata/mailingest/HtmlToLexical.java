package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.richtext.CssStyles;
import com.ahmadre.hinata.richtext.LexicalJson;
import com.ahmadre.hinata.richtext.LexicalNodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts an HTML mail into Lexical blocks that keep its layout (HIN-181): columns become
 * {@code layout-container}/{@code layout-item}, a cell's background, padding, border and width
 * become the block style of what it holds ({@code "$":{"style":…}}), text keeps its color, size
 * and font, images keep their size. The result is an ordinary editable document, not a picture of
 * the mail.
 *
 * <p>What a reader would never see is left out before anything is converted: the head and its
 * styles, scripts, hidden elements and the preheader, the mobile copy of a block the mail also
 * carries for the desktop, tracking pixels and spacer images. Only the CSS {@link CssStyles} lists
 * is kept, each value checked; a link keeps its address and {@code RichTextService} decides later
 * whether it may lead there.
 *
 * <p>Bounded like every converter here: element nesting is read to {@value #MAX_DEPTH} levels
 * (deeper content keeps its text), a style sheet to {@value #MAX_CSS_RULES} rules, and the stored
 * document to the bounds of {@code LexicalJson}; a mail past them goes the markdown way.
 */
final class HtmlToLexical {

	/** Largest HTML body converted at all; a newsletter is rarely a tenth of it. */
	static final int MAX_HTML_CHARS = 2_000_000;

	/** Element nesting read; mails nest tables a dozen levels, rarely more. */
	private static final int MAX_DEPTH = 48;

	/** Rules of a mail's style sheets taken into account. */
	private static final int MAX_CSS_RULES = 500;

	/** Text of a data-table cell is short; a layout cell holds paragraphs. */
	private static final int DATA_CELL_CHARS = 60;

	private static final Set<String> DROPPED = Set.of("head", "style", "script", "meta", "link", "title",
			"noscript", "template", "iframe", "object", "embed", "svg", "math", "button", "input", "select",
			"textarea", "map", "area", "audio", "video", "canvas");

	private static final Set<String> BLOCKS = Set.of("div", "p", "center", "section", "article", "header",
			"footer", "main", "aside", "nav", "address", "figure", "figcaption", "dl", "dt", "dd", "form",
			"fieldset", "li", "blockquote", "body", "tr", "td", "th", "tbody", "thead", "tfoot", "caption");

	/** Text properties a block hands down to the text it holds. */
	private static final Set<String> INHERITED_TEXT = Set.of("color", "font-family", "font-size", "font-weight",
			"font-style", "line-height", "letter-spacing", "text-transform", "text-decoration");

	private static final Set<String> ALIGNMENTS = Set.of("left", "center", "right", "justify");

	/** Pixel sizes of {@code <font size>} 1 to 7, as browsers draw them. */
	private static final int[] FONT_SIZES = { 10, 13, 16, 18, 24, 32, 48 };

	private static final Pattern SIMPLE_SELECTOR = Pattern.compile("[a-z][a-z0-9]*|[a-z0-9]*\\.[a-z_][a-z0-9_-]*");
	private static final Pattern PX = Pattern.compile("\\s*(\\d{1,5})(?:\\.\\d+)?\\s*(?:px)?\\s*");
	private static final Pattern PERCENT = Pattern.compile("\\s*(\\d{1,3})(?:\\.\\d+)?\\s*%\\s*");
	private static final Pattern SPACER = Pattern.compile("(?i)(spacer|blank|clear|transparent|pixel)\\.gif");
	private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u200B\\u200C\\u200D\\uFEFF\\u034F]+");
	private static final Pattern MOBILE = Pattern.compile("(?i)(^|[-_])(mob|mobile|phone)([-_]|$|only)");
	private static final Pattern UTILITY = Pattern.compile("full|width|fluid|stack|padding|center|text|align");
	/** An MJML column: its desktop width in percent or pixels. */
	private static final Pattern MJ_COLUMN = Pattern.compile("mj-column-(per|px)-(\\d{1,4})(?:-(\\d{1,2}))?");
	private static final Pattern DESKTOP = Pattern.compile("(?i)(^|[-_])(desk|desktop)([-_]|$|only)");

	private final LexicalNodes nodes;

	HtmlToLexical(ObjectMapper mapper) {
		this.nodes = new LexicalNodes(mapper);
	}

	/**
	 * The blocks of [html], a mail body.
	 *
	 * @throws IllegalArgumentException when the body is too large or holds nothing to show
	 */
	ArrayNode convert(String html) {
		if (html == null || html.isBlank()) throw new IllegalArgumentException("empty body");
		if (html.length() > MAX_HTML_CHARS) throw new IllegalArgumentException("body too large");
		Document document = Jsoup.parse(html);
		Map<Element, String> sheet = styleSheet(document);
		document.select(String.join(",", DROPPED)).remove();
		applySheet(sheet);
		removeHidden(document);
		removeMobileCopies(document);
		removeNoiseImages(document);
		Element body = document.body();
		Builder out = new Builder();
		block(body, out, Ctx.ROOT.with(body, declarations(body)));
		out.flush();
		if (out.blocks.isEmpty()) throw new IllegalArgumentException("nothing to show");
		ArrayNode blocks = nodes.array();
		out.blocks.forEach(blocks::add);
		return blocks;
	}

	// --- preprocessing --------------------------------------------------------

	/**
	 * The declarations each element gets from the mail's {@code <style>} sheets: rules with a
	 * plain tag, class or tag.class selector, outside any {@code @media} or other at-rule, so
	 * what is kept is what a desktop client shows. Later rules come later in the string.
	 */
	private static Map<Element, String> styleSheet(Document document) {
		Map<Element, String> styles = new IdentityHashMap<>();
		StringBuilder css = new StringBuilder();
		for (Element style : document.select("style")) css.append(style.data()).append('\n');
		String source = css.toString().replaceAll("(?s)/\\*.*?\\*/", "");
		int rules = 0;
		int i = 0;
		while (i < source.length() && rules < MAX_CSS_RULES) {
			int open = source.indexOf('{', i);
			if (open < 0) break;
			String prelude = source.substring(i, open).strip().toLowerCase(Locale.ROOT);
			int close = matching(source, open);
			if (close < 0) break;
			if (!prelude.startsWith("@")) {
				String body = source.substring(open + 1, close);
				for (String selector : prelude.split(",")) {
					String simple = selector.strip();
					if (!SIMPLE_SELECTOR.matcher(simple).matches()) continue;
					rules++;
					for (Element element : document.select(simple)) {
						styles.merge(element, body, (a, b) -> a + ";" + b);
					}
				}
			}
			i = close + 1;
		}
		return styles;
	}

	/** The index of the brace closing the one at [open], or -1. */
	private static int matching(String source, int open) {
		int depth = 0;
		for (int i = open; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '{') depth++;
			else if (c == '}' && --depth == 0) return i;
		}
		return -1;
	}

	/** Puts the sheet's declarations in front of each element's own, which win. */
	private static void applySheet(Map<Element, String> sheet) {
		sheet.forEach((element, css) -> {
			if (element.parent() == null && !"html".equals(element.normalName())) return;
			String own = element.attr("style");
			element.attr("style", own.isBlank() ? css : css + ";" + own);
		});
	}

	/** Elements a mail client does not show, the preheader among them. */
	private static void removeHidden(Document document) {
		for (Element element : document.getAllElements()) {
			if (element.parent() == null) continue;
			Map<String, String> css = inline(element);
			boolean collapsed = ("0".equals(px(css.get("max-height"))) || "0".equals(px(css.get("height"))))
					&& "hidden".equals(css.get("overflow"));
			// Visually hidden for screen readers only: a mail client shows nothing there either.
			boolean screenReaderOnly = "absolute".equals(css.get("position")) && "hidden".equals(css.get("overflow"))
					&& (pixels(null, css.get("width")) == 1 || pixels(null, css.get("height")) == 1);
			if ("none".equals(css.get("display")) || "hidden".equals(css.get("visibility"))
					|| "all".equals(css.get("mso-hide")) || collapsed || screenReaderOnly || element.hasAttr("hidden")
					|| "0".equals(css.get("opacity"))) {
				element.remove();
			}
		}
	}

	/**
	 * Of a block the mail carries twice, for phones and for desktops, keeps the desktop copy. A
	 * copy is told by its classes and id; {@code hide-mobile} is the desktop copy, not the phone's.
	 * Only when the mail carries a desktop copy at all is a phone copy dropped.
	 */
	private static void removeMobileCopies(Document document) {
		List<Element> mobile = new ArrayList<>();
		boolean desktop = false;
		for (Element element : document.getAllElements()) {
			Variant variant = variantOf(element);
			if (variant == Variant.MOBILE) mobile.add(element);
			else if (variant == Variant.DESKTOP) desktop = true;
		}
		if (desktop) mobile.forEach(Element::remove);
	}

	private enum Variant { MOBILE, DESKTOP, NONE }

	private static Variant variantOf(Element element) {
		List<String> tokens = new ArrayList<>(element.classNames());
		if (!element.id().isBlank()) tokens.add(element.id());
		for (String token : tokens) {
			String lower = token.toLowerCase(Locale.ROOT);
			// A utility class that adapts one copy to a phone ("mj-full-width-mobile") names no copy.
			if (UTILITY.matcher(lower).find()) continue;
			boolean hide = lower.contains("hide");
			if (MOBILE.matcher(token).find()) return hide ? Variant.DESKTOP : Variant.MOBILE;
			if (DESKTOP.matcher(token).find()) return hide ? Variant.MOBILE : Variant.DESKTOP;
		}
		return Variant.NONE;
	}

	/** Tracking pixels, spacer images and images without a source, and links left empty by them. */
	private static void removeNoiseImages(Document document) {
		for (Element image : document.select("img")) {
			Map<String, String> css = inline(image);
			String src = image.attr("src").strip();
			int width = pixels(image.attr("width"), css.get("width"));
			int height = pixels(image.attr("height"), css.get("height"));
			boolean tiny = width >= 0 && width <= 2 || height >= 0 && height <= 2;
			boolean spacer = SPACER.matcher(src).find() && image.attr("alt").isBlank();
			if (src.isEmpty() || tiny || spacer) image.remove();
		}
		for (Element link : document.select("a")) {
			if (link.parent() != null && blank(link.text()) && link.selectFirst("img") == null) link.unwrap();
		}
	}

	// --- reading an element ---------------------------------------------------

	/** The element's own inline declarations, lower-cased, later ones winning. */
	private static Map<String, String> inline(Element element) {
		Map<String, String> css = new LinkedHashMap<>();
		String style = element.attr("style");
		if (style.isBlank()) return css;
		for (String part : style.split(";")) {
			int colon = part.indexOf(':');
			if (colon <= 0) continue;
			String property = part.substring(0, colon).strip().toLowerCase(Locale.ROOT);
			String value = part.substring(colon + 1).replace("!important", "").strip();
			if (!value.isEmpty()) css.put(property, value.toLowerCase(Locale.ROOT));
		}
		return css;
	}

	/** Everything an element says about its look: legacy attributes first, then its CSS. */
	private static Map<String, String> declarations(Element element) {
		Map<String, String> css = new LinkedHashMap<>();
		String tag = element.normalName();
		String bgcolor = element.attr("bgcolor").strip();
		if (!bgcolor.isEmpty()) css.put("background-color", bgcolor.toLowerCase(Locale.ROOT));
		String align = element.attr("align").strip().toLowerCase(Locale.ROOT);
		if (!align.isEmpty() && !"table".equals(tag) && !"img".equals(tag)) css.put("text-align", align);
		if ("center".equals(tag)) css.put("text-align", "center");
		if ("font".equals(tag)) {
			String color = element.attr("color").strip();
			if (!color.isEmpty()) css.put("color", color.toLowerCase(Locale.ROOT));
			String face = element.attr("face").strip();
			if (!face.isEmpty()) css.put("font-family", face.toLowerCase(Locale.ROOT));
			String size = element.attr("size").strip();
			if (size.matches("[1-7]")) css.put("font-size", FONT_SIZES[Integer.parseInt(size) - 1] + "px");
		}
		css.putAll(inline(element));
		return css;
	}

	/** A pixel size from an attribute or a CSS value, -1 when there is none. */
	private static int pixels(String attribute, String css) {
		if (css != null) {
			Matcher matcher = PX.matcher(css);
			if (matcher.matches()) return Integer.parseInt(matcher.group(1));
		}
		if (attribute != null) {
			Matcher matcher = PX.matcher(attribute);
			if (matcher.matches()) return Integer.parseInt(matcher.group(1));
		}
		return -1;
	}

	/** "0" for a zero length, the value otherwise, null for none. */
	private static String px(String value) {
		if (value == null) return null;
		return value.matches("0+(\\.0+)?(px|pt|em|%)?") ? "0" : value;
	}

	private static boolean blank(String text) {
		return WHITESPACE.matcher(text).replaceAll("").isEmpty();
	}

	// --- block style ----------------------------------------------------------

	/**
	 * The block style of an element: its listed CSS, a pixel width as {@code max-width} (a mail
	 * column narrows on a phone instead of overflowing), and nothing that changes nothing.
	 * [centered] adds the centering margins of a fixed-width container.
	 */
	private static String blockStyle(Map<String, String> css, boolean withWidth, boolean centered) {
		StringBuilder source = new StringBuilder();
		css.forEach((property, value) -> {
			if (property.equals("width") || property.equals("height") || property.equals("max-width")) return;
			source.append(property).append(':').append(value).append(';');
		});
		if (withWidth) {
			String width = css.get("max-width") != null && css.get("max-width").endsWith("px")
					? css.get("max-width") : css.get("width");
			if (width != null && PX.matcher(width).matches() && !"0".equals(px(width))) {
				source.append("max-width:").append(width).append(';');
			}
		}
		if (centered) source.append("margin-left:auto;margin-right:auto;");
		return withoutNoOps(CssStyles.block(source.toString()));
	}

	/** Drops declarations that draw nothing: zero padding, zero margins, a zero radius. */
	private static String withoutNoOps(String style) {
		if (style.isEmpty()) return style;
		StringBuilder out = new StringBuilder();
		for (String declaration : style.split(";")) {
			int colon = declaration.indexOf(':');
			String property = declaration.substring(0, colon);
			String value = declaration.substring(colon + 1);
			boolean zero = value.matches("0px( 0px){0,3}");
			if (zero && (property.startsWith("padding") || property.startsWith("margin")
					|| property.equals("border-radius"))) {
				continue;
			}
			if (!out.isEmpty()) out.append(';');
			out.append(declaration);
		}
		return out.toString();
	}

	/**
	 * [outer] around [inner], as one style: nested wrappers collapse into one block. Paddings add
	 * up, because the outer box's inset still surrounds the inner one; for everything else the
	 * inner value wins.
	 */
	static String merge(String outer, String inner) {
		if (outer.isEmpty()) return inner;
		if (inner.isEmpty()) return outer;
		Map<String, String> a = parse(outer);
		Map<String, String> b = parse(inner);
		double[] padding = sides(a);
		double[] more = sides(b);
		boolean painted = b.containsKey("background-color") || b.containsKey("border")
				|| b.containsKey("border-top") || b.containsKey("border-bottom");
		if (painted && !Arrays.equals(padding, new double[4])) {
			// Space around a box with a background of its own is outside that box: above and below
			// it becomes margin, beside it there is no way to say it and it is let go.
			a.keySet().removeIf(property -> property.startsWith("padding"));
			if (padding[0] > 0) a.putIfAbsent("margin-top", CssStyles.block("margin-top:" + padding[0] + "px")
					.substring("margin-top:".length()));
			if (padding[2] > 0) a.putIfAbsent("margin-bottom", CssStyles.block("margin-bottom:" + padding[2] + "px")
					.substring("margin-bottom:".length()));
			padding = new double[4];
		}
		Map<String, String> merged = new LinkedHashMap<>();
		a.forEach((property, value) -> {
			if (!property.startsWith("padding")) merged.put(property, value);
		});
		b.forEach((property, value) -> {
			if (!property.startsWith("padding")) merged.put(property, value);
		});
		boolean any = false;
		StringBuilder total = new StringBuilder();
		for (int i = 0; i < 4; i++) {
			double side = padding[i] + more[i];
			any |= side > 0;
			if (i > 0) total.append(' ');
			total.append(CssStyles.block("padding-top:" + side + "px").substring("padding-top:".length()));
		}
		if (any) merged.put("padding", total.toString());
		StringBuilder css = new StringBuilder();
		merged.forEach((property, value) -> css.append(property).append(':').append(value).append(';'));
		return CssStyles.block(css.toString());
	}

	/**
	 * Whether [outer] and [inner] can become one box without changing what is painted: not when
	 * both paint a different background, and not when a painted outer box would shrink to the
	 * width of the inner one.
	 */
	private static boolean mergeable(String outer, String inner) {
		Map<String, String> a = parse(outer);
		Map<String, String> b = parse(inner);
		String background = a.get("background-color");
		boolean painted = background != null || a.containsKey("border") || a.containsKey("border-top")
				|| a.containsKey("border-bottom");
		if (background != null && b.containsKey("background-color") && !background.equals(b.get("background-color"))) {
			return false;
		}
		return !(painted && b.containsKey("max-width") && !b.get("max-width").equals(a.get("max-width")));
	}

	private static Map<String, String> parse(String style) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String declaration : style.split(";")) {
			int colon = declaration.indexOf(':');
			if (colon > 0) out.put(declaration.substring(0, colon), declaration.substring(colon + 1));
		}
		return out;
	}

	/** Top, right, bottom, left padding of a sanitised style, in px. */
	private static double[] sides(Map<String, String> style) {
		double[] sides = new double[4];
		String all = style.get("padding");
		if (all != null) {
			String[] parts = all.split(" ");
			double[] values = new double[parts.length];
			for (int i = 0; i < parts.length; i++) values[i] = Double.parseDouble(parts[i].replace("px", ""));
			switch (values.length) {
				case 1 -> sides = new double[] { values[0], values[0], values[0], values[0] };
				case 2 -> sides = new double[] { values[0], values[1], values[0], values[1] };
				case 3 -> sides = new double[] { values[0], values[1], values[2], values[1] };
				default -> sides = new double[] { values[0], values[1], values[2], values[3] };
			}
		}
		String[] names = { "padding-top", "padding-right", "padding-bottom", "padding-left" };
		for (int i = 0; i < 4; i++) {
			String side = style.get(names[i]);
			if (side != null) sides[i] = Double.parseDouble(side.replace("px", ""));
		}
		return sides;
	}

	// --- context --------------------------------------------------------------

	/**
	 * What an element hands down to its content: alignment, text style, format bits, the link it
	 * is inside of, the heading it is part of, and how deep it sits.
	 */
	private record Ctx(String align, Map<String, String> text, int format, Element link, int heading, int depth) {

		static final Ctx ROOT = new Ctx(null, Map.of(), 0, null, 0, 0);

		Ctx with(Element element, Map<String, String> css) {
			String tag = element.normalName();
			String align = this.align;
			String textAlign = css.get("text-align");
			if (textAlign != null) {
				String normal = "middle".equals(textAlign) ? "center" : textAlign;
				if (ALIGNMENTS.contains(normal)) align = normal;
				else if ("start".equals(normal)) align = "left";
			}
			Map<String, String> text = this.text;
			int format = this.format;
			for (Map.Entry<String, String> entry : css.entrySet()) {
				String property = entry.getKey();
				String value = entry.getValue();
				switch (property) {
					case "font-weight" -> format = isBold(value) ? format | LexicalNodes.BOLD
							: format & ~LexicalNodes.BOLD;
					case "font-style" -> format = value.startsWith("italic") || value.startsWith("oblique")
							? format | LexicalNodes.ITALIC : format & ~LexicalNodes.ITALIC;
					case "text-decoration", "text-decoration-line" -> {
						format &= ~(LexicalNodes.UNDERLINE | LexicalNodes.STRIKETHROUGH);
						if (value.contains("underline")) format |= LexicalNodes.UNDERLINE;
						if (value.contains("line-through")) format |= LexicalNodes.STRIKETHROUGH;
					}
					default -> {
						if (INHERITED_TEXT.contains(property)) {
							if (text == this.text) text = new HashMap<>(this.text);
							text.put(property, value);
						}
					}
				}
			}
			format |= switch (tag) {
				case "b", "strong" -> LexicalNodes.BOLD;
				case "i", "em", "cite", "dfn" -> LexicalNodes.ITALIC;
				case "u", "ins" -> LexicalNodes.UNDERLINE;
				case "s", "strike", "del" -> LexicalNodes.STRIKETHROUGH;
				case "code", "tt", "kbd", "samp" -> LexicalNodes.CODE;
				default -> 0;
			};
			Element link = this.link;
			if ("a".equals(tag) && !element.attr("href").isBlank()) link = element;
			int heading = this.heading;
			if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') {
				heading = tag.charAt(1) - '0';
			}
			return new Ctx(align, text, format, link, heading, depth + 1);
		}

		private static boolean isBold(String weight) {
			if (weight.equals("bold") || weight.equals("bolder")) return true;
			return weight.matches("[6-9]00");
		}

		/** The text style of text in this context: the listed CSS, without what the format bits say. */
		String textStyle() {
			if (text.isEmpty()) return "";
			StringBuilder source = new StringBuilder();
			for (String property : List.of("color", "font-family", "font-size", "line-height", "letter-spacing",
					"text-transform")) {
				String value = text.get(property);
				// font-size:0 is how a template swallows the gaps between columns; text set in it
				// always sets its own size, and a reader must never get invisible text.
				boolean zero = ("font-size".equals(property) || "line-height".equals(property))
						&& value != null && value.matches("0+(\\.0+)?(px|pt|em|%)?");
				if (value != null && !zero) source.append(property).append(':').append(value).append(';');
			}
			return CssStyles.text(source.toString());
		}

		/** The element's paragraph format: its alignment, "" for none. */
		String paragraphFormat() {
			return align == null ? "" : align;
		}
	}

	// --- walking --------------------------------------------------------------

	private void children(Element parent, Builder out, Ctx ctx) {
		for (Node child : parent.childNodes()) {
			if (child instanceof TextNode text) {
				out.text(text.getWholeText(), ctx);
			}
			else if (child instanceof Element element) {
				element(element, out, ctx);
			}
		}
	}

	private void element(Element element, Builder out, Ctx ctx) {
		if (ctx.depth() > MAX_DEPTH) {
			out.text(element.text(), ctx);
			return;
		}
		String tag = element.normalName();
		Map<String, String> css = declarations(element);
		Ctx inner = ctx.with(element, css);
		switch (tag) {
			case "br" -> out.lineBreak(ctx);
			case "img" -> out.inline(image(element, css), ctx);
			case "hr" -> {
				out.flush();
				out.blocks.add(nodes.simple("horizontalrule"));
			}
			case "table" -> {
				out.flush();
				table(element, css, out, ctx, inner);
			}
			case "ul", "ol" -> {
				out.flush();
				out.blocks.add(list(element, inner, 0));
			}
			case "pre" -> {
				out.flush();
				code(element, out);
			}
			case "h1", "h2", "h3", "h4", "h5", "h6" -> {
				out.flush();
				block(element, out, inner);
			}
			default -> {
				if (BLOCKS.contains(tag)) block(element, out, inner);
				else children(element, out, inner);
			}
		}
	}

	/**
	 * A block element: its content as blocks of their own, carrying its block style. A block that
	 * holds nothing but a link and has a background is a button; one whose children are columns
	 * side by side is a row of columns; an empty one drawing a border is a rule.
	 */
	private void block(Element element, Builder out, Ctx ctx) {
		out.flush();
		Map<String, String> css = declarations(element);
		String style = merge(blockStyle(css, true, centered(element, css, ctx)), columnShare(element));
		List<Element> columns = columnsIn(element);
		if (columns.size() >= 2) {
			out.addAll(styled(contentOf(element, ctx), style));
			return;
		}
		if (!hasContent(element) && (css.containsKey("border-top") || css.containsKey("border-bottom"))) {
			ObjectNode rule = nodes.simple("horizontalrule");
			LexicalNodes.setBlockStyle(rule, CssStyles.block("border-top:" + css.getOrDefault("border-top",
					css.get("border-bottom"))));
			out.blocks.add(rule);
			return;
		}
		List<ObjectNode> content = contentOf(element, ctx);
		ObjectNode button = button(element, css, content, ctx);
		if (button != null) {
			out.blocks.add(button);
			return;
		}
		out.addAll(styled(content, style));
	}

	/** The blocks inside [element]: a row of columns when its children sit side by side. */
	private List<ObjectNode> contentOf(Element element, Ctx ctx) {
		List<Element> columns = columnsIn(element);
		if (columns.size() >= 2) return new ArrayList<>(List.of(columnRow(columns, ctx)));
		Builder content = new Builder();
		children(element, content, ctx);
		content.flush();
		return content.blocks;
	}

	/**
	 * The children of [element] that sit side by side as columns: MJML's
	 * {@code mj-column-per-NN}/{@code mj-column-px-NNN} and inline blocks given a width. Text
	 * between them is the template's whitespace; anything else means they are no row.
	 */
	private static List<Element> columnsIn(Element element) {
		List<Element> columns = new ArrayList<>();
		for (Node child : element.childNodes()) {
			if (child instanceof TextNode text) {
				if (!blank(text.getWholeText())) return List.of();
			}
			else if (child instanceof Element candidate) {
				if (columnTrack(candidate) == null) return List.of();
				if (hasContent(candidate)) columns.add(candidate);
			}
		}
		return columns;
	}

	/** The track of a column element, or null when it is none. */
	private static String columnTrack(Element element) {
		for (String name : element.classNames()) {
			Matcher matcher = MJ_COLUMN.matcher(name.toLowerCase(Locale.ROOT));
			if (matcher.matches()) {
				String number = matcher.group(3) == null ? matcher.group(2) : matcher.group(2) + "." + matcher.group(3);
				return number + ("per".equals(matcher.group(1)) ? "%" : "px");
			}
		}
		Map<String, String> css = inline(element);
		if (!"inline-block".equals(css.get("display"))) return null;
		String width = css.getOrDefault("width", element.attr("width"));
		Matcher px = PX.matcher(width);
		if (!width.isBlank() && px.matches()) return px.group(1) + "px";
		Matcher percent = PERCENT.matcher(width);
		return percent.matches() ? percent.group(1) + "%" : "1fr";
	}

	/** Columns side by side: one container, each column an item. */
	private ObjectNode columnRow(List<Element> columns, Ctx ctx) {
		StringBuilder template = new StringBuilder();
		ArrayNode items = nodes.array();
		for (Element column : columns) {
			if (!template.isEmpty()) template.append(' ');
			template.append(columnTrack(column));
			Map<String, String> css = declarations(column);
			ArrayNode blocks = nodes.array();
			contentOf(column, ctx.with(column, css)).forEach(blocks::add);
			if (blocks.isEmpty()) blocks.add(nodes.paragraph(nodes.array(), 0));
			ObjectNode item = nodes.layoutItem(blocks);
			LexicalNodes.setBlockStyle(item, blockStyle(css, false, false));
			items.add(item);
		}
		return nodes.layoutContainer(template.toString(), items);
	}

	/**
	 * A column alone in its row keeps its share of the row: MJML's {@code mj-column-per-50} holding
	 * a row of icons is half as wide as the section, centred.
	 */
	private static String columnShare(Element element) {
		String track = columnTrack(element);
		if (track == null || track.equals("100%") || track.equals("1fr")) return "";
		return CssStyles.block("max-width:" + track + ";margin-left:auto;margin-right:auto");
	}

	/** Whether a fixed-width [element] sits centred: by its own margins or its container's alignment. */
	private static boolean centered(Element element, Map<String, String> css, Ctx ctx) {
		String width = css.get("width");
		String maxWidth = css.get("max-width");
		boolean fixed = width != null && PX.matcher(width).matches()
				|| maxWidth != null && PX.matcher(maxWidth).matches()
				|| PX.matcher(element.attr("width")).matches() && !element.attr("width").isBlank();
		if (!fixed) return false;
		String margin = css.getOrDefault("margin", "");
		return "center".equalsIgnoreCase(element.attr("align")) || margin.endsWith(" auto")
				|| "auto".equals(css.get("margin-left")) && "auto".equals(css.get("margin-right"))
				|| "center".equals(ctx.align());
	}

	/**
	 * [blocks] carrying [style]: on the one block itself when there is one, else in a one-column
	 * container. A container already carrying a style takes the outer one under its own, so
	 * nested single-cell wrappers collapse into one.
	 */
	private List<ObjectNode> styled(List<ObjectNode> blocks, String style) {
		if (style.isEmpty() || blocks.isEmpty()) return blocks;
		if (blocks.size() == 1 && mergeable(style, LexicalNodes.blockStyle(blocks.getFirst()))) {
			ObjectNode only = blocks.getFirst();
			LexicalNodes.setBlockStyle(only, merge(style, LexicalNodes.blockStyle(only)));
			return blocks;
		}
		ArrayNode content = nodes.array();
		blocks.forEach(content::add);
		ArrayNode items = nodes.array();
		items.add(nodes.layoutItem(content));
		ObjectNode container = nodes.layoutContainer("1fr", items);
		LexicalNodes.setBlockStyle(container, style);
		return List.of(container);
	}

	/**
	 * The button a block with a background makes of the single link it holds: one centred
	 * paragraph with the background, padding and radius of the block and of the link.
	 */
	private ObjectNode button(Element element, Map<String, String> css, List<ObjectNode> blocks, Ctx ctx) {
		if (blocks.size() != 1 || !"paragraph".equals(blocks.getFirst().path("type").asText())) return null;
		JsonNode children = blocks.getFirst().path("children");
		if (children.size() != 1 || !"link".equals(children.get(0).path("type").asText())) return null;
		Element anchor = element.selectFirst("a[href]");
		if (anchor == null) return null;
		Map<String, String> own = declarations(anchor);
		boolean background = css.containsKey("background-color") || own.containsKey("background-color")
				|| css.containsKey("background") || own.containsKey("background");
		if (!background) return null;
		ObjectNode paragraph = blocks.getFirst();
		String format = ctx.align() == null ? "center" : ctx.align();
		String style = merge(blockStyle(css, false, false), blockStyle(own, false, false));
		LexicalNodes.setBlockStyle(paragraph, merge(style, hug(anchor, own, style, format)));
		paragraph.put("format", format);
		return paragraph;
	}

	/**
	 * The width that makes a button hug its label as the mail draws it, estimated from the label
	 * and its font size: a paragraph is as wide as its column otherwise, a red bar instead of a
	 * button. Centred or right-aligned like the label.
	 */
	private static String hug(Element anchor, Map<String, String> own, String style, String format) {
		int fontSize = pixels(null, own.get("font-size"));
		if (fontSize <= 0) fontSize = 16;
		double[] padding = sides(parse(style));
		long width = Math.round(anchor.text().strip().length() * fontSize * 0.56 + padding[1] + padding[3] + 4);
		String margins = switch (format) {
			case "center" -> ";margin-left:auto;margin-right:auto";
			case "right" -> ";margin-left:auto";
			default -> "";
		};
		return CssStyles.block("max-width:" + width + "px" + margins);
	}

	// --- tables ---------------------------------------------------------------

	private void table(Element table, Map<String, String> css, Builder out, Ctx outer, Ctx ctx) {
		if (!css.containsKey("width") && PX.matcher(table.attr("width")).matches() && !table.attr("width").isBlank()) {
			css = new LinkedHashMap<>(css);
			css.put("width", table.attr("width").strip() + "px");
		}
		else if (!css.containsKey("width") && "center".equalsIgnoreCase(table.attr("align"))) {
			// A centred table without a width is as wide as its cells; when they all name their
			// width in pixels, that is its width.
			int width = pixelWidthOfCells(table);
			if (width > 0) {
				css = new LinkedHashMap<>(css);
				css.put("width", width + "px");
			}
		}
		List<Element> rows = rowsOf(table);
		if (rows.isEmpty()) {
			block(table, out, ctx);
			return;
		}
		if (isDataTable(table, rows)) {
			out.blocks.add(dataTable(rows, ctx));
			return;
		}
		int cellPadding = pixels(table.attr("cellpadding"), null);
		List<ObjectNode> blocks = new ArrayList<>();
		for (Element row : rows) {
			Map<String, String> rowCss = declarations(row);
			Ctx rowCtx = ctx.with(row, rowCss);
			List<Element> cells = row.children().stream()
					.filter(cell -> cell.normalName().equals("td") || cell.normalName().equals("th"))
					.filter(HtmlToLexical::hasContent)
					.toList();
			if (cells.size() >= 2) {
				blocks.add(columns(cells, rowCss, rowCtx, cellPadding));
			}
			else if (cells.size() == 1) {
				blocks.addAll(cell(cells.getFirst(), rowCss, rowCtx, cellPadding, true));
			}
		}
		boolean centered = centered(table, css, outer);
		out.addAll(styled(blocks, blockStyle(css, true, centered)));
	}

	/** The summed pixel widths of the first row's cells; 0 when one of them names none. */
	private static int pixelWidthOfCells(Element table) {
		List<Element> rows = rowsOf(table);
		if (rows.isEmpty()) return 0;
		int sum = 0;
		for (Element cell : rows.getFirst().children()) {
			String track = track(cell);
			if (!track.endsWith("px")) return 0;
			sum += Integer.parseInt(track.substring(0, track.length() - 2));
		}
		return sum;
	}

	/** The rows of [table] itself, not of the tables nested in it. */
	private static List<Element> rowsOf(Element table) {
		List<Element> rows = new ArrayList<>();
		for (Element child : table.children()) {
			switch (child.normalName()) {
				case "tr" -> rows.add(child);
				case "tbody", "thead", "tfoot" -> child.children().stream()
						.filter(row -> row.normalName().equals("tr")).forEach(rows::add);
				default -> {
					// A caption or a colgroup: no row.
				}
			}
		}
		return rows;
	}

	/** Whether a cell holds anything to see: words, an image, a rule. */
	private static boolean hasContent(Element cell) {
		return !blank(cell.text()) || cell.selectFirst("img, hr") != null;
	}

	/**
	 * A table of data rather than of layout: a header row of {@code th}, or at least three columns
	 * of short text in at least two rows, drawn with borders.
	 */
	private static boolean isDataTable(Element table, List<Element> rows) {
		Elements first = rows.getFirst().children();
		long headers = first.stream().filter(cell -> cell.normalName().equals("th")).count();
		if (headers >= 2 && headers == first.size()) return true;
		if (rows.size() < 2) return false;
		boolean bordered = pixels(table.attr("border"), null) > 0
				|| table.attr("style").contains("border") || table.selectFirst("td[style*=border]") != null;
		if (!bordered) return false;
		int columns = 0;
		for (Element row : rows) {
			List<Element> cells = row.children().stream()
					.filter(cell -> cell.normalName().equals("td") || cell.normalName().equals("th")).toList();
			columns = Math.max(columns, cells.size());
			for (Element cell : cells) {
				if (cell.text().length() > DATA_CELL_CHARS || cell.selectFirst("table, img") != null) return false;
			}
		}
		return columns >= 3;
	}

	private ObjectNode dataTable(List<Element> rows, Ctx ctx) {
		ArrayNode tableRows = nodes.array();
		for (Element row : rows) {
			ArrayNode cells = nodes.array();
			for (Element cell : row.children()) {
				String tag = cell.normalName();
				if (!tag.equals("td") && !tag.equals("th")) continue;
				Builder content = new Builder();
				children(cell, content, ctx.with(cell, declarations(cell)));
				content.flush();
				ArrayNode blocks = nodes.array();
				content.blocks.forEach(blocks::add);
				if (blocks.isEmpty()) blocks.add(nodes.paragraph(nodes.array(), 0));
				int span = Math.max(1, pixels(cell.attr("colspan"), null));
				cells.add(nodes.tableCell(blocks, tag.equals("th"), span));
			}
			if (!cells.isEmpty()) tableRows.add(nodes.tableRow(cells));
		}
		return nodes.table(tableRows);
	}

	/** A row of two or more cells: columns as wide as the cells say. */
	private ObjectNode columns(List<Element> cells, Map<String, String> rowCss, Ctx ctx, int cellPadding) {
		StringBuilder template = new StringBuilder();
		ArrayNode items = nodes.array();
		for (Element cell : cells) {
			if (!template.isEmpty()) template.append(' ');
			template.append(track(cell));
			ArrayNode content = nodes.array();
			List<ObjectNode> blocks = cell(cell, rowCss, ctx, cellPadding, false);
			blocks.forEach(content::add);
			if (content.isEmpty()) content.add(nodes.paragraph(nodes.array(), 0));
			ObjectNode item = nodes.layoutItem(content);
			LexicalNodes.setBlockStyle(item, cellStyle(cell, rowCss, cellPadding));
			items.add(item);
		}
		return nodes.layoutContainer(template.toString(), items);
	}

	/** The column track of a cell: its pixel or percent width, or a share of what is left. */
	private static String track(Element cell) {
		String width = inline(cell).getOrDefault("width", cell.attr("width"));
		Matcher px = PX.matcher(width);
		if (!width.isBlank() && px.matches() && Integer.parseInt(px.group(1)) > 0) return px.group(1) + "px";
		Matcher percent = PERCENT.matcher(width);
		if (percent.matches() && Integer.parseInt(percent.group(1)) > 0) return percent.group(1) + "%";
		return "1fr";
	}

	/** The style of a cell: its own, its row's background, the table's cell padding. */
	private static String cellStyle(Element cell, Map<String, String> rowCss, int cellPadding) {
		Map<String, String> css = new LinkedHashMap<>();
		String rowBackground = rowCss.getOrDefault("background-color", rowCss.get("background"));
		if (rowBackground != null) css.put("background-color", rowBackground);
		if (cellPadding > 0) css.put("padding", cellPadding + "px");
		css.putAll(declarations(cell));
		css.remove("text-align");
		return blockStyle(css, false, false);
	}

	/**
	 * The blocks of one cell. A cell alone in its row carries its style itself ([alone]); in a
	 * row of columns the style goes on its {@code layout-item}.
	 */
	private List<ObjectNode> cell(Element cell, Map<String, String> rowCss, Ctx rowCtx, int cellPadding,
			boolean alone) {
		Map<String, String> css = declarations(cell);
		Ctx ctx = rowCtx.with(cell, css);
		List<ObjectNode> content = contentOf(cell, ctx);
		Map<String, String> withRow = new LinkedHashMap<>(css);
		String rowBackground = rowCss.getOrDefault("background-color", rowCss.get("background"));
		if (rowBackground != null) withRow.putIfAbsent("background-color", rowBackground);
		ObjectNode button = button(cell, withRow, content, ctx);
		if (button != null) return List.of(button);
		return alone ? styled(content, cellStyle(cell, rowCss, cellPadding)) : content;
	}

	// --- lists, code, images ----------------------------------------------------

	private ObjectNode list(Element list, Ctx ctx, int indent) {
		boolean ordered = list.normalName().equals("ol");
		int start = ordered ? Math.max(1, pixels(list.attr("start"), null)) : 1;
		int value = start;
		ArrayNode items = nodes.array();
		for (Element item : list.children()) {
			if (!item.normalName().equals("li")) continue;
			Ctx itemCtx = ctx.with(item, declarations(item));
			Builder content = new Builder();
			List<Element> nested = new ArrayList<>();
			for (Node child : item.childNodes()) {
				if (child instanceof Element element && (element.normalName().equals("ul")
						|| element.normalName().equals("ol"))) {
					nested.add(element);
				}
				else if (child instanceof TextNode text) {
					content.text(text.getWholeText(), itemCtx);
				}
				else if (child instanceof Element element) {
					element(element, content, itemCtx);
				}
			}
			content.flush();
			ObjectNode listItem = nodes.element("listitem", inlineOf(content.blocks), indent);
			listItem.put("value", value++);
			items.add(listItem);
			for (Element sub : nested) {
				if (indent >= 8) break;
				ArrayNode holder = nodes.array();
				holder.add(list(sub, itemCtx, indent + 1));
				ObjectNode holderItem = nodes.element("listitem", holder, indent);
				holderItem.put("value", value++);
				items.add(holderItem);
			}
		}
		ObjectNode element = nodes.element("list", items, 0);
		element.put("listType", ordered ? "number" : "bullet");
		element.put("start", start);
		element.put("tag", ordered ? "ol" : "ul");
		return element;
	}

	/** The inline content of [blocks] in one run, a line break between two blocks. */
	private ArrayNode inlineOf(List<ObjectNode> blocks) {
		ArrayNode out = nodes.array();
		for (ObjectNode block : blocks) {
			if (!out.isEmpty()) out.add(nodes.simple("linebreak"));
			JsonNode children = block.path("children");
			if (block.path("type").asText().equals("paragraph") || block.path("type").asText().equals("heading")) {
				children.forEach(out::add);
			}
			else {
				ArrayNode one = nodes.array();
				one.add(block);
				out.add(nodes.text(LexicalJson.plainText(nodes.document(one)), 0));
			}
		}
		return out;
	}

	private void code(Element pre, Builder out) {
		String text = pre.wholeText();
		if (text.startsWith("\n")) text = text.substring(1);
		if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
		if (blank(text)) return;
		ArrayNode children = nodes.array();
		children.add(nodes.text(text, 0));
		ObjectNode code = nodes.element("code", children, 0);
		code.put("language", "");
		out.blocks.add(code);
	}

	private ObjectNode image(Element image, Map<String, String> css) {
		int width = pixels(image.attr("width"), css.get("width"));
		int height = pixels(image.attr("height"), css.get("height"));
		int maxWidth = pixels(null, css.get("max-width"));
		if (maxWidth <= 0) maxWidth = width > 0 ? width : LexicalNodes.IMAGE_MAX_WIDTH;
		return nodes.image(image.attr("src").strip(), image.attr("alt").strip(), Math.max(0, width),
				Math.max(0, height), maxWidth);
	}

	// --- output ---------------------------------------------------------------

	/** The blocks of one run of content and the paragraph being filled. */
	private final class Builder {

		final List<ObjectNode> blocks = new ArrayList<>();
		private Paragraph paragraph;

		void addAll(List<ObjectNode> more) {
			blocks.addAll(more);
		}

		void text(String raw, Ctx ctx) {
			String text = WHITESPACE.matcher(raw).replaceAll(" ");
			if (text.isEmpty()) return;
			if (paragraph == null && text.isBlank()) return;
			open(ctx).text(text, ctx);
		}

		void lineBreak(Ctx ctx) {
			open(ctx).node(nodes.simple("linebreak"), ctx, true);
		}

		void inline(ObjectNode node, Ctx ctx) {
			open(ctx).node(node, ctx, false);
		}

		private Paragraph open(Ctx ctx) {
			if (paragraph == null) paragraph = new Paragraph(ctx.paragraphFormat(), ctx.heading());
			return paragraph;
		}

		void flush() {
			if (paragraph == null) return;
			ObjectNode block = paragraph.finish();
			paragraph = null;
			if (block != null) blocks.add(block);
		}
	}

	/** One paragraph or heading being filled, merging text runs and grouping a link's content. */
	private final class Paragraph {

		private final String format;
		private final int heading;
		private final ArrayNode children = nodes.array();
		private ObjectNode link;
		private Element linkOwner;
		/** Whether the content so far ends in a space or a line break, or there is none. */
		private boolean atBreak = true;

		Paragraph(String format, int heading) {
			this.format = format;
			this.heading = heading;
		}

		void text(String text, Ctx ctx) {
			String value = atBreak && text.startsWith(" ") ? text.substring(1) : text;
			if (value.isEmpty()) return;
			ArrayNode target = target(ctx);
			JsonNode last = target.isEmpty() ? null : target.get(target.size() - 1);
			String style = ctx.textStyle();
			if (last instanceof ObjectNode previous && "text".equals(previous.path("type").asText())
					&& previous.path("format").asInt() == ctx.format()
					&& previous.path("style").asText().equals(style)) {
				previous.put("text", previous.path("text").asText() + value);
			}
			else {
				target.add(nodes.text(value, ctx.format(), style));
			}
			atBreak = value.endsWith(" ");
		}

		void node(ObjectNode node, Ctx ctx, boolean lineBreak) {
			if (lineBreak) trimEnd(children, false);
			target(ctx).add(node);
			atBreak = lineBreak;
		}

		/** Where content in [ctx] goes: the paragraph, or the link it belongs to. */
		private ArrayNode target(Ctx ctx) {
			if (ctx.link() == null) {
				link = null;
				linkOwner = null;
				return children;
			}
			if (ctx.link() != linkOwner) {
				link = nodes.link(ctx.link().attr("href").strip(), nodes.array());
				linkOwner = ctx.link();
				children.add(link);
			}
			return (ArrayNode) link.get("children");
		}

		ObjectNode finish() {
			trimEnd(children, true);
			removeEmptyLinks(children);
			if (!hasContent(children)) return null;
			ObjectNode block = heading > 0 ? nodes.heading(heading, children) : nodes.paragraph(children, 0);
			block.put("format", format);
			return block;
		}
	}

	/** Strips trailing spaces, and at the very end trailing line breaks too. */
	private static void trimEnd(ArrayNode children, boolean breaks) {
		while (!children.isEmpty()) {
			JsonNode last = children.get(children.size() - 1);
			String type = last.path("type").asText();
			if ("text".equals(type)) {
				String stripped = last.path("text").asText().stripTrailing();
				if (stripped.isEmpty()) {
					children.remove(children.size() - 1);
					continue;
				}
				((ObjectNode) last).put("text", stripped);
				return;
			}
			if ("linebreak".equals(type) && breaks) {
				children.remove(children.size() - 1);
				continue;
			}
			if ("link".equals(type) && last.get("children") instanceof ArrayNode inner) {
				trimEnd(inner, breaks);
				if (inner.isEmpty()) {
					children.remove(children.size() - 1);
					continue;
				}
			}
			return;
		}
	}

	private static void removeEmptyLinks(ArrayNode children) {
		for (int i = children.size() - 1; i >= 0; i--) {
			JsonNode child = children.get(i);
			if ("link".equals(child.path("type").asText()) && child.path("children").isEmpty()) children.remove(i);
		}
	}

	/** Whether inline content shows anything: words or an image. */
	private static boolean hasContent(JsonNode children) {
		for (JsonNode child : children) {
			String type = child.path("type").asText();
			if ("image".equals(type)) return true;
			if ("text".equals(type) && !child.path("text").asText().isBlank()) return true;
			if ("link".equals(type) && hasContent(child.path("children"))) return true;
		}
		return false;
	}
}
