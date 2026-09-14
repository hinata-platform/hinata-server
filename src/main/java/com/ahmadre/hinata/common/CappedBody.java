package com.ahmadre.hinata.common;

import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.GzipSource;
import okio.Okio;
import okio.Source;

import java.io.IOException;
import java.io.Serial;
import java.util.Locale;

/**
 * Reads a response body against a cap on its bytes, and stops inside the read that
 * crosses the cap instead of measuring what has already arrived.
 *
 * <p>The request has to name its {@code Accept-Encoding}, normally {@code identity}.
 * Without one, OkHttp asks for gzip itself and unpacks it before this class sees a
 * byte, and the packed bytes go uncounted. Some servers send gzip despite
 * {@code identity}; iCloud does. So gzip is unpacked here, and the cap counts the
 * packed bytes as well as the unpacked ones: a small body cannot unfold into a large
 * one, and a stream of empty deflate blocks, which unpack to nothing, cannot run on
 * past the cap either. Any other encoding is refused.
 */
public final class CappedBody {

	private CappedBody() {
	}

	/**
	 * The body of [response], unpacked if it came as gzip, and empty when there is none.
	 *
	 * @throws TooLarge                 when the declared length is above [cap], or as soon
	 *                                  as more than [cap] bytes have come in, packed or unpacked
	 * @throws Unreadable               when the body comes in an encoding other than identity
	 *                                  or gzip, or is labelled gzip without being gzip
	 * @throws IOException              when the connection fails or the call times out
	 * @throws IllegalArgumentException when the request left the encoding to OkHttp
	 */
	public static byte[] read(Response response, long cap) throws IOException {
		if (response.request().header("Accept-Encoding") == null) {
			throw new IllegalArgumentException("name the Accept-Encoding, or OkHttp unpacks gzip where nothing counts it");
		}
		ResponseBody body = response.body();
		if (body == null) {
			return new byte[0];
		}
		String encoding = response.header("Content-Encoding");
		String coding = encoding == null ? "" : encoding.strip().toLowerCase(Locale.ROOT);
		boolean gzip = coding.equals("gzip") || coding.equals("x-gzip");
		if (!gzip && !coding.isEmpty() && !coding.equals("identity")) {
			throw new Unreadable();
		}
		if (body.contentLength() > cap) {
			throw new TooLarge();
		}
		BufferedSource wire = body.source();
		if (gzip && !looksLikeGzip(wire)) {
			throw new Unreadable();
		}
		Source counted = new Counting(wire, cap);
		// Closed on the way out: only GzipSource.close() frees the native inflater at once.
		try (BufferedSource source = Okio.buffer(gzip ? new GzipSource(counted) : counted)) {
			Buffer buffer = new Buffer();
			while (source.read(buffer, 8192) != -1) {
				if (buffer.size() > cap) {
					throw new TooLarge();
				}
			}
			return buffer.readByteArray();
		}
	}

	/** Every gzip stream starts with 1f 8b; a body labelled gzip that does not was never packed. */
	private static boolean looksLikeGzip(BufferedSource source) throws IOException {
		return source.request(2) && source.getBuffer().getByte(0) == (byte) 0x1f
				&& source.getBuffer().getByte(1) == (byte) 0x8b;
	}

	/** More than the cap came in. An IOException, because that is all a Source may throw. */
	public static final class TooLarge extends IOException {
		@Serial
		private static final long serialVersionUID = 1L;
	}

	/** The body comes in an encoding this class does not read. */
	public static final class Unreadable extends IOException {
		@Serial
		private static final long serialVersionUID = 1L;
	}

	/**
	 * Counts the bytes as they come off the wire and throws inside the very read that
	 * crosses the cap. Counting outside is too late for gzip: okio's inflater keeps
	 * reading until it has output, so a single read could swallow the whole stream.
	 */
	private static final class Counting extends ForwardingSource {

		private final long cap;
		private long count;

		Counting(Source delegate, long cap) {
			super(delegate);
			this.cap = cap;
		}

		@Override
		public long read(Buffer sink, long byteCount) throws IOException {
			long read = super.read(sink, byteCount);
			if (read > 0) {
				count += read;
				if (count > cap) {
					throw new TooLarge();
				}
			}
			return read;
		}
	}
}
