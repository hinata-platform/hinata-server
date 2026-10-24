package com.ahmadre.hinata.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * The few pages the server renders itself, for people who are not in the app: the pages a mailed
 * link lands on, the house rules and the form for reports without an account (HIN-175). One
 * policy for all of them, one escaper and one frame, so a new page cannot bring a weaker one.
 */
public final class PublicPage {

	private PublicPage() {
	}

	/** The languages these pages are translated into. */
	public static final Set<String> LOCALES = Set.of("en", "de", "zh", "hi", "es", "ja", "fr", "ru", "ar");

	/** Languages written right to left. */
	private static final Set<String> RIGHT_TO_LEFT = Set.of("ar");

	/**
	 * The policy these pages need, and the reason they are wrapped in a {@link ResponseEntity} at
	 * all. The chain-wide header is {@code default-src 'none'}: exactly right for a JSON API, but it
	 * also blocks the inline styling these pages are made of and the organization logo they carry.
	 * Spring Security's header writers leave a header the handler already set alone, so this
	 * narrower policy replaces it for these responses only: still no script, no framing, no
	 * base-URI games, images only from this server, and a form that can post nowhere but back to
	 * us.
	 */
	public static final String CSP = "default-src 'none'; img-src 'self'; "
			+ "style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; "
			+ "frame-ancestors 'none'";

	/** Serves a rendered page as UTF-8 HTML under {@link #CSP}. */
	public static ResponseEntity<String> page(String html) {
		return page(HttpStatus.OK, html);
	}

	public static ResponseEntity<String> page(HttpStatus status, String html) {
		return ResponseEntity.status(status)
				.contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
				.header("Content-Security-Policy", CSP)
				.body(html);
	}

	/**
	 * The language of a page nobody opened from the app: the browser's {@code Accept-Language} is
	 * the only signal there is. Just the primary tag of the first entry, so "de-AT,de;q=0.9" is
	 * German; anything the server does not speak is English.
	 */
	public static Locale localeOf(String acceptLanguage) {
		if (acceptLanguage == null) {
			return Locale.ENGLISH;
		}
		String tag = acceptLanguage.split(",")[0].trim().split("[-;]")[0].toLowerCase(Locale.ROOT);
		return LOCALES.contains(tag) ? Locale.forLanguageTag(tag) : Locale.ENGLISH;
	}

	/**
	 * Which way [locale] runs. These pages are plain hand-written HTML with no layout engine to
	 * ask; without the attribute, Arabic would be laid out left-aligned with its punctuation on the
	 * wrong side.
	 */
	public static String direction(Locale locale) {
		return RIGHT_TO_LEFT.contains(locale.getLanguage()) ? "rtl" : "ltr";
	}

	/**
	 * Everything interpolated into these pages goes through here. Escaped for a UTF-8 document,
	 * which is what they declare and send: the same five characters that can break out of text or
	 * a quoted attribute, while German copy stays readable text instead of entity soup.
	 */
	public static String escape(String value) {
		return value == null ? "" : HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
	}

	/** What goes at the top of a page: the organization's name, and whether its logo can be shown. */
	public record Brand(String name, boolean logo) {
	}

	/**
	 * The organization's logo through our own {@code /api/v1/meta/logo}, same-origin by
	 * construction, or its name where there is no logo to show. No {@code onerror}: inline script
	 * is not on this policy, and an alt text is the better failure.
	 */
	public static String masthead(Brand brand) {
		String mark = brand.logo()
				? "<img src=\"/api/v1/meta/logo\" alt=\"" + escape(brand.name())
						+ "\" style=\"max-height:32px;max-width:200px\"/>"
				: escape(brand.name());
		return "<div style=\"font-weight:800;color:#2D2B55;margin-bottom:20px\">" + mark + "</div>";
	}

	/**
	 * A whole page: [title] in the tab, the masthead, and [body], which is HTML the caller built
	 * from escaped parts. [width] is the card's widest, in pixels.
	 */
	public static String frame(Locale locale, String title, Brand brand, String body, int width) {
		return """
				<!doctype html><html lang="%s" dir="%s"><head><meta charset="utf-8"/>
				<meta name="viewport" content="width=device-width,initial-scale=1"/>
				<title>%s · %s</title></head>
				<body style="margin:0;font-family:-apple-system,'Segoe UI',Roboto,sans-serif;background:#F4F3EF">
				<div style="max-width:%dpx;margin:48px auto;background:#fff;border:1px solid #E7E5DE;border-radius:24px;overflow:hidden">
				<div style="height:4px;background:#D9A032"></div>
				<div style="padding:32px">
				%s
				%s
				</div></div></body></html>
				""".formatted(locale.getLanguage(), direction(locale), escape(title), escape(brand.name()), width,
				masthead(brand), body);
	}
}
