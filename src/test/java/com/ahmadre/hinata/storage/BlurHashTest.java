package com.ahmadre.hinata.storage;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The encoder is verified by decoding its output the way a client does, rather
 * than against a golden string: the hash is only ever useful if the reference
 * decoder — which is what the app runs — reconstructs the original image from
 * it. That catches the classic mistakes (a missing sRGB↔linear conversion, a
 * swapped channel, an off-by-one in the base-83 digits) as a picture that comes
 * back wrong, which is exactly how a user would see them.
 */
class BlurHashTest {

	private static final String ALPHABET =
			"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

	private static BufferedImage solid(Color colour) {
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(colour);
		g.fillRect(0, 0, 32, 32);
		g.dispose();
		return image;
	}

	/** Left-to-right blue → red ramp, flat in the vertical direction. */
	private static BufferedImage horizontalRamp() {
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 32; x++) {
				image.setRGB(x, y, new Color(x * 8, 20, 248 - x * 8).getRGB());
			}
		}
		return image;
	}

	@Test
	void dcTermIsTheImagesAverageColour() {
		Color colour = new Color(200, 80, 40);

		// The DC term is stored unquantised, so it must come back exactly — this
		// is the assertion that pins the sRGB↔linear conversion.
		Color dc = dcColour(BlurHash.encode(solid(colour)));

		assertThat(dc.getRed()).isCloseTo(colour.getRed(), Offset.offset(2));
		assertThat(dc.getGreen()).isCloseTo(colour.getGreen(), Offset.offset(2));
		assertThat(dc.getBlue()).isCloseTo(colour.getBlue(), Offset.offset(2));
	}

	@Test
	void solidImageDecodesBackToItsColour() {
		Color colour = new Color(200, 80, 40);

		Color[][] decoded = decode(BlurHash.encode(solid(colour)), 4, 4);

		for (Color[] row : decoded) {
			for (Color pixel : row) {
				// Wider than the DC check on purpose: BlurHash's basis is not
				// orthogonal and its AC terms are quantised into 19 levels, so a
				// flat surface reconstructs with a few percent of ringing. That is
				// the format, not a defect — the result is a blurred placeholder.
				assertThat(pixel.getRed()).isCloseTo(colour.getRed(), Offset.offset(20));
				assertThat(pixel.getGreen()).isCloseTo(colour.getGreen(), Offset.offset(20));
				assertThat(pixel.getBlue()).isCloseTo(colour.getBlue(), Offset.offset(20));
			}
		}
	}

	@Test
	void gradientKeepsItsDirectionThroughTheHash() {
		Color[][] decoded = decode(BlurHash.encode(horizontalRamp()), 8, 8);
		Color left = decoded[4][0];
		Color right = decoded[4][7];

		// Blue on the left, red on the right — a transposed or mirrored basis
		// function would swap or flatten this.
		assertThat(left.getBlue()).isGreaterThan(left.getRed() + 60);
		assertThat(right.getRed()).isGreaterThan(right.getBlue() + 60);
		// Flat vertically: the top and bottom of a column stay the same colour.
		assertThat(decoded[0][0].getBlue()).isCloseTo(decoded[7][0].getBlue(), Offset.offset(12));
	}

	@Test
	void hashHasTheFormatsShapeAndAlphabet() {
		String hash = BlurHash.encode(solid(Color.GRAY));

		// 1 size flag + 1 maximum + 4 DC + 2 per AC component (4×3 − 1 = 11).
		assertThat(hash).hasSize(6 + 2 * 11);
		assertThat(decode83(hash.substring(0, 1)))
				.isEqualTo((BlurHash.COMPONENTS_X - 1) + (BlurHash.COMPONENTS_Y - 1) * 9);
		for (char c : hash.toCharArray()) {
			assertThat(ALPHABET.indexOf(c)).as("character '%s' is in the alphabet", c).isNotNegative();
		}
	}

	@Test
	void componentCountsOutsideTheFormatAreRejected() {
		assertThatThrownBy(() -> BlurHash.encode(solid(Color.GRAY), 0, 3))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> BlurHash.encode(solid(Color.GRAY), 4, 10))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> BlurHash.encode(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// --- reference decoder (mirrors the published algorithm the app runs) ------

	private static int decode83(String value) {
		int result = 0;
		for (int i = 0; i < value.length(); i++) {
			result = result * 83 + ALPHABET.indexOf(value.charAt(i));
		}
		return result;
	}

	/** The hash's DC term as a colour — the image's average, stored verbatim. */
	private static Color dcColour(String hash) {
		int dc = decode83(hash.substring(2, 6));
		return new Color((dc >> 16) & 0xFF, (dc >> 8) & 0xFF, dc & 0xFF);
	}

	private static Color[][] decode(String hash, int width, int height) {
		int sizeFlag = decode83(hash.substring(0, 1));
		int numX = sizeFlag % 9 + 1;
		int numY = sizeFlag / 9 + 1;
		double maximumValue = (decode83(hash.substring(1, 2)) + 1) / 166.0;

		double[][] colours = new double[numX * numY][];
		colours[0] = decodeDc(decode83(hash.substring(2, 6)));
		for (int i = 1; i < colours.length; i++) {
			colours[i] = decodeAc(decode83(hash.substring(4 + i * 2, 6 + i * 2)), maximumValue);
		}

		Color[][] pixels = new Color[height][width];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				double r = 0;
				double g = 0;
				double b = 0;
				for (int j = 0; j < numY; j++) {
					for (int i = 0; i < numX; i++) {
						double basis = Math.cos(Math.PI * x * i / width)
								* Math.cos(Math.PI * y * j / height);
						double[] colour = colours[i + j * numX];
						r += colour[0] * basis;
						g += colour[1] * basis;
						b += colour[2] * basis;
					}
				}
				pixels[y][x] = new Color(linearTosRgb(r), linearTosRgb(g), linearTosRgb(b));
			}
		}
		return pixels;
	}

	private static double[] decodeDc(int value) {
		return new double[] {
				sRgbToLinear((value >> 16) & 0xFF),
				sRgbToLinear((value >> 8) & 0xFF),
				sRgbToLinear(value & 0xFF),
		};
	}

	private static double[] decodeAc(int value, double maximumValue) {
		int r = value / (19 * 19);
		int g = (value / 19) % 19;
		int b = value % 19;
		return new double[] {
				signedPow((r - 9) / 9.0, 2.0) * maximumValue,
				signedPow((g - 9) / 9.0, 2.0) * maximumValue,
				signedPow((b - 9) / 9.0, 2.0) * maximumValue,
		};
	}

	private static double signedPow(double value, double exponent) {
		return Math.copySign(Math.pow(Math.abs(value), exponent), value);
	}

	private static double sRgbToLinear(int value) {
		double v = value / 255.0;
		return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
	}

	private static int linearTosRgb(double value) {
		double v = Math.max(0, Math.min(1, value));
		return v <= 0.0031308
				? (int) (v * 12.92 * 255 + 0.5)
				: (int) ((1.055 * Math.pow(v, 1 / 2.4) - 0.055) * 255 + 0.5);
	}
}
