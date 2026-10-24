package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.PublicPage;
import com.ahmadre.hinata.config.ClientIpResolver;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.moderation.rules.HouseRulesService;
import com.ahmadre.hinata.moderation.rules.RulesVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Locale;
import java.util.Optional;

/**
 * What answers without an account (HIN-175): the house rules, any version of them, and the form
 * for reports (DSA Art. 14 and 16). Both are listed, with their reasons, in
 * {@code UnauthenticatedSurfaceTest}.
 */
@Tag(name = "Moderation")
@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
class PublicModerationController {

	private final HouseRulesService rules;
	private final PublicForms forms;
	private final PublicReports reports;
	private final PublicModerationPages pages;
	private final ClientIpResolver clientIp;

	@Operation(summary = "The house rules of this instance, or one version of them, as a page")
	@SecurityRequirements
	@GetMapping(value = "/rules", produces = MediaType.TEXT_HTML_VALUE)
	ResponseEntity<String> rules(@RequestParam(required = false) Integer version,
			@RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
		Locale locale = PublicPage.localeOf(acceptLanguage);
		RulesVersion shown = version == null ? rules.latest() : rules.version(version);
		return shown == null ? PublicPage.page(HttpStatus.NOT_FOUND, pages.noSuchVersion(locale))
				: PublicPage.page(pages.rules(locale, shown));
	}

	@Operation(summary = "The form for reports from people without an account")
	@SecurityRequirements
	@GetMapping(value = "/report", produces = MediaType.TEXT_HTML_VALUE)
	ResponseEntity<String> form(@RequestParam(required = false) String location,
			@RequestHeader(name = "Accept-Language", required = false) String acceptLanguage, HttpServletRequest http) {
		return PublicPage.page(pages.form(PublicPage.localeOf(acceptLanguage), PublicModerationPages.Values.empty(location),
				forms.issue(clientIp.resolve(http)), null));
	}

	/**
	 * Files a report sent from the form. In this order: only from this page, a token this server
	 * made for this address and not used, every field right; then the token is used, the limits
	 * are counted with it, and the report filed. A filled-in trap field looks done and files nothing.
	 */
	@Operation(summary = "Send a report from the form for people without an account")
	@SecurityRequirements
	@PostMapping(value = "/report", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
			produces = MediaType.TEXT_HTML_VALUE)
	ResponseEntity<String> submit(@RequestParam MultiValueMap<String, String> form,
			@RequestHeader(name = "Accept-Language", required = false) String acceptLanguage, HttpServletRequest http) {
		Locale locale = PublicPage.localeOf(acceptLanguage);
		if (!fromThisPage(http)) {
			return PublicPage.page(HttpStatus.FORBIDDEN, pages.foreign(locale));
		}
		String address = clientIp.resolve(http);
		PublicModerationPages.Values values = new PublicModerationPages.Values(form.getFirst("location"),
				form.getFirst("category"), form.getFirst("explanation"), form.getFirst("name"), form.getFirst("email"),
				"true".equals(form.getFirst("goodFaith")));
		String token = form.getFirst("token");
		switch (forms.check(token, address)) {
			case INVALID -> {
				return PublicPage.page(pages.form(locale, values, forms.issue(address),
						"web.moderation.report.error.expired"));
			}
			case TOO_YOUNG -> {
				return PublicPage.page(pages.form(locale, values, forms.issue(address),
						"web.moderation.report.error.tooFast"));
			}
			case GOOD -> {
				// On to the rest.
			}
		}
		String trap = form.getFirst("website");
		if (trap != null && !trap.isEmpty()) {
			forms.use(token, address);
			return PublicPage.page(pages.done(locale));
		}
		PublicReports.Submission submission = new PublicReports.Submission(values.location(), categoryOf(values.category()),
				values.explanation(), values.name(), values.email(), values.goodFaith());
		Optional<String> problem = reports.problemWith(submission);
		if (problem.isPresent()) {
			// The token stays good: nothing was sent yet.
			return PublicPage.page(pages.form(locale, values, token, problem.get()));
		}
		if (!forms.use(token, address)) {
			return PublicPage.page(pages.form(locale, values, forms.issue(address), "web.moderation.report.error.expired"));
		}
		// Counted after the token is used, and the mailbox in one step: forms sent at the same
		// moment cannot all pass a limit each read before the others counted.
		if (forms.overLimit(address)
				|| !forms.reserveEmail(PublicReports.countedEmail(submission.email()), PublicReports.PER_EMAIL_PER_DAY)) {
			return PublicPage.page(pages.limited(locale));
		}
		reports.file(submission, locale);
		return PublicPage.page(pages.done(locale));
	}

	/**
	 * Whether the form was sent from a page of this server: {@code Sec-Fetch-Site} where the
	 * browser sends it, else an {@code Origin} that is this server's. A request with neither is no
	 * browser's form.
	 */
	private static boolean fromThisPage(HttpServletRequest http) {
		String site = http.getHeader("Sec-Fetch-Site");
		if (site != null) {
			return "same-origin".equalsIgnoreCase(site);
		}
		String origin = http.getHeader("Origin");
		if (origin == null) {
			return false;
		}
		String own = ServletUriComponentsBuilder.fromContextPath(http).replacePath(null).replaceQuery(null)
				.build().toUriString();
		return origin.equalsIgnoreCase(own);
	}

	private static ReportCategory categoryOf(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return ReportCategory.valueOf(value);
		}
		catch (IllegalArgumentException unknown) {
			return null;
		}
	}
}
