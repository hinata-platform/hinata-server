package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.PublicPage;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.moderation.rules.HouseRulesService;
import com.ahmadre.hinata.moderation.rules.RulesVersion;
import com.ahmadre.hinata.setup.BrandLogoService;
import com.ahmadre.hinata.setup.OperatorContact;
import com.ahmadre.hinata.setup.SettingsService;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The pages people without an account see of moderation (HIN-175): the house rules, the form for
 * reports and what follows it. No script, the shared policy and frame ({@link PublicPage}), and
 * nothing interpolated that did not go through the escaper or the Markdown renderer, which escapes
 * raw HTML, drops images and keeps only links to {@code http}, {@code https} and {@code mailto}.
 */
@Component
@RequiredArgsConstructor
class PublicModerationPages {

	private static final String PRODUCT_NAME = "hinata";

	/** The Markdown of the rules, with raw HTML escaped and every other kind of link shown as text. */
	private static final MutableDataSet MARKDOWN = new MutableDataSet()
			.set(Parser.EXTENSIONS, List.of(TablesExtension.create(), StrikethroughExtension.create()))
			.set(HtmlRenderer.ESCAPE_HTML, true)
			.set(HtmlRenderer.SUPPRESSED_LINKS, "(?is)(?!(https?|mailto):).*");
	private static final Parser PARSER = Parser.builder(MARKDOWN).build();
	private static final HtmlRenderer RENDERER = HtmlRenderer.builder(MARKDOWN).build();

	private static final String LABEL = "display:block;color:#23223F;font-size:14px;font-weight:600;margin:16px 0 6px";
	private static final String FIELD = "width:100%;box-sizing:border-box;padding:12px;border:1px solid #CFCCC2;"
			+ "border-radius:10px;font-size:15px;font-family:inherit";
	private static final String HINT = "color:#6B6A85;font-size:13px;line-height:1.5;margin:6px 0 0";

	private final MessageSource messages;
	private final SettingsService settings;
	private final BrandLogoService brandLogo;
	private final OperatorContact operatorContact;

	/** What somebody entered, sent back into the form when something has to be put right. */
	record Values(String location, String category, String explanation, String name, String email, boolean goodFaith) {

		static Values empty(String location) {
			return new Values(location, null, null, null, null, false);
		}
	}

	String form(Locale locale, Values values, String token, String problemKey) {
		StringBuilder body = new StringBuilder();
		body.append(heading(t(locale, "web.moderation.report.heading")));
		body.append(paragraph(t(locale, "web.moderation.report.intro", operator())));
		if (problemKey != null) {
			body.append("<p role=\"alert\" style=\"background:#FBE9E7;color:#7A1F12;border-radius:10px;padding:12px;"
					+ "font-size:14px;line-height:1.5;margin:16px 0 0\">")
					.append(escape(problemKey.equals(PublicReports.EXPLANATION_PROBLEM)
							? t(locale, problemKey, ContentReportService.EXPLANATION_MIN) : t(locale, problemKey)))
					.append("</p>");
		}
		body.append("<form method=\"post\" action=\"/api/v1/public/report\">");
		body.append(hidden("token", token));
		body.append(label("location", t(locale, "web.moderation.report.location")));
		body.append("<input id=\"location\" name=\"location\" required maxlength=\"").append(PublicReports.LOCATION_MAX)
				.append("\" value=\"").append(escape(values.location())).append("\" style=\"").append(FIELD)
				.append("\" aria-describedby=\"location-hint\"/>");
		body.append(hint("location-hint", t(locale, "web.moderation.report.locationHint")));
		body.append(label("category", t(locale, "web.moderation.report.category")));
		body.append("<select id=\"category\" name=\"category\" required style=\"").append(FIELD).append("\">");
		body.append("<option value=\"\">").append(escape(t(locale, "web.moderation.report.choose"))).append("</option>");
		for (ReportCategory category : ReportCategory.values()) {
			body.append("<option value=\"").append(category.name()).append('"')
					.append(category.name().equals(values.category()) ? " selected" : "").append('>')
					.append(escape(t(locale, "moderation.category." + category.name()))).append("</option>");
		}
		body.append("</select>");
		body.append(label("explanation", t(locale, "web.moderation.report.explanation")));
		body.append("<textarea id=\"explanation\" name=\"explanation\" rows=\"5\" maxlength=\"")
				.append(ContentReport.EXPLANATION_MAX_PUBLIC).append("\" style=\"").append(FIELD)
				.append("\" aria-describedby=\"explanation-hint\">").append(escape(values.explanation()))
				.append("</textarea>");
		body.append(hint("explanation-hint", t(locale, "web.moderation.report.explanationHint",
				ContentReportService.EXPLANATION_MIN)));
		body.append(label("name", t(locale, "web.moderation.report.name")));
		body.append("<input id=\"name\" name=\"name\" maxlength=\"").append(PublicReports.NAME_MAX)
				.append("\" autocomplete=\"name\" value=\"").append(escape(values.name())).append("\" style=\"")
				.append(FIELD).append("\"/>");
		body.append(label("email", t(locale, "web.moderation.report.email")));
		body.append("<input id=\"email\" name=\"email\" type=\"email\" maxlength=\"").append(PublicReports.EMAIL_MAX)
				.append("\" autocomplete=\"email\" value=\"").append(escape(values.email())).append("\" style=\"")
				.append(FIELD).append("\" aria-describedby=\"email-hint\"/>");
		body.append(hint("email-hint", t(locale, "web.moderation.report.emailHint") + " "
				+ t(locale, "web.moderation.report.childSafetyHint")));
		// Filled in only by something that fills in every field; a person never sees it.
		body.append("<div aria-hidden=\"true\" style=\"position:absolute;left:-10000px;top:auto;width:1px;height:1px;"
				+ "overflow:hidden\"><label>Website<input name=\"website\" tabindex=\"-1\" autocomplete=\"off\"/></label></div>");
		body.append("<label style=\"display:flex;gap:10px;align-items:flex-start;margin:18px 0 0;font-size:15px;"
				+ "line-height:1.45;color:#23223F\"><input type=\"checkbox\" name=\"goodFaith\" value=\"true\" required")
				.append(values.goodFaith() ? " checked" : "").append(" style=\"width:20px;height:20px;margin-top:1px\"/>")
				.append(escape(t(locale, "web.moderation.report.goodFaith"))).append("</label>");
		body.append("<button type=\"submit\" style=\"width:100%;margin-top:20px;padding:14px;background:#2D2B55;color:#fff;"
				+ "border:0;border-radius:10px;font-size:15px;font-weight:600;cursor:pointer\">")
				.append(escape(t(locale, "web.moderation.report.submit"))).append("</button>");
		body.append("</form>");
		body.append("<p style=\"margin:18px 0 0;font-size:14px\"><a href=\"").append(HouseRulesService.PUBLIC_RULES_PATH)
				.append("\" style=\"color:#2D2B55\">").append(escape(t(locale, "web.moderation.report.rules")))
				.append("</a></p>");
		return PublicPage.frame(locale, t(locale, "web.moderation.report.pageTitle"), brand(), body.toString(), 560);
	}

