package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.storage.FileSignature.Family;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one check every byte from a person passes before it is stored (HIN-177): attachments,
 * embedded images, voice messages, avatars, the logo, and attachments of mail. The type comes
 * from the content, not from what was declared; the kind decides which types and how many bytes;
 * an image's size is read from its header before anything decodes it; a file name carries no
 * control character and no path. What it refuses is not stored at all.
 *
 * <p>Images are not checked for what they show. Whether a picture is acceptable is for a person
 * to judge once somebody reports it; a later automatic check would start here, in one place.
 */
@Component
public class UploadGate {

	/** Longest file name kept. */
	public static final int FILE_NAME_MAX = 200;

	/**
	 * Endings a system runs or renders when the file is opened: programs, scripts, shortcuts and web
	 * pages. Refused for every kind, whatever the content, so a download never becomes one.
	 */
	private static final Set<String> RUNNABLE = Set.of("exe", "com", "scr", "pif", "cpl", "msi", "msp", "mst",
			"dll", "sys", "drv", "ocx", "bat", "cmd", "ps1", "psm1", "psd1", "vbs", "vbe", "js", "jse", "mjs",
			"wsf", "wsh", "wsc", "sct", "hta", "lnk", "url", "scf", "reg", "inf", "jar", "jnlp", "appref-ms",
			"application", "gadget", "msc", "chm", "hlp", "command", "tool", "app", "pkg", "mpkg", "sh", "csh",
			"ksh", "html", "htm", "xhtml", "shtml", "mht", "mhtml", "svg", "svgz", "xml", "xsl", "xslt");

	/** The kinds whose types an instance can switch off by group. */
	private static final Set<UploadKind> SWITCHABLE = EnumSet.of(UploadKind.ATTACHMENT, UploadKind.MAIL_ATTACHMENT,
			UploadKind.MEDIA, UploadKind.PASTED_IMAGE, UploadKind.VOICE);

	/** Every type the gate tells by its content: the most an instance can ever allow. */
	private static final Set<String> SAFE = Arrays.stream(UploadGroup.values())
			.flatMap(group -> group.types().stream()).collect(Collectors.toUnmodifiableSet());
	private static final Set<String> IMAGES = UploadGroup.IMAGES.types();
	private static final Set<String> VOICE = UploadGroup.VOICE.types();
	private static final Set<String> PICTURES = Set.of("image/png", "image/jpeg", "image/gif", "image/bmp");

	/** A voice message, as long as the recorder in the app makes one. */
	static final long VOICE_MAX_BYTES = 16L * 1024 * 1024;
	static final long AVATAR_MAX_BYTES = 12L * 1024 * 1024;
	static final long LOGO_MAX_BYTES = 8L * 1024 * 1024;
	/** Pixels the logo may have; everything else but a pasted image gets the decoder's own budget. */
	static final long LOGO_MAX_PIXELS = 50_000_000L;
	/** Pixels an image pasted into a text may have: a large screenshot fits, a decompression bomb does not. */
	static final long PASTED_MAX_PIXELS = 25_000_000L;

	private final HinataProperties properties;
	private final ObjectProvider<UploadPolicy> policy;
	private final ApplicationEventPublisher events;

	public UploadGate(HinataProperties properties, ObjectProvider<UploadPolicy> policy,
			ApplicationEventPublisher events) {
		this.properties = properties;
		this.policy = policy;
		this.events = events;
	}

	/** What the gate let through: the type the content really has. */
	public record Accepted(String contentType) {
	}

	/**
	 * Checks [candidate]: 422 {@code upload.refused} with the reason in the details when it may not
	 * be stored, counted as a refused upload, and its real type otherwise.
	 */
	public Accepted check(UploadCandidate candidate) {
		try {
			return judge(candidate);
		}
		catch (Refused refused) {
			throw refuse(candidate.kind(), refused.reason);
		}
	}

	/**
	 * Why [candidate] would be refused, or empty when it would pass. Counts nothing: for asking
	 * what the gate would say without anybody uploading anything.
	 */
	public Optional<UploadRefusal> refusalOf(UploadCandidate candidate) {
		try {
			judge(candidate);
			return Optional.empty();
		}
		catch (Refused refused) {
			return Optional.of(refused.reason);
		}
	}

	/**
	 * The bytes of an embedded image the gate let through, without their metadata
	 * ({@link ImageMetadata}). Refused as {@code metadata} when its blocks do not read cleanly:
	 * such an image is never stored as it came.
	 */
	byte[] withoutMetadata(UploadCandidate candidate, Accepted accepted) {
		try {
			return ImageMetadata.strip(candidate.bytes(), accepted.contentType());
		}
		catch (ImageMetadata.UnreadableException | IOException ex) {
			throw refuse(candidate.kind(), UploadRefusal.METADATA);
		}
	}

