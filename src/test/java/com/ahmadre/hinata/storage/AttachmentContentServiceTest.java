package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.issue.Issue;
import org.junit.jupiter.api.Test;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bounds are the feature. Every case here is a way an attachment could
 * otherwise end up unbounded inside a model's context window — an original
 * photograph, a whole PDF, a half-delivered archive — and the assertion is that
 * it does not.
 */
class AttachmentContentServiceTest {

	private static final AttachmentContentService.Limits LIMITS =
			new AttachmentContentService.Limits(5L * 1024 * 1024, 1600, 200);

	private final StorageService storage = mock(StorageService.class);
	private final AttachmentContentService service = new AttachmentContentService(storage);

	// --- helpers -----------------------------------------------------------

	private static byte[] png(int width, int height, boolean transparent) throws Exception {
		BufferedImage image = new BufferedImage(width, height,
				transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		if (!transparent) {
			g.setColor(new Color(30, 120, 200));
			g.fillRect(0, 0, width, height);
		}
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private Issue.Attachment attachment(String contentType, long size) {
		return Issue.Attachment.builder()
				.id("a1").fileName("file").contentType(contentType).size(size)
				.objectKey("secret-object-key").build();
	}

	private void stored(byte[] data, String contentType) {
		when(storage.getObject(anyString()))
				.thenReturn(Optional.of(new StorageService.StoredObject(data, contentType)));
	}

	// --- images ------------------------------------------------------------

	@Test
	void anImageIsDownscaledToTheRequestedWidth() throws Exception {
		byte[] original = png(3200, 1600, false);
		stored(original, "image/png");

		AttachmentContent content = service.render(attachment("image/png", original.length), LIMITS);

		assertThat(content).isInstanceOf(AttachmentContent.Image.class);
		AttachmentContent.Image image = (AttachmentContent.Image) content;
		assertThat(image.width()).isEqualTo(1600);
		assertThat(image.height()).isEqualTo(800);
		assertThat(image.sourceWidth()).isEqualTo(3200);
		// The original is never what leaves here — not its bytes, not its size.
		assertThat(image.bytes()).isNotEqualTo(original);
		assertThat(ImageOps.read(image.bytes()).getWidth()).isEqualTo(1600);
	}

	@Test
	void anImageAlreadyWithinTheBoundIsStillReEncoded() throws Exception {
		// Re-encoding is what strips EXIF (and a camera's GPS coordinates with
		// it), so "small enough" must not become "passed through untouched".
		byte[] original = png(400, 300, false);
		stored(original, "image/png");

		AttachmentContent.Image image = (AttachmentContent.Image)
				service.render(attachment("image/png", original.length), LIMITS);

		assertThat(image.width()).isEqualTo(400);
		assertThat(image.bytes()).isNotEqualTo(original);
		assertThat(image.contentType()).isEqualTo("image/jpeg");
	}

	@Test
	void aRequestedWidthAboveTheServerCeilingIsClamped() throws Exception {
		byte[] original = png(4000, 2000, false);
		stored(original, "image/png");

		AttachmentContent.Image image = (AttachmentContent.Image) service.render(
				attachment("image/png", original.length),
				new AttachmentContentService.Limits(5L * 1024 * 1024, 99_999, 200));

		assertThat(image.width()).isEqualTo(AttachmentContentService.MAX_IMAGE_WIDTH);
	}

	@Test
	void aRequestedWidthBelowTheFloorIsClamped() throws Exception {
		byte[] original = png(4000, 2000, false);
		stored(original, "image/png");

		AttachmentContent.Image image = (AttachmentContent.Image) service.render(
				attachment("image/png", original.length),
				new AttachmentContentService.Limits(5L * 1024 * 1024, 1, 200));

		assertThat(image.width()).isEqualTo(AttachmentContentService.MIN_IMAGE_WIDTH);
	}

	@Test
	void transparencySurvivesAsPng() throws Exception {
		byte[] original = png(800, 600, true);
		stored(original, "image/png");

		AttachmentContent.Image image = (AttachmentContent.Image)
				service.render(attachment("image/png", original.length), LIMITS);

		assertThat(image.contentType()).isEqualTo("image/png");
	}

	@Test
	void anUndecodableImageReportsWhyInsteadOfShippingTheOriginal() {
		byte[] notAnImage = "RIFF....WEBPVP8 nonsense".getBytes(StandardCharsets.UTF_8);
		stored(notAnImage, "image/webp");

		AttachmentContent content = service.render(attachment("image/webp", notAnImage.length), LIMITS);

		assertThat(content).isEqualTo(
				new AttachmentContent.Unavailable(AttachmentContent.Reason.UNREADABLE));
	}

	// --- text --------------------------------------------------------------

	@Test
	void textIsReturnedWholeWhenItFits() {
		byte[] data = "hello, ticket".getBytes(StandardCharsets.UTF_8);
		stored(data, "text/plain");

		AttachmentContent.Text text = (AttachmentContent.Text)
				service.render(attachment("text/plain", data.length), LIMITS);

		assertThat(text.text()).isEqualTo("hello, ticket");
		assertThat(text.truncated()).isFalse();
	}

	@Test
	void longTextIsCutAtTheBudgetAndSaysSo() {
		byte[] data = "x".repeat(1000).getBytes(StandardCharsets.UTF_8);
		stored(data, "text/plain");

		AttachmentContent.Text text = (AttachmentContent.Text)
				service.render(attachment("text/plain", data.length), LIMITS);

		assertThat(text.text()).hasSize(200);
		assertThat(text.truncated()).isTrue();
	}

	@Test
	void aContentTypeWithParametersStillMatchesTheAllowList() {
		byte[] data = "a,b,c".getBytes(StandardCharsets.UTF_8);
		stored(data, "text/csv");

		AttachmentContent content = service.render(
				attachment("TEXT/CSV; charset=utf-8", data.length), LIMITS);

		assertThat(content).isInstanceOf(AttachmentContent.Text.class);
	}

	// --- pdf ---------------------------------------------------------------

	private static byte[] pdf(int pages, String line) throws Exception {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (int page = 1; page <= pages; page++) {
				PDPage sheet = new PDPage();
				document.addPage(sheet);
				try (PDPageContentStream content = new PDPageContentStream(document, sheet)) {
					content.beginText();
					content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					content.newLineAtOffset(40, 700);
					content.showText(line + " " + page);
					content.endText();
				}
			}
			document.save(out);
			return out.toByteArray();
		}
	}

	@Test
	void aPdfComesBackAsItsText() throws Exception {
		byte[] data = pdf(1, "the screenshot shows a broken layout on page");
		stored(data, "application/pdf");

		AttachmentContent.Text text = (AttachmentContent.Text)
				service.render(attachment("application/pdf", data.length), LIMITS);

		assertThat(text.text()).contains("broken layout");
		assertThat(text.truncated()).isFalse();
	}

	@Test
	void aLongPdfIsCutAtTheBudgetAndSaysSo() throws Exception {
		byte[] data = pdf(40, "x".repeat(60) + " page");
		stored(data, "application/pdf");

		AttachmentContent.Text text = (AttachmentContent.Text)
				service.render(attachment("application/pdf", data.length), LIMITS);

		assertThat(text.text()).hasSize(200);
		assertThat(text.truncated()).isTrue();
	}

	@Test
	void aPageIsAbandonedOnceItHasCostMoreGlyphsThanItMay() throws Exception {
		// The character budget is only ever checked between pages, and PDFBox lays
		// out a whole page before it emits a single character — so a file whose
		// compressed content stream expands into hundreds of millions of glyphs
		// would take the heap with it while still on page one. The glyph ceiling
		// is the only bound that can see that page coming; here it is set low
		// enough that an ordinary document trips it.
		byte[] data = pdf(5, "the quick brown fox jumps over the lazy dog on page");

		PdfTextExtractor.Extract whole = PdfTextExtractor.extract(data, 100_000);
		PdfTextExtractor.Extract bounded = PdfTextExtractor.extract(data, 100_000, 80);

		assertThat(whole.truncated()).isFalse();
		assertThat(bounded.truncated()).isTrue();
		assertThat(bounded.text().length()).isLessThan(whole.text().length());
	}

	@Test
	void aDocumentThatFitsIsNotAffectedByTheGlyphCeiling() throws Exception {
		// The ceiling is derived from the character budget with room to spare, so
		// a real document must never be cut short by it.
		byte[] data = pdf(3, "a perfectly ordinary paragraph of text on page");
		stored(data, "application/pdf");

		AttachmentContent.Text text = (AttachmentContent.Text) service.render(
				attachment("application/pdf", data.length),
				new AttachmentContentService.Limits(5L * 1024 * 1024, 1600, 20_000));

		assertThat(text.truncated()).isFalse();
		assertThat(text.text()).contains("ordinary paragraph");
	}

	// --- refusals ----------------------------------------------------------

	@Test
	void anOversizedFileIsRefusedWithoutEvenReadingIt() {
		AttachmentContent content = service.render(
				attachment("image/png", 40L * 1024 * 1024), LIMITS);

		assertThat(content).isEqualTo(
				new AttachmentContent.Unavailable(AttachmentContent.Reason.TOO_LARGE));
		// Refusing a 40 MB file must not cost 40 MB of transfer first.
		verify(storage, never()).getObject(anyString());
	}

	@Test
	void bytesLargerThanTheirRecordedSizeAreStillRefused() {
		// The recorded size is metadata; the bytes in hand are what would land in
		// the context window, so both are gated.
		byte[] data = new byte[6 * 1024 * 1024];
		stored(data, "text/plain");

		AttachmentContent content = service.render(attachment("text/plain", 10), LIMITS);

		assertThat(content).isEqualTo(
				new AttachmentContent.Unavailable(AttachmentContent.Reason.TOO_LARGE));
	}

	@Test
	void anArchiveIsNotRenderableAndIsNeverFetched() {
		AttachmentContent content = service.render(attachment("application/zip", 1000), LIMITS);

		assertThat(content).isEqualTo(
				new AttachmentContent.Unavailable(AttachmentContent.Reason.TYPE_NOT_RENDERABLE));
		verify(storage, never()).getObject(anyString());
	}

	@Test
	void anUnknownTypeIsRefusedRatherThanGuessedAt() {
		assertThat(service.isRenderable("application/x-msdownload")).isFalse();
		assertThat(service.isRenderable(null)).isFalse();
		assertThat(service.isRenderable("image/svg+xml")).isFalse();
	}

	@Test
	void aVanishedObjectSaysSoInsteadOfThrowing() {
		when(storage.getObject(anyString())).thenReturn(Optional.empty());

		AttachmentContent content = service.render(attachment("text/plain", 10), LIMITS);

		assertThat(content).isEqualTo(
				new AttachmentContent.Unavailable(AttachmentContent.Reason.MISSING));
	}

	// --- how hard the bounds are worked -----------------------------------

	@Test
	void theShrinkStepIsAimedAtTheByteBudgetRatherThanStepped() {
		// Every retry costs a full scale and re-encode of the source — around
		// 60 ms for a 1600px picture with transparency — so the step is derived
		// from how far over budget the last one came out. Bytes grow with the
		// pixel count and pixels with the square of the width, hence the root.
		int budget = AttachmentContentService.MAX_ENCODED_BYTES;

		// Four times over: shrink hard, but never past half in one step.
		assertThat(AttachmentContentService.narrowerThan(1600, budget * 4)).isEqualTo(800);
		// Twice over: about seven tenths, which a fixed three-quarter step would
		// have needed two passes to reach.
		assertThat(AttachmentContentService.narrowerThan(1600, budget * 2)).isEqualTo(1073);
		// Barely over: never a smaller step than the fixed one, so every attempt
		// still makes real progress and the loop terminates.
		assertThat(AttachmentContentService.narrowerThan(1600, budget + 1)).isEqualTo(1200);
		// The floor wins over the estimate, whatever the overshoot.
		assertThat(AttachmentContentService.narrowerThan(400, budget * 100))
				.isEqualTo(AttachmentContentService.MIN_IMAGE_WIDTH);
	}

	@Test
	void aPictureThatReEncodesTooFatComesBackInsideTheByteBudget() throws Exception {
		// Noise is what an encoder cannot compress away, so this is the case that
		// actually walks the retry loop. What must hold is the bound, not a
		// particular width: the picture leaving here fits in a context window.
		byte[] original = noise(1300, 850);
		stored(original, "image/png");

		AttachmentContent.Image image = (AttachmentContent.Image)
				service.render(attachment("image/png", original.length), LIMITS);

		assertThat(image.bytes().length).isLessThanOrEqualTo(AttachmentContentService.MAX_ENCODED_BYTES);
		assertThat(image.width()).isLessThan(image.sourceWidth());
		assertThat(image.width()).isGreaterThanOrEqualTo(AttachmentContentService.MIN_IMAGE_WIDTH);
	}

	/** A picture with an incompressible interior: the encoder's worst case. */
	private static byte[] noise(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		java.util.Random random = new java.util.Random(19);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setRGB(x, y, 0xC0000000 | random.nextInt(0xFFFFFF));
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	@Test
	void onlyTheTextThatCanBeKeptIsDecoded() {
		// The budget is in characters, so decoding a five megabyte export in full
		// to keep twenty kilobytes of it is work with no answer attached. What the
		// caller gets must not change because of that: it is still exactly the
		// beginning of the file, multi-byte characters and all.
		String unit = "Grüße über größere Bäume – 🌳 ";
		StringBuilder whole = new StringBuilder();
		while (whole.length() < 50_000) {
			whole.append(unit);
		}
		byte[] data = whole.toString().getBytes(StandardCharsets.UTF_8);
		stored(data, "text/plain");

		AttachmentContent.Text text = (AttachmentContent.Text)
				service.render(attachment("text/plain", data.length), LIMITS);

		assertThat(text.truncated()).isTrue();
		assertThat(text.text()).hasSize(200);
		assertThat(text.text()).isEqualTo(whole.substring(0, 200));
	}

	@Test
	void aFileThatIsNotQuiteUtf8StillFillsTheWholeBudget() {
		// Malformed bytes decode to replacement characters, and the JDK folds up
		// to three of them into one — the worst ratio of bytes to characters
		// there is, and the one the read window has to be wide enough for.
		byte[] data = new byte[10_000];
		for (int i = 0; i < data.length; i++) {
			// A truncated three-byte sequence, over and over: never valid, never
			// resynchronising, one replacement character per two bytes at best.
			data[i] = (byte) (i % 2 == 0 ? 0xE0 : 0xA0);
		}
		stored(data, "text/plain");

		AttachmentContent.Text text = (AttachmentContent.Text)
				service.render(attachment("text/plain", data.length), LIMITS);

		assertThat(text.text()).hasSize(200);
		assertThat(text.truncated()).isTrue();
		assertThat(text.text()).isEqualTo(new String(data, StandardCharsets.UTF_8).substring(0, 200));
	}

	@Test
	void textShorterThanTheBudgetIsUnaffectedByTheReadWindow() {
		byte[] data = "Grüße – 🌳 ok".getBytes(StandardCharsets.UTF_8);
		stored(data, "text/plain");

		AttachmentContent.Text text = (AttachmentContent.Text)
				service.render(attachment("text/plain", data.length), LIMITS);

		assertThat(text.text()).isEqualTo("Grüße – 🌳 ok");
		assertThat(text.truncated()).isFalse();
	}

	@Test
	void aFileClaimingToBeAPdfWithoutTheSignatureIsNotHandedToTheParser() {
		byte[] data = "not really a pdf".getBytes(StandardCharsets.UTF_8);
		stored(data, "application/pdf");

		AttachmentContent content = service.render(attachment("application/pdf", data.length), LIMITS);

		assertThat(content).isEqualTo(
				new AttachmentContent.Unavailable(AttachmentContent.Reason.UNREADABLE));
	}
}
