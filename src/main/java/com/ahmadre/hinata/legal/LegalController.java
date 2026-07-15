package com.ahmadre.hinata.legal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Public read access to the legal documents (privacy policy / terms of
 * service). Unauthenticated: the app links these from the logged-out login
 * screen and the web routes /privacy-policy + /terms-of-service must resolve
 * before any sign-in. Content is operator-replaceable markdown (object storage
 * with a bundled classpath fallback — see {@link LegalService}).
 */
@Tag(name = "Legal")
@RestController
@RequiredArgsConstructor
public class LegalController {

	private final LegalService legal;

	/** JSON shape served to the app. */
	public record LegalResponse(String type, String lang, String markdown, Instant updatedAt) {
	}

	@Operation(summary = "Fetch a legal document as markdown (public)")
	@SecurityRequirements
	@GetMapping("/api/v1/legal/{type}")
	public ResponseEntity<LegalResponse> get(
			@PathVariable String type,
			@RequestParam(required = false) String lang,
			@RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
		String effectiveLang = lang != null && !lang.isBlank() ? lang : primaryLanguage(acceptLanguage);
		LegalService.LegalContent content = legal.get(type, effectiveLang);
		return ResponseEntity.ok()
				// Short public cache: legal text changes rarely, but an operator
				// swap should still propagate within minutes.
				.cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
				.body(new LegalResponse(content.type(), content.lang(), content.markdown(),
						content.updatedAt()));
	}

	/** First language tag of an Accept-Language header ("de-DE,de;q=0.9" → "de"). */
	private static String primaryLanguage(String acceptLanguage) {
		if (acceptLanguage == null || acceptLanguage.isBlank()) {
			return "";
		}
		String first = acceptLanguage.split(",")[0].trim();
		int dash = first.indexOf('-');
		return (dash > 0 ? first.substring(0, dash) : first).toLowerCase(Locale.ROOT);
	}
}