	private Accepted judge(UploadCandidate candidate) {
		UploadKind kind = candidate.kind();
		if (!fileNameIsSafe(candidate.fileName())) {
			throw new Refused(UploadRefusal.FILE_NAME);
		}
		if (runsWhenOpened(candidate.fileName())) {
			throw new Refused(UploadRefusal.TYPE);
		}
		if (candidate.size() <= 0) {
			throw new Refused(UploadRefusal.EMPTY);
		}
		if (candidate.size() > maxBytes(kind)) {
			throw new Refused(UploadRefusal.SIZE);
		}
		byte[] head;
		try {
			head = candidate.head(FileSignature.TEXT_SAMPLE);
		}
		catch (IOException ex) {
			throw new Refused(UploadRefusal.UNREADABLE);
		}
		String type = typeOf(candidate, head);
		if (type == null || !allowed(kind).contains(type)) {
			throw new Refused(UploadRefusal.TYPE);
		}
		if (SWITCHABLE.contains(kind) && switchedOff(type)) {
			throw new Refused(UploadRefusal.DISABLED);
		}
		if (PICTURES.contains(type) && !withinPixels(candidate, maxPixels(kind))) {
			throw new Refused(UploadRefusal.IMAGE);
		}
		return new Accepted(type);
	}

	/**
	 * The type the content really is. For mail the header is a sender's word and counts for
	 * nothing but a hint between ZIP and an office document, and content no signature names has no
	 * type; for everything else the declared type must be allowed and be what the bytes say.
	 */
	private String typeOf(UploadCandidate candidate, byte[] head) {
		Family family = FileSignature.detect(head);
		String declared = normalize(candidate.declaredType());
		if (candidate.kind() == UploadKind.MAIL_ATTACHMENT) {
			if (family == null) {
				return isText(declared) && FileSignature.looksLikeText(head) ? declared : null;
			}
			return family == Family.ZIP && isZipBased(declared) ? declared : typeOf(family);
		}
		if (declared == null || !allowed(candidate.kind()).contains(declared)) {
			throw new Refused(UploadRefusal.TYPE);
		}
		boolean matches = family == null ? isText(declared) && FileSignature.looksLikeText(head)
				: familiesOf(declared).contains(family);
		if (!matches) {
			throw new Refused(UploadRefusal.SIGNATURE);
		}
		return declared;
	}

	/**
	 * The types of [kind]. For attachments the configured list, but never more than the gate can
	 * tell by content: a type added to the environment that no signature covers stays refused.
	 */
	private Collection<String> allowed(UploadKind kind) {
		return switch (kind) {
			case ATTACHMENT, MAIL_ATTACHMENT -> properties.getStorage().getAllowedContentTypes().stream()
					.filter(SAFE::contains).toList();
			case MEDIA, PASTED_IMAGE -> IMAGES;
			case VOICE -> VOICE;
			case AVATAR, LOGO -> PICTURES;
		};
	}

	/**
	 * The types an attachment may have right now: the safe list, narrowed by the configuration and
	 * by the groups switched off. What the app is told before it lets somebody pick a file.
	 */
	public List<String> attachmentTypes() {
		UploadPolicy instance = policy.getIfAvailable();
		Set<UploadGroup> off = instance == null ? Set.of() : instance.disabledGroups();
		return allowed(UploadKind.ATTACHMENT).stream()
				.filter(type -> UploadGroup.of(type) == null || !off.contains(UploadGroup.of(type))).toList();
	}

	/** The most bytes [kind] may have. */
	public long maxBytes(UploadKind kind) {
		return switch (kind) {
			case ATTACHMENT, MAIL_ATTACHMENT, MEDIA, PASTED_IMAGE ->
					(long) properties.getStorage().getMaxUploadMb() * 1024 * 1024;
			case VOICE -> VOICE_MAX_BYTES;
			case AVATAR -> AVATAR_MAX_BYTES;
			case LOGO -> LOGO_MAX_BYTES;
		};
	}

	private static long maxPixels(UploadKind kind) {
		return switch (kind) {
			case LOGO -> LOGO_MAX_PIXELS;
			case PASTED_IMAGE -> PASTED_MAX_PIXELS;
			default -> ImageOps.MAX_DECODE_PIXELS;
		};
	}

	private boolean switchedOff(String type) {
		UploadPolicy instance = policy.getIfAvailable();
		UploadGroup group = UploadGroup.of(type);
		return instance != null && group != null && instance.disabledGroups().contains(group);
	}