	String done(Locale locale) {
		return message(locale, "web.moderation.report.doneTitle", t(locale, "web.moderation.report.doneBody"));
	}

	/** At a limit: the way to the moderation stays open, by mail (DSA Art. 16). */
	String limited(Locale locale) {
		String email = operatorContact.current().contactEmail();
		return message(locale, "web.moderation.report.limitTitle", email == null
				? t(locale, "web.moderation.report.limitBodyLater") : t(locale, "web.moderation.report.limitBody", email));
	}

	String foreign(Locale locale) {
		return message(locale, "web.moderation.report.foreignTitle", t(locale, "web.moderation.report.foreignBody"));
	}

	String noSuchVersion(Locale locale) {
		return message(locale, "web.moderation.rules.notFoundTitle", t(locale, "web.moderation.rules.notFoundBody"));
	}

	String rules(Locale locale, RulesVersion version) {
		String markdown = version.renderedIn(locale.getLanguage());
		String since = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
				.format(version.getCreatedAt().atZone(ZoneOffset.UTC));
		String body = "<style>.rules h1{color:#23223F;font-size:22px;margin:0 0 12px}"
				+ ".rules h2{color:#23223F;font-size:17px;margin:22px 0 8px}"
				+ ".rules p,.rules li{color:#3F3E5A;font-size:15px;line-height:1.6}.rules a{color:#2D2B55}</style>"
				+ "<div class=\"rules\">" + markdownToHtml(markdown) + "</div>"
				+ "<p style=\"" + HINT + ";margin-top:24px\">"
				+ escape(t(locale, "web.moderation.rules.version", version.getVersion(), since)) + "</p>"
				+ "<p style=\"margin:12px 0 0;font-size:15px\"><a href=\"" + HouseRulesService.PUBLIC_REPORT_PATH
				+ "\" style=\"color:#2D2B55;font-weight:600\">" + escape(t(locale, "web.moderation.rules.report"))
				+ "</a></p>";
		return PublicPage.frame(locale, t(locale, "web.moderation.rules.pageTitle"), brand(), body, 680);
	}

	/** [markdown] as HTML: raw HTML escaped, images reduced to their text, unsafe links to their text. */
	static String markdownToHtml(String markdown) {
		Node document = PARSER.parse(markdown == null ? "" : markdown);
		List<Image> images = new ArrayList<>();
		for (Node node : document.getDescendants()) {
			if (node instanceof Image image) {
				images.add(image);
			}
		}
		for (Image image : images) {
			image.insertBefore(new Text(image.getText()));
			image.unlink();
		}
		return RENDERER.render(document);
	}

	private String message(Locale locale, String titleKey, String text) {
		String title = t(locale, titleKey);
		return PublicPage.frame(locale, title, brand(), heading(title) + paragraph(text), 460);
	}

	private PublicPage.Brand brand() {
		try {
			String name = settings.get().getOrganizationName();
			return new PublicPage.Brand(name == null || name.isBlank() ? PRODUCT_NAME : name.strip(),
					brandLogo.display().isPresent());
		}
		catch (RuntimeException unreachable) {
			return new PublicPage.Brand(PRODUCT_NAME, false);
		}
	}

	private String operator() {
		String operator = operatorContact.current().operatorName();
		return operator != null ? operator : brand().name();
	}

	private static String heading(String text) {
		return "<h1 style=\"color:#23223F;font-size:22px;margin:0 0 12px\">" + escape(text) + "</h1>";
	}

	private static String paragraph(String text) {
		return "<p style=\"color:#6B6A85;font-size:15px;line-height:1.6;margin:0\">" + escape(text) + "</p>";
	}

	private static String label(String field, String text) {
		return "<label for=\"" + field + "\" style=\"" + LABEL + "\">" + escape(text) + "</label>";
	}

	private static String hint(String id, String text) {
		return "<p id=\"" + id + "\" style=\"" + HINT + "\">" + escape(text) + "</p>";
	}

	private static String hidden(String name, String value) {
		return "<input type=\"hidden\" name=\"" + name + "\" value=\"" + escape(value) + "\"/>";
	}

	private String t(Locale locale, String key, Object... args) {
		return messages.getMessage(key, args.length == 0 ? null : args, key, locale);
	}

	private static String escape(String value) {
		return PublicPage.escape(value);
	}
}
