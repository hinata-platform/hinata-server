package com.ahmadre.hinata.media;

import com.ahmadre.hinata.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/**
 * Inline Markdown media: upload an image while authoring, and read it (or a
 * proxied external image) back as bytes through our own origin so it renders on
 * Flutter web without CORS problems. Every route is authenticated — media is
 * readable by any signed-in user but never anonymously.
 */
@Tag(name = "Media")
@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
public class MediaController {

	private final MediaService media;
	private final ExternalImageFetcher fetcher;

	@Operation(summary = "Upload an inline Markdown image")
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public MediaService.MediaUpload upload(@RequestParam("file") MultipartFile file) {
		return media.store(file);
	}

	@Operation(summary = "Read an uploaded inline image (authenticated)")
	@GetMapping("/{id:[0-9a-fA-F-]{36}}")
	public ResponseEntity<byte[]> get(@PathVariable String id) {
		return inline(media.load(id), CacheControl.maxAge(Duration.ofDays(30)).cachePrivate());
	}

	@Operation(summary = "Proxy an external image URL server-side (bypasses CORS)")
	@GetMapping("/proxy")
	public ResponseEntity<byte[]> proxy(@RequestParam("url") String url) {
		return inline(fetcher.fetch(url), CacheControl.maxAge(Duration.ofDays(1)).cachePrivate());
	}

	/**
	 * Serves image bytes inline with the validated content type. {@code nosniff}
	 * stops a browser from re-interpreting the payload as anything other than the
	 * declared image type (defence against content-sniffing / stored XSS).
	 */
	private ResponseEntity<byte[]> inline(StorageService.StoredObject object, CacheControl cache) {
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(object.contentType()))
				.cacheControl(cache)
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
				.header("X-Content-Type-Options", "nosniff")
				.body(object.data());
	}
}
