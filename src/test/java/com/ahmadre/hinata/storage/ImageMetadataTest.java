package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.media.MediaController;
import com.ahmadre.hinata.media.MediaService;
import com.ahmadre.hinata.storage.StorageService.StoredObject;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.ahmadre.hinata.storage.TestImages.indexOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An image embedded in a page or a comment travels to everybody who reads it. A phone photo
 * carries where it was taken, so an embedded image loses its metadata before it is stored
 * (HIN-177): cut out block by block, so quality and animation stay. A file somebody attaches on
 * purpose arrives as it was sent.
 */
class ImageMetadataTest {

	private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
	private final List<UploadRefusedEvent> refused = new ArrayList<>();
	private final StorageService storage = TestStorage.inMemory(TestUploadGates.counting(refused), objects);
	private final MediaService media = new MediaService(storage, new ImagePreviewService());

	private static MockMultipartFile upload(String name, String type, byte[] bytes) {
		return new MockMultipartFile("file", name, type, bytes);
	}

	/** The fixture has to carry what the strip removes, or the assertions below would prove nothing. */
	@Test
	void theFixturesCarryTheirMetadata() {
		byte[] photo = TestImages.jpegWithExif(64, 32, 1);
		assertThat(indexOf(photo, TestImages.EXIF_SIGNATURE)).isNotNegative();
		assertThat(indexOf(photo, TestImages.gpsIfd())).isNotNegative();
		assertThat(indexOf(photo, "Hamburg")).isNotNegative();
		assertThat(indexOf(TestImages.pngWithText(4, 4), "taken at home")).isNotNegative();
		assertThat(indexOf(TestImages.animatedGif(3), "Hamburg")).isNotNegative();
	}

	/** A save brings up to twenty pasted images; none of them is decoded before somebody looks. */
	@Test
	void aPastedPhotoLosesItsPositionAndGetsItsThumbnailOnFirstView() {
		byte[] photo = TestImages.jpegWithExif(64, 32, 1);

		String url = media.storePasted(photo, "image/jpeg");
		String id = url.substring("/api/v1/media/".length());

		assertThat(indexOf(objects.get("media/" + id).data(), TestImages.gpsIfd())).as("GPS IFD").isNegative();
		assertThat(objects).doesNotContainKey(ImagePreviewService.mediaThumbnailKey(id));
		assertThat(media.loadThumbnail(id).contentType()).isEqualTo("image/jpeg");
		assertThat(objects).containsKey(ImagePreviewService.mediaThumbnailKey(id));
	}

	@Test
	void aPhotoIsStoredAndServedWithoutItsPosition() throws Exception {
		byte[] photo = TestImages.jpegWithExif(64, 32, 1);

		MediaService.MediaUpload stored = media.store(upload("urlaub.jpg", "image/jpeg", photo));
		String id = stored.url().substring("/api/v1/media/".length());
		byte[] served = new MediaController(media).get(id).getBody();

		assertThat(served).isNotNull();
		assertThat(indexOf(served, TestImages.EXIF_SIGNATURE)).as("Exif header").isNegative();
		assertThat(indexOf(served, TestImages.gpsIfd())).as("GPS IFD").isNegative();
		assertThat(indexOf(served, "Hamburg")).as("XMP and IPTC").isNegative();
		assertThat(indexOf(served, "taken at home")).as("comment").isNegative();
		// Cut out, not encoded again: everything that was the picture is still there.
		assertThat(served.length).isEqualTo(TestImages.jpeg(64, 32).length);
		assertThat(stored.size()).isEqualTo(served.length);
		assertThat(ImageIO.read(new ByteArrayInputStream(served))).isNotNull();
	}

	/**
	 * The EXIF said "turn it a quarter clockwise". That one fact stays, in an EXIF block of its own,
	 * and the pixels are not touched: no decoding at full size, nothing encoded again (HIN-177).
	 */
	@Test
	void aTurnedPhotoKeepsOnlyItsTurn() throws Exception {
		byte[] photo = TestImages.jpegWithExif(64, 32, 6);

		byte[] stripped = ImageMetadata.strip(photo, "image/jpeg");

		assertThat(ImageMetadata.jpegOrientation(stripped)).isEqualTo(6);
		assertThat(indexOf(stripped, TestImages.gpsIfd())).as("GPS IFD").isNegative();
		assertThat(indexOf(stripped, "Hamburg")).as("XMP and IPTC").isNegative();
		assertThat(stripped.length).as("the picture's bytes as they were, and the turn")
				.isEqualTo(TestImages.jpeg(64, 32).length + ImageMetadata.ORIENTATION_SEGMENT_LENGTH);
		assertThat(ImageIO.read(new ByteArrayInputStream(stripped))).isNotNull();
	}

