package com.ahmadre.hinata.issue.export;

import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;

import java.awt.Color;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The typeface a PDF is drawn with, chosen so the reader's own script actually
 * appears on the page.
 *
 * <p>PDF's fourteen built-in fonts — Helvetica among them — are WinAnsi
 * encoded: they hold Latin-1 and nothing else. Ask one of them for
 * {@code डेटा निर्यात} or {@code 数据导出} and it does not fail, it draws
 * nothing. The export still arrives, correctly laid out, with the text missing.
 * That is the worst way for this to break, and it breaks worst on the one
 * document somebody is legally entitled to: the GDPR Art. 15 report.
 *
 * <p>So a script outside Latin-1 is drawn with a real Unicode font, embedded
 * into the file ({@code IDENTITY_H}) so it renders on a machine that has never
 * heard of it. The fonts come from the operating system — the container
 * installs Noto, which is what {@code CANDIDATES} looks for — rather than from
 * this repository, because a full CJK face is around ten megabytes and does not
 * belong in a source tree.
 *
 * <p>When nothing suitable is installed, Latin text still renders exactly as it
 * always has. Only the scripts that were never going to appear are affected,
 * and {@link #missingFontFor} says which, so the gap is reportable instead of
 * silent.
 */
public final class ExportFonts {

	private ExportFonts() {
	}

	/**
	 * Where a Unicode face for each script is looked for, most specific first.
	 * Alpine's {@code font-noto-*} packages and the usual Debian/macOS paths.
	 */
	private static final Map<Script, List<String>> CANDIDATES = Map.of(
			Script.DEVANAGARI, List.of(
					"/usr/share/fonts/noto/NotoSansDevanagari-Regular.ttf",
					"/usr/share/fonts/truetype/noto/NotoSansDevanagari-Regular.ttf",
					"/System/Library/Fonts/Supplemental/NotoSansDevanagari-Regular.ttf",
					"/System/Library/Fonts/Supplemental/DevanagariMT.ttc,0"),
			Script.CJK, List.of(
					"/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc,0",
					"/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0",
					"/System/Library/Fonts/Supplemental/NotoSansSC-Regular.otf",
					"/System/Library/Fonts/PingFang.ttc,0"),
			Script.LATIN, List.of());

	/** The writing systems this platform ships a language for. */
	public enum Script {
		LATIN, DEVANAGARI, CJK
	}

	/** Resolved once per script — reading and parsing a CJK face is not cheap. */
	private static final Map<Script, BaseFont> RESOLVED = new ConcurrentHashMap<>();

	/** Which script [text] needs, judged by the first character outside Latin-1. */
	public static Script scriptOf(String text) {
		if (text == null) {
			return Script.LATIN;
		}
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c < 0x0100) {
				continue;
			}
			if (c >= 0x0900 && c <= 0x097F) {
				return Script.DEVANAGARI;
			}
			if (c >= 0x2E80 && c <= 0x9FFF || c >= 0xF900 && c <= 0xFAFF) {
				return Script.CJK;
			}
		}
		return Script.LATIN;
	}

	/**
	 * A font of the given size and style that can draw [text], or the base-14
	 * face when the text is Latin (or when no Unicode font is installed).
	 */
	public static Font forText(String text, float size, int style, Color color) {
		BaseFont base = baseFontFor(scriptOf(text));
		return base == null ? new Font(Font.HELVETICA, size, style, color)
				: new Font(base, size, style, color);
	}

	/** Plain body text, for callers that only need a default. */
	public static Font body() {
		return forText("", 10, Font.NORMAL, Color.BLACK);
	}

	/**
	 * The script [text] needs when this machine cannot draw it, else null.
	 * Lets a caller log or report the gap rather than shipping a blank page.
	 */
	public static Script missingFontFor(String text) {
		Script script = scriptOf(text);
		return script != Script.LATIN && baseFontFor(script) == null ? script : null;
	}

	private static BaseFont baseFontFor(Script script) {
		if (script == Script.LATIN) {
			return null; // the built-in face is right, and needs no embedding
		}
		if (script == Script.DEVANAGARI) {
			// Not a missing font — a missing shaper. Devanagari is written by
			// reordering matras and fusing consonants into conjuncts, which is
			// OpenType GSUB/GPOS work; OpenPDF maps code points to glyphs one to
			// one and does none of it. Handing it Devanagari produces a page with
			// the text silently absent (measured: zero extractable characters),
			// so we report it as unrenderable and let the caller fall back to a
			// document somebody can actually read. CJK needs no shaping, which is
			// why it works.
			return null;
		}
		return RESOLVED.computeIfAbsent(script, ExportFonts::load);
	}

	/**
	 * [wanted], or English when a PDF cannot be drawn in that language's script.
	 *
	 * <p>A reader is better served by a complete document in a language they may
	 * have to work at than by a correctly-labelled one with every label blank.
	 */
	public static java.util.Locale renderableLocale(java.util.Locale wanted, String sample) {
		return missingFontFor(sample) == null ? wanted : java.util.Locale.ENGLISH;
	}

	private static BaseFont load(Script script) {
		for (String path : CANDIDATES.getOrDefault(script, List.of())) {
			String file = path.contains(",") ? path.substring(0, path.indexOf(',')) : path;
			if (!new File(file).canRead()) {
				continue;
			}
			try {
				return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
			}
			catch (Exception unusable) {
				// A face we cannot parse is the same as one we do not have.
			}
		}
		return null;
	}
}
