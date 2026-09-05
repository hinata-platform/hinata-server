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
	 * Where fonts live, across the platforms this runs on: the container's Alpine
	 * packages, the usual Linux layouts, and a developer's macOS.
	 *
	 * <p>Searched rather than pinned to a filename, because the same package
	 * lands in a different place on every distribution — and a path that is
	 * wrong fails the way this whole class exists to prevent: silently.
	 */
	private static final List<String> FONT_DIRECTORIES = List.of(
			"/usr/share/fonts", "/usr/local/share/fonts",
			"/System/Library/Fonts", "/System/Library/Fonts/Supplemental",
			"/Library/Fonts");

	/**
	 * A character each script is judged by. Finding a file whose name looks
	 * right is not evidence it can draw anything — the face is asked directly,
	 * because the alternative is discovering it from a blank page.
	 */
	private static final Map<Script, Character> PROBE = Map.of(
			Script.DEVANAGARI, 'क',
			Script.ARABIC, 'ب',
			Script.CJK, '数',
			Script.CYRILLIC, 'Д',
			Script.LATIN, 'A');

	/** Filename fragments identifying a face that covers each script. */
	private static final Map<Script, List<String>> FACE_NAMES = Map.of(
			Script.DEVANAGARI, List.of("NotoSansDevanagari", "NotoSerifDevanagari", "Devanagari"),
			Script.ARABIC, List.of("NotoSansArabic", "NotoNaskhArabic", "Arabic"),
			// Cyrillic lives in the same Noto Sans that already covers Latin, so the
			// generic faces come first here — a machine with any Noto Sans can draw
			// Russian without a script-specific download.
			Script.CYRILLIC, List.of("NotoSans-Regular", "NotoSans", "DejaVuSans", "Arial"),
			Script.CJK, List.of("NotoSansCJK", "NotoSansSC", "NotoSerifCJK", "SourceHanSans",
					"PingFang", "Hiragino Sans GB", "wqy-zenhei", "DroidSansFallback"),
			Script.LATIN, List.of());

	/**
	 * The writing systems this platform ships a language for.
	 *
	 * <p>Two of them cannot be typeset here at all — see {@link #baseFontFor}.
	 * They are named anyway, because "we know what this needs and cannot do it"
	 * is what lets the caller fall back deliberately instead of drawing nothing.
	 */
	public enum Script {
		LATIN, DEVANAGARI, ARABIC, CYRILLIC, CJK
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
			if (c >= 0x0400 && c <= 0x04FF) {
				return Script.CYRILLIC;
			}
			if (c >= 0x0600 && c <= 0x06FF || c >= 0x0750 && c <= 0x077F
					|| c >= 0xFB50 && c <= 0xFDFF || c >= 0xFE70 && c <= 0xFEFF) {
				return Script.ARABIC;
			}
			if (c >= 0x0900 && c <= 0x097F) {
				return Script.DEVANAGARI;
			}
			// Japanese kana and Han both sit in this range, so one branch serves both.
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
		if (script == Script.DEVANAGARI || script == Script.ARABIC) {
			// Not a missing font — a missing shaper. Devanagari is written by
			// reordering matras and fusing consonants into conjuncts; Arabic joins
			// every letter to its neighbours and picks one of four forms per letter
			// by position, and is then laid out right to left. Both are OpenType
			// GSUB/GPOS work; OpenPDF maps code points to glyphs one to one and does
			// none of it. Handing it either produces a page with the text silently
			// absent, or one of disconnected letters in the wrong order — so we
			// report it as unrenderable and let the caller fall back to a document
			// somebody can actually read. CJK and Cyrillic need no shaping, which is
			// why they work.
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
		for (String name : FACE_NAMES.getOrDefault(script, List.of())) {
			for (File file : findFaces(name)) {
				// A .ttc holds several faces; ",0" asks for the first.
				String path = file.getName().toLowerCase().endsWith(".ttc")
						? file.getPath() + ",0"
						: file.getPath();
				try {
					BaseFont font = BaseFont.createFont(path, BaseFont.IDENTITY_H,
							BaseFont.EMBEDDED);
					if (canActuallyDraw(font, PROBE.getOrDefault(script, 'A'))) {
						return font;
					}
					// Named for the script, but the page comes out empty: a
					// fallback face, a .ttc whose first entry is something else,
					// or a system font that refuses to embed.
				}
				catch (Exception unusable) {
					// A face we cannot parse is the same as one we do not have.
				}
			}
		}
		return null;
	}

	/**
	 * Whether [font] puts [probe] on a page — established by making one.
	 *
	 * <p>Asking the font is not enough. {@code charExists} answers from the
	 * cmap, and a face can pass that and still contribute nothing: a macOS
	 * system font that declines to embed, or a .ttc whose first entry is a
	 * different script. Both were observed here, and both fail the same
	 * invisible way. So the question is settled by writing a one-character
	 * document and reading it back — once per script, on first use.
	 */
	private static boolean canActuallyDraw(BaseFont font, char probe) {
		try {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			com.lowagie.text.Document doc = new com.lowagie.text.Document();
			com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
			doc.open();
			doc.add(new com.lowagie.text.Paragraph(String.valueOf(probe), new Font(font, 12)));
			doc.close();
			com.lowagie.text.pdf.PdfReader reader =
					new com.lowagie.text.pdf.PdfReader(out.toByteArray());
			try {
				String back = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader)
						.getTextFromPage(1);
				return back != null && back.indexOf(probe) >= 0;
			}
			finally {
				reader.close();
			}
		}
		catch (Exception cannot) {
			return false;
		}
	}

	/** Readable font files whose name contains [fragment]. */
	private static List<File> findFaces(String fragment) {
		List<File> found = new java.util.ArrayList<>();
		for (String directory : FONT_DIRECTORIES) {
			File dir = new File(directory);
			if (!dir.isDirectory()) {
				continue;
			}
			try (java.util.stream.Stream<java.nio.file.Path> tree =
					java.nio.file.Files.walk(dir.toPath(), 3)) {
				tree.map(java.nio.file.Path::toFile)
						.filter(File::isFile)
						.filter(f -> f.getName().contains(fragment))
						.filter(f -> {
							String n = f.getName().toLowerCase();
							return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc");
						})
						.filter(File::canRead)
						.forEach(found::add);
			}
			catch (Exception unreadable) {
				// A directory we cannot walk simply holds no fonts for us.
			}
		}
		return found;
	}
}
