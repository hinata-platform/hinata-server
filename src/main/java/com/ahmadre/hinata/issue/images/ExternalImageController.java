package com.ahmadre.hinata.issue.images;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * An external image of an issue's texts, through this server (HIN-181): the app shows a mail's
 * images from here, never from the sender's server, and only after its reader chose to load them.
 */
@Tag(name = "Issues")
@RestController
@RequiredArgsConstructor
public class ExternalImageController {

	private final ExternalImageService images;
	private final CurrentUser currentUser;

	@Operation(summary = "An external image of an issue's description or comments, loaded by the server")
	@GetMapping("/api/v1/issues/{idOrKey}/external-image")
	public ResponseEntity<byte[]> image(@PathVariable String idOrKey, @RequestParam String src) {
		ExternalImageService.ExternalImage image = images.load(idOrKey, src, currentUser.require());
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(image.contentType()))
				.cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
				.header("X-Content-Type-Options", "nosniff")
				.header("Content-Security-Policy", "default-src 'none'; sandbox")
				.body(image.data());
	}
}
