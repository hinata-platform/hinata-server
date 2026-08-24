package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.media.ExternalImageFetcher;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The one place any server-side code obtains the organization's logo.
 *
 * <p>There are two shapes of consumer and they need different guarantees, which
 * is why this exists rather than every caller reaching for
 * {@code general.logoUrl} itself:
 * <ul>
 *   <li>{@link #display()} — bytes handed straight to a client (the
 *       {@code /api/v1/meta/logo} proxy). SVG is allowed here because the client
 *       renders it and the response is served under a locked-down CSP.</li>
 *   <li>{@link #raster()} / {@link #mailBand()} — bytes this process
 *       <em>decodes</em>, for PDFs, Word documents and e-mail artwork. Raster
 *       only: pointing a decoder at an SVG means running a document parser on
 *       bytes a third-party host can swap at any moment.</li>
 * </ul>
 *
 * <p>Everything is fetched at most once per configuration and cached. That is
 * not an optimization detail, it is what makes the feature safe to spread:
 * {@code /api/v1/meta/logo} is unauthenticated, so an uncached implementation
 * turns this server into a request amplifier aimed at whatever host the admin
 * configured — and turns every mail send into an outbound fetch on the
 * notification thread. Refresh is event-driven ({@link
 * SettingsService.SettingsChangedEvent}) plus a TTL for external URLs whose
 * contents can change underneath us; there is deliberately no poller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandLogoService {

	/**
	 * Logo bytes plus the content type they should be served or embedded as, and
	 * whether they came from an upload. The last one is carried rather than
	 * re-derived by the caller so serving the logo costs one settings read, not
	 * two — it is an unauthenticated endpoint.
	 */
	public record BrandAsset(byte[] bytes, String contentType, boolean uploaded) {
	}

	/**
	 * How long an externally hosted logo is trusted before it is re-fetched. An
	 * uploaded logo needs no TTL — its URL carries a {@code ?v=} token, so a
	 * re-upload changes the cache key, and a removal fires a settings event.
	 */
	private static final Duration EXTERNAL_TTL = Duration.ofMinutes(15);

	/**
	 * Logos are branding, not photographs. The upload path already normalizes to
	 * {@value OrganizationLogoService#MAX_EDGE}px; this caps the external path so
	 * one hostile (or merely careless) URL cannot park tens of megabytes in the
	 * cache of a process that also serves the API.
	 */
	private static final int MAX_DISPLAY_BYTES = 5 * 1024 * 1024;

	private final SettingsService settings;
	private final OrganizationLogoService logoService;
	private final ExternalImageFetcher fetcher;

	/**
	 * Everything derived from one {@code logoUrl}, swapped atomically. Fields are
	 * {@code null} when that particular rendition does not exist (no logo at all,
	 * or an SVG that cannot be rasterized) — resolved once, so a failing external
	 * host is not retried on every consumer for the whole TTL.
	 */
	private record Snapshot(String key, String organization, BrandAsset display, byte[] raster,
			java.util.Map<String, java.util.Optional<byte[]>> bands, Instant at) {
	}

	private volatile Snapshot cache;

	/** Serializes the derivation so N concurrent callers cause one fetch. */
	private final java.util.concurrent.locks.ReentrantLock refreshing =
			new java.util.concurrent.locks.ReentrantLock();

	// --- public API -----------------------------------------------------------

	/**
	 * The logo exactly as configured — raster or SVG — for handing to a client.
	 * Empty when no logo is configured or the source could not be fetched.
	 */
	public Optional<BrandAsset> display() {
		return Optional.ofNullable(snapshot().display());
	}

	/**
	 * The logo as normalized PNG bytes, for anything this process draws: PDF and
	 * Word exports, the e-mail band. Empty when no logo is configured, when the
	 * source is a vector (which has no decoder here) or when it is unreachable.
	 */
	public Optional<byte[]> raster() {
		return Optional.ofNullable(snapshot().raster());
	}

	/**
	 * The band that opens a mail rendered from [backdrop], carrying the
	 * organization's logo and name in place of Hinata's. Empty on an instance with
	 * no usable logo, where the caller falls back to
	 * {@link MailBandComposer#composeHinata}.
	 *
	 * <p>Composed once per backdrop and held, because a band is derived from an
	 * image decode and a font render — work that must not happen on the thread
	 * sending a password reset.
	 */
	public Optional<byte[]> mailBand(String backdrop) {
		Snapshot snap = snapshot();
		if (snap.raster() == null) {
			return Optional.empty();
		}
		String key = (backdrop == null || backdrop.isBlank())
				? MailBandComposer.DEFAULT_BACKDROP
				: backdrop;
		return snap.bands().computeIfAbsent(key, name -> MailBandComposer.composeOrganization(
				snap.raster(), snap.organization(), name));
	}

	/**
	 * Whether a logo is configured that this process cannot draw — an external
	 * vector or an unreachable URL. Surfaced in the admin area so an operator can
	 * see why their logo shows in the app but not in mails and documents.
	 */
	public boolean configuredButUnusableForDocuments() {
		Snapshot snap = snapshot();
		return snap.key() != null && !snap.key().isBlank() && snap.raster() == null;
	}

	/** Drops the cache so the next read re-derives. */
	public void invalidate() {
		cache = null;
	}

	@EventListener
	public void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		invalidate();
	}

	// --- derivation -----------------------------------------------------------

	/**
	 * The current snapshot, deriving it at most once at a time.
	 *
	 * <p>Deriving means an outbound fetch, and this is reached from
	 * <em>unauthenticated</em> endpoints ({@code /api/v1/meta/logo}, the browser
	 * pages behind a mailed link) as well as from every mail send. So the lock is
	 * held only by the one thread that refreshes: a caller that arrives while a
	 * refresh is in flight and already has a usable snapshot keeps serving that
	 * one rather than queueing behind a slow remote host. Without this, a single
	 * logo host that stops responding would park every request thread on this
	 * monitor and take the whole API down with it.
	 *
	 * <p>Single-flight is preserved either way, which is the security property:
	 * N concurrent callers must still cause at most one outbound fetch, or this
	 * server becomes a request amplifier aimed at whatever host the admin
	 * configured.
	 */
	private Snapshot snapshot() {
		String key = currentKey();
		Snapshot current = cache;
		boolean usable = current != null && java.util.Objects.equals(current.key(), key);
		if (usable && !isStale(current)) {
			return current;
		}
		// Stale but usable: one thread refreshes, everyone else keeps the old bytes.
		// A first load has nothing to fall back to and does have to wait.
		if (usable && !refreshing.tryLock()) {
			return current;
		}
		if (!usable) {
			refreshing.lock();
		}
		try {
			// Re-check: whoever held the lock may have just done the work.
			current = cache;
			if (current != null && java.util.Objects.equals(current.key(), key) && !isStale(current)) {
				return current;
			}
			Snapshot derived = derive(key);
			cache = derived;
			return derived;
		}
		finally {
			refreshing.unlock();
		}
	}

	private boolean isStale(Snapshot snap) {
		if (snap.key() == null || !OrganizationLogoService.isInternal(snap.key())) {
			return snap.at().plus(EXTERNAL_TTL).isBefore(Instant.now());
		}
		// Uploaded: the key already changes on re-upload, and removal fires an event.
		return false;
	}

	private static java.util.Map<String, java.util.Optional<byte[]>> bandCache() {
		return new java.util.concurrent.ConcurrentHashMap<>();
	}

	/** The organization drawn beside the logo, or null on an instance with none. */
	private String organizationName() {
		String name = settings.get().getOrganizationName();
		return name == null || name.isBlank() ? null : name.trim();
	}

	private String currentKey() {
		ServerSettings.General general = settings.get().getGeneral();
		String url = general == null ? null : general.getLogoUrl();
		return url == null ? null : url.trim();
	}

	private Snapshot derive(String key) {
		Instant now = Instant.now();
		String organization = organizationName();
		if (key == null || key.isBlank()) {
			return new Snapshot(key, organization, null, null, bandCache(), now);
		}
		BrandAsset display = fetchDisplay(key);
		if (display == null) {
			return new Snapshot(key, organization, null, null, bandCache(), now);
		}
		// Bands are composed lazily per backdrop rather than eagerly here: an
		// instance sends a handful of the thirteen mail kinds, and each band costs
		// an image decode plus a font render.
		return new Snapshot(key, organization, display, toRaster(display), bandCache(), now);
	}

	/** The configured bytes, from object storage or the external host. Never throws. */
	private BrandAsset fetchDisplay(String url) {
		try {
			if (OrganizationLogoService.isInternal(url)) {
				// An upload: already a normalized PNG sitting in our own bucket, so
				// there is no network hop and nothing to validate.
				return logoService.load()
						.map(stored -> new BrandAsset(stored.data(),
								stored.contentType() == null ? "image/png" : stored.contentType(), true))
						.orElse(null);
			}
			// External: goes through the SSRF-hardened fetcher — http(s) only, every
			// resolved address checked as public unicast, redirects re-validated per
			// hop, content type allow-listed and the body read under a hard cap.
			StorageService.StoredObject fetched =
					fetcher.fetch(url, ExternalImageFetcher.DISPLAY_TYPES);
			if (fetched.data().length > MAX_DISPLAY_BYTES) {
				log.warn("Organization logo at {} is {} bytes; ignoring it",
						sanitize(url), fetched.data().length);
				return null;
			}
			return new BrandAsset(fetched.data(), fetched.contentType(), false);
		}
		catch (Exception ex) {
			log.warn("Organization logo {} could not be loaded: {}", sanitize(url), ex.getMessage());
			return null;
		}
	}

	/** Normalized PNG for anything we draw ourselves, or null for a vector. */
	private byte[] toRaster(BrandAsset asset) {
		String type = asset.contentType() == null ? "" : asset.contentType().toLowerCase();
		if (!ExternalImageFetcher.RASTER_TYPES.contains(type)) {
			return null;
		}
		try {
			return logoService.normalize(asset.bytes());
		}
		catch (Exception ex) {
			log.warn("Organization logo could not be normalized: {}", ex.getMessage());
			return null;
		}
	}

	/**
	 * The URL is admin-controlled and ends up in the log; CR/LF in it would forge
	 * log lines in a plain-text encoder.
	 */
	private static String sanitize(String url) {
		String flat = url.replaceAll("[\\r\\n]", "");
		return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
	}
}
