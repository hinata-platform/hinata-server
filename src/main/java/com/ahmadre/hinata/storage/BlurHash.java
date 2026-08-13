package com.ahmadre.hinata.storage;

import java.awt.image.BufferedImage;

/**
 * Encoder for the <a href="https://blurha.sh">BlurHash</a> format: a ~30 byte
 * string that holds a blurred, low-frequency version of an image. It ships
 * inside the JSON that lists a picture, so a client can paint a recognisable
 * placeholder in the first frame — before a single image byte has been
 * requested — instead of an empty grey box.
 *
 * <p>Implemented in-house rather than pulled in as a dependency: the format is a
 * fixed, ~100-line DCT over sRGB with a base-83 alphabet that has not changed
 * since it was published, and the only consumer is this pipeline. Decoding
 * happens in the app.
 *
 * <p>Cost is {@code O(width × height × componentX × componentY)}, so callers
 * pass an already downscaled image ({@link #MAX_SOURCE_EDGE}); the output is
 * indistinguishable because every high frequency is discarded anyway.
 */
public final class BlurHash {

	/** The format's alphabet — index is the digit's value. */
	private static final String ALPHABET =
			"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

	/** Longest edge a caller should hand in; more pixels change nothing visible. */
	public static final int MAX_SOURCE_EDGE = 64;

	/** Horizontal / vertical DCT components — 4×3 is the format's usual default. */
	public static final int COMPONENTS_X = 4;
	public static final int COMPONENTS_Y = 3;

	private BlurHash() {
	}

	/** Encodes [image] with the default 4×3 components. */
	public static String encode(BufferedImage image) {
		return encode(image, COMPONENTS_X, COMPONENTS_Y);
	}

	/**
	 * Encodes [image] into a BlurHash with [componentX]×[componentY] components
	 * (each 1..9). The image is read once into a linear-light buffer; every
	 * component is then a weighted sum over those pixels.
	 */
	public static String encode(BufferedImage image, int componentX, int componentY) {
		if (image == null) {
			throw new IllegalArgumentException("image is required");
		}
		if (componentX < 1 || componentX > 9 || componentY < 1 || componentY > 9) {
			throw new IllegalArgumentException("components must be within 1..9");
		}
		int width = image.getWidth();
		int height = image.getHeight();
		if (width < 1 || height < 1) {
			throw new IllegalArgumentException("image must not be empty");
		}

		// Linear-light RGB of every pixel, read once: the component loop below
		// touches each pixel componentX × componentY times, and sRGB→linear is a
		// pow() that must not be paid again on each of those passes.
		double[] linear = new double[width * height * 3];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int argb = image.getRGB(x, y);
				int i = (y * width + x) * 3;
				linear[i] = sRgbToLinear((argb >> 16) & 0xFF);
				linear[i + 1] = sRgbToLinear((argb >> 8) & 0xFF);
				linear[i + 2] = sRgbToLinear(argb & 0xFF);
			}
		}

		double[][] factors = new double[componentX * componentY][];
		for (int j = 0; j < componentY; j++) {
			for (int i = 0; i < componentX; i++) {
				factors[j * componentX + i] = multiplyBasisFunction(linear, width, height, i, j);
			}
		}

		double[] dc = factors[0];
		int acCount = factors.length - 1;
		StringBuilder hash = new StringBuilder(6 + 2 * acCount);

		encodeBase83(hash, (componentX - 1) + (componentY - 1) * 9, 1);

		double maximumValue;
		if (acCount > 0) {
			double actualMaximumValue = 0;
			for (int k = 1; k < factors.length; k++) {
				for (int c = 0; c < 3; c++) {
					actualMaximumValue = Math.max(actualMaximumValue, Math.abs(factors[k][c]));
				}
			}
			int quantised = Math.max(0, Math.min(82, (int) Math.floor(actualMaximumValue * 166 - 0.5)));
			maximumValue = (quantised + 1) / 166.0;
			encodeBase83(hash, quantised, 1);
		}
		else {
			maximumValue = 1;
			encodeBase83(hash, 0, 1);
		}

		encodeBase83(hash, encodeDc(dc), 4);
		for (int k = 1; k < factors.length; k++) {
			encodeBase83(hash, encodeAc(factors[k], maximumValue), 2);
		}
		return hash.toString();
	}

	private static double[] multiplyBasisFunction(double[] linear, int width, int height,
			int componentX, int componentY) {
		double r = 0;
		double g = 0;
		double b = 0;
		double normalisation = (componentX == 0 && componentY == 0) ? 1 : 2;
		for (int y = 0; y < height; y++) {
			double basisY = Math.cos(Math.PI * componentY * y / height);
			for (int x = 0; x < width; x++) {
				double basis = normalisation
						* Math.cos(Math.PI * componentX * x / width) * basisY;
				int i = (y * width + x) * 3;
				r += basis * linear[i];
				g += basis * linear[i + 1];
				b += basis * linear[i + 2];
			}
		}
		double scale = 1.0 / (width * height);
		return new double[] {r * scale, g * scale, b * scale};
	}

	private static int encodeDc(double[] value) {
		return (linearTosRgb(value[0]) << 16) + (linearTosRgb(value[1]) << 8) + linearTosRgb(value[2]);
	}

	private static int encodeAc(double[] value, double maximumValue) {
		int r = quantiseAc(value[0], maximumValue);
		int g = quantiseAc(value[1], maximumValue);
		int b = quantiseAc(value[2], maximumValue);
		return r * 19 * 19 + g * 19 + b;
	}

	private static int quantiseAc(double value, double maximumValue) {
		double signed = signedPow(value / maximumValue, 0.5) * 9 + 9.5;
		return (int) Math.max(0, Math.min(18, Math.floor(signed)));
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

	private static void encodeBase83(StringBuilder out, int value, int length) {
		int divisor = 1;
		for (int i = 1; i < length; i++) {
			divisor *= 83;
		}
		for (int i = 0; i < length; i++) {
			int digit = (value / divisor) % 83;
			divisor /= 83;
			out.append(ALPHABET.charAt(digit));
		}
	}
}
