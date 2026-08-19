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

	@Test
	void aFileClaimingToBeAPdfWithoutTheSignatureIsNotHandedToTheParser() {
		byte[] data = "not really a pdf".getBytes(StandardCharsets.UTF_8);
		stored(data, "application/pdf");

		AttachmentContent content = service.render(attachment("application/pdf", data.length), LIMITS);

		assertThat(content).isEqualTo(
				new AttachmentContent.Unavailable(AttachmentContent.Reason.UNREADABLE));
	}
}