	/** The preview, drawn by the server, stands the way the photo is shown. */
	@Test
	void thePreviewOfATurnedPhotoStandsUpright() throws Exception {
		byte[] stripped = ImageMetadata.strip(TestImages.jpegWithExif(64, 32, 6), "image/jpeg");

		BufferedImage preview = ImageIO.read(new ByteArrayInputStream(
				new ImagePreviewService().create(stripped).orElseThrow().bytes()));

		assertThat(preview.getWidth()).isLessThan(preview.getHeight());
		assertThat(dominant(preview.getRGB(preview.getWidth() / 2, 2))).isEqualTo(Color.RED);
	}

	private static Color dominant(int rgb) {
		Color color = new Color(rgb);
		return color.getRed() > color.getBlue() ? Color.RED : Color.BLUE;
	}

	@Test
	void anAnimationKeepsEveryFrame() throws Exception {
		byte[] stripped = ImageMetadata.strip(TestImages.animatedGif(3), "image/gif");

		assertThat(indexOf(stripped, "Hamburg")).as("XMP").isNegative();
		assertThat(indexOf(stripped, "taken at home")).as("comment").isNegative();
		assertThat(indexOf(stripped, "NETSCAPE2.0")).as("the loop").isNotNegative();
		ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		try (var in = new MemoryCacheImageInputStream(new ByteArrayInputStream(stripped))) {
			reader.setInput(in);
			assertThat(reader.getNumImages(true)).isEqualTo(3);
		}
		finally {
			reader.dispose();
		}
	}

	@Test
	void aWebpLosesExifAndXmpAndKeepsWhatDrawsIt() throws Exception {
		byte[] stripped = ImageMetadata.strip(TestImages.webpWithMetadata(), "image/webp");

		assertThat(chunksOf(stripped)).containsExactly("VP8X", "ICCP", "VP8L");
		// The header no longer promises EXIF and XMP; it still promises the colour profile.
		assertThat(stripped[20] & 0xFF).isEqualTo(0x20);
		long riffSize = (stripped[4] & 0xFF) | (stripped[5] & 0xFF) << 8 | (stripped[6] & 0xFF) << 16
				| (long) (stripped[7] & 0xFF) << 24;
		assertThat(riffSize).isEqualTo(stripped.length - 8);
	}

	private static List<String> chunksOf(byte[] webp) {
		List<String> chunks = new ArrayList<>();
		int i = 12;
		while (i + 8 <= webp.length) {
			chunks.add(new String(webp, i, 4, StandardCharsets.US_ASCII));
			int size = (webp[i + 4] & 0xFF) | (webp[i + 5] & 0xFF) << 8 | (webp[i + 6] & 0xFF) << 16
					| (webp[i + 7] & 0xFF) << 24;
			i += 8 + size + (size & 1);
		}
		return chunks;
	}

	@Test
	void aPngLosesItsWordsAndKeepsItsPixels() throws Exception {
		byte[] source = TestImages.pngWithText(6, 4);

		byte[] stripped = ImageMetadata.strip(source, "image/png");

		for (String gone : List.of("tEXt", "iTXt", "zTXt", "eXIf", "tIME", "prVt", "taken at home")) {
			assertThat(indexOf(stripped, gone)).as(gone).isNegative();
		}
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(stripped));
		assertThat(image.getRGB(3, 2)).isEqualTo(ImageIO.read(new ByteArrayInputStream(TestImages.png(6, 4)))
				.getRGB(3, 2));
	}

	/** A block that does not read cleanly is no reason to store the image as it came. */
	@Test
	void anImageWithABrokenBlockIsRefusedAndNothingIsStored() {
		// Cut inside the EXIF segment: its length now points past the end.
		byte[] cut = Arrays.copyOf(TestImages.jpegWithExif(64, 32, 1), 40);
		assertThatThrownBy(() -> ImageMetadata.strip(cut, "image/jpeg"))
				.isInstanceOf(ImageMetadata.UnreadableException.class);

		// Behind a sound header, so the gate lets it through to the strip: a chunk claiming 2 GB.
		byte[] png = TestImages.png(4, 4);
		byte[] broken = new byte[png.length + 8];
		System.arraycopy(png, 0, broken, 0, 33);
		System.arraycopy(new byte[] { 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 't', 'E', 'X', 't' }, 0,
				broken, 33, 8);
		System.arraycopy(png, 33, broken, 41, png.length - 33);
		assertThatThrownBy(() -> media.store(upload("kaputt.png", "image/png", broken)))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getMessageKey()).isEqualTo("error.upload.refused.metadata");
					assertThat(ex.getDetails()).containsEntry("reason", "metadata");
				});
		assertThat(objects).isEmpty();
		assertThat(refused).containsExactly(new UploadRefusedEvent(UploadKind.MEDIA, "metadata"));
	}

	@Test
	void anAttachmentArrivesAsItWasSent() throws Exception {
		byte[] photo = TestImages.jpegWithExif(64, 32, 6);

		StorageService.Stored stored = storage.upload(
				UploadCandidate.of(UploadKind.ATTACHMENT, upload("urlaub.jpg", "image/jpeg", photo)), "");

		assertThat(objects.get(stored.objectKey()).data()).isEqualTo(photo);
		assertThat(stored.data()).as("streamed, not held").isNull();
	}
}
