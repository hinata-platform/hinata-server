package com.ahmadre.hinata.media;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.richtext.EmbeddedImages;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * Keeps the images that arrive inside a text in this server's media (HIN-177), as uploads are
 * kept: through the gate, without their metadata. The words a text gets in place of an image are
 * in the instance's language, because everybody who opens the text reads them.
 */
@Component
@RequiredArgsConstructor
class MediaEmbeddedImages implements EmbeddedImages {

	private final MediaService media;
	private final StorageService storage;
	private final HinataProperties properties;
	private final SettingsService settings;
	private final MessageSource messages;

	/**
	 * Empty for a refusal of the gate and on an instance without object storage, where no image
	 * can ever be kept. A store that is down is not that: it fails the save, so nobody loses the
	 * image they pasted and the next try can keep it.
	 */
	@Override
	public Optional<String> store(byte[] data, String contentType) {
		if (!storage.isConfigured()) {
			return Optional.empty();
		}
		try {
			return Optional.of(media.storePasted(data, contentType));
		}
		catch (ApiException ex) {
			if ("upload.refused".equals(ex.getDetails().get("code"))) {
				return Optional.empty();
			}
			throw ex;
		}
	}

	@Override
	public boolean isOwn(String url) {
		URI target = parse(url);
		return target != null && (sameOrigin(target, parse(properties.getBaseUrl()))
				|| sameOrigin(target, parse(properties.webBase())));
	}

	@Override
	public String externalImageLabel() {
		return messages.getMessage("richtext.externalImage", null, settings.instanceLocale());
	}

	@Override
	public String imageNotKeptText() {
		return messages.getMessage("richtext.imageNotKept", null, settings.instanceLocale());
	}

	private static URI parse(String url) {
		try {
			URI uri = url == null || url.isBlank() ? null : URI.create(url.strip());
			return uri == null || uri.getHost() == null ? null : uri;
		}
		catch (IllegalArgumentException malformed) {
			return null;
		}
	}

	private static boolean sameOrigin(URI a, URI b) {
		return b != null && a.getScheme().equalsIgnoreCase(b.getScheme())
				&& a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
	}

	private static int port(URI uri) {
		if (uri.getPort() >= 0) {
			return uri.getPort();
		}
		return "https".equals(uri.getScheme().toLowerCase(Locale.ROOT)) ? 443 : 80;
	}
}