	/**
	 * Whether the image's header names a size within [maxPixels], read before anything decodes it.
	 * Through a memory-cached stream, as {@link ImageOps} reads: the factory in {@link ImageIO}
	 * would spool the upload through a temp file first.
	 */
	private static boolean withinPixels(UploadCandidate candidate, long maxPixels) {
		try (InputStream in = candidate.content().open();
				ImageInputStream stream = new MemoryCacheImageInputStream(in)) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				return false;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(stream, true, true);
				long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
				return pixels > 0 && pixels <= Math.min(maxPixels, ImageOps.MAX_DECODE_PIXELS);
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException ex) {
			return false;
		}
	}

	private static Set<Family> familiesOf(String type) {
		return switch (type) {
			case "image/png" -> Set.of(Family.PNG);
			case "image/jpeg" -> Set.of(Family.JPEG);
			case "image/gif" -> Set.of(Family.GIF);
			case "image/webp" -> Set.of(Family.WEBP);
			case "image/bmp" -> Set.of(Family.BMP);
			case "application/pdf" -> Set.of(Family.PDF);
			case "application/zip", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> Set.of(Family.ZIP);
			case "audio/mp4", "audio/x-m4a", "audio/m4a" -> Set.of(Family.MP4);
			case "audio/aac" -> Set.of(Family.AAC, Family.MP4);
			case "audio/mpeg" -> Set.of(Family.MP3);
			case "audio/webm" -> Set.of(Family.WEBM);
			case "audio/ogg" -> Set.of(Family.OGG);
			case "audio/wav", "audio/x-wav" -> Set.of(Family.WAV);
			default -> Set.of();
		};
	}

	private static String typeOf(Family family) {
		return switch (family) {
			case PNG -> "image/png";
			case JPEG -> "image/jpeg";
			case GIF -> "image/gif";
			case WEBP -> "image/webp";
			case BMP -> "image/bmp";
			case PDF -> "application/pdf";
			case ZIP -> "application/zip";
			case MP4 -> "audio/mp4";
			case AAC -> "audio/aac";
			case MP3 -> "audio/mpeg";
			case WEBM -> "audio/webm";
			case OGG -> "audio/ogg";
			case WAV -> "audio/wav";
		};
	}

	private static boolean isText(String type) {
		return "text/plain".equals(type) || "text/csv".equals(type) || "application/json".equals(type);
	}

	private static boolean isZipBased(String type) {
		return "application/zip".equals(type) || UploadGroup.OFFICE.types().contains(type);
	}

	/** The declared type in lower case and without parameters; {@code image/jpg} is a JPEG too. */
	private static String normalize(String declared) {
		if (declared == null || declared.isBlank()) {
			return null;
		}
		String type = declared.strip().toLowerCase(Locale.ROOT);
		int parameters = type.indexOf(';');
		if (parameters >= 0) {
			type = type.substring(0, parameters).strip();
		}
		return "image/jpg".equals(type) ? "image/jpeg" : type;
	}

	/**
	 * Whether [name] ends like a program, a script, a shortcut or a web page. Trailing dots and
	 * spaces count for nothing, as Windows drops them; only the last ending counts, so
	 * {@code Rechnung.pdf.hta} is the page it would open as.
	 */
	static boolean runsWhenOpened(String name) {
		if (name == null) {
			return false;
		}
		String trimmed = name.strip();
		while (trimmed.endsWith(".")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
		}
		int dot = trimmed.lastIndexOf('.');
		return dot >= 0 && RUNNABLE.contains(trimmed.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	/** No control character, no path, not too long; no name at all is fine, the store names it. */
	static boolean fileNameIsSafe(String name) {
		if (name == null) {
			return true;
		}
		if (name.length() > FILE_NAME_MAX) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c < 0x20 || c == 0x7F || c == '/' || c == '\\') {
				return false;
			}
		}
		return true;
	}

	/** Counts the refusal and words it for the person who sent the bytes. */
	private ApiException refuse(UploadKind kind, UploadRefusal reason) {
		events.publishEvent(new UploadRefusedEvent(kind, reason.code()));
		Map<String, String> details = Map.of("code", "upload.refused", "reason", reason.code());
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, reason.messageKey(), details,
				argumentsOf(reason, kind));
	}

	/** What the sentence of [reason] fills in: the limit in MB for one about size, nothing else. */
	public Object[] argumentsOf(UploadRefusal reason, UploadKind kind) {
		return reason == UploadRefusal.SIZE ? new Object[] { maxBytes(kind) / (1024 * 1024) } : new Object[0];
	}

	/** A refusal on its way out of {@link #judge}; no trace, it never leaves this class. */
	private static final class Refused extends RuntimeException {

		private final transient UploadRefusal reason;

		Refused(UploadRefusal reason) {
			super(reason.code(), null, false, false);
			this.reason = reason;
		}
	}
}
