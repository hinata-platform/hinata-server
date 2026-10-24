package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.PublicPage;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.me.MeController;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.setup.SettingsService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The form for reports from people without an account (HIN-175, DSA Art. 16), as the browser
 * sends it: from this page only, with a token made for the address, never faster than a person,
 * within limits that show the contact instead of an error, and with a page that says the same
 * whatever the address pointed at.
 */
@Import(PublicReportIntegrationTest.Clocks.class)
class PublicReportIntegrationTest extends RemovalIntegrationSupport {

	private static final Pattern TOKEN = Pattern.compile("name=\"token\" value=\"([^\"]+)\"");
	private static final String DONE = "Thank you for your report";

	@TestConfiguration
	static class Clocks {
		@Bean
		@Primary
		PublicFormsTest.MovableClock movableClock() {
			return new PublicFormsTest.MovableClock(Instant.now());
		}
	}

	@Autowired
	PublicModerationController controller;
	@Autowired
	PublicFormsTest.MovableClock clock;
	@Autowired
	SettingsService settings;
	@Autowired
	MeController me;

	private int address;

	@BeforeEach
	void clean() {
		mongo.getCollection("public_form_tokens").deleteMany(new Document());
		contactIs(settings, "moderation@example.org");
		address = 1;
	}

	/** A browser at its own address, sending from this page. */
	private MockHttpServletRequest browser() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("198.51.100." + address);
		request.addHeader("Sec-Fetch-Site", "same-origin");
		return request;
	}

	private String tokenFrom(ResponseEntity<String> page) {
		Matcher matcher = TOKEN.matcher(page.getBody());
		assertThat(matcher.find()).as("a token in the form").isTrue();
		return matcher.group(1);
	}

	private String freshToken() {
		String token = tokenFrom(controller.form(null, "en", browser()));
		clock.advance(Duration.ofSeconds(4));
		return token;
	}

	private MultiValueMap<String, String> form(String token, String location, String category, String explanation,
			String email) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("token", token);
		form.add("location", location);
		form.add("category", category);
		form.add("explanation", explanation);
		form.add("name", "Kim");
		form.add("email", email);
		form.add("goodFaith", "true");
		return form;
	}

	private ResponseEntity<String> send(MultiValueMap<String, String> form) {
		return controller.submit(form, "en", browser());
	}

	private IssueComment commentOnAnIssue() {
		Issue issue = issue("Standaufbau", doc("Plan"));
		return issues.addComment(issue.getId(), new RichText(doc("Du Idiot"), "Du Idiot", List.of()), author);
	}

	@Test
	void aReportOfACommentLandsOnItAndShowsNothingOfItYet() {
		IssueComment comment = commentOnAnIssue();
		String readable = issues.get(comment.getIssueId()).getReadableId();
		String location = "https://localhost/issues/" + readable + "?comment=" + comment.getId();

		ResponseEntity<String> page = send(form(freshToken(), location, "HARASSMENT", "Beleidigung", "kim@example.org"));

		assertThat(page.getBody()).contains(DONE);
		ContentReport report = mongo.findOne(new org.springframework.data.mongodb.core.query.Query(), ContentReport.class);
		assertThat(report.getTargetKind()).isEqualTo(ContentKind.COMMENT);
		assertThat(report.getTargetId()).isEqualTo(comment.getId());
		assertThat(report.getSource()).isEqualTo(ReportSource.PUBLIC);
		assertThat(report.getReporterKey()).startsWith("public:");
		ModerationCase opened = mongo.findById(report.getCaseId(), ModerationCase.class);
		assertThat(opened.isEvidenceWithheld()).isTrue();
		assertThat(opened.getListExcerpt()).isNull();
		assertThat(opened.getLocation()).isEqualTo(location);
		// The receipt says it arrived and nothing of what was written.
		verify(mail, atLeastOnce()).sendNotification(eq("kim@example.org"), anyString(), anyString(),
				org.mockito.ArgumentMatchers.argThat(body -> body != null && !body.contains("Beleidigung")), any(),
				any(), any(), any());
	}

	@Test
	void anAddressOfAnythingElseIsSomethingElseAndThePageSaysTheSame() {
		ResponseEntity<String> foreign = send(form(freshToken(), "https://elsewhere.example/post/1", "SPAM", null, null));
		ResponseEntity<String> missing = send(form(freshToken(), "https://localhost/issues/ACME-999", "SPAM", null, null));

		assertThat(foreign.getBody()).contains(DONE);
		assertThat(missing.getBody()).isEqualTo(foreign.getBody());
		assertThat(mongo.findAll(ContentReport.class)).extracting(ContentReport::getTargetKind)
				.containsOnly(ContentKind.OTHER);
	}

	@Test
	void onlyThisPageMaySendTheForm() {
		MockHttpServletRequest elsewhere = new MockHttpServletRequest();
		elsewhere.setRemoteAddr("198.51.100.1");
		elsewhere.addHeader("Origin", "https://evil.example");
		MockHttpServletRequest nothing = new MockHttpServletRequest();
		nothing.setRemoteAddr("198.51.100.1");
		MockHttpServletRequest crossSite = new MockHttpServletRequest();
		crossSite.addHeader("Sec-Fetch-Site", "cross-site");

		for (MockHttpServletRequest request : List.of(elsewhere, nothing, crossSite)) {
			assertThat(controller.submit(form(freshToken(), "x", "SPAM", null, null), "en", request).getStatusCode())
					.isEqualTo(HttpStatus.FORBIDDEN);
		}
		MockHttpServletRequest ownOrigin = new MockHttpServletRequest();
		ownOrigin.setRemoteAddr("198.51.100.1");
		ownOrigin.addHeader("Origin", "http://localhost");
		assertThat(controller.submit(form(freshToken(), "x", "SPAM", null, null), "en", ownOrigin).getBody())
				.contains(DONE);
	}

	@Test
	void tooFastUsedTwiceOrCaughtInTheTrapFilesNothing() {
		String token = tokenFrom(controller.form(null, "en", browser()));
		assertThat(send(form(token, "x", "SPAM", null, null)).getBody()).contains("That was too fast");

		clock.advance(Duration.ofSeconds(4));
		assertThat(send(form(token, "x", "SPAM", null, null)).getBody()).contains(DONE);
		assertThat(send(form(token, "x", "SPAM", null, null)).getBody()).contains("The form has expired");

		MultiValueMap<String, String> trapped = form(freshToken(), "x", "SPAM", null, null);
		trapped.add("website", "https://spam.example");
		assertThat(send(trapped).getBody()).contains(DONE);

		assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(), ContentReport.class)).isEqualTo(1);
	}

	@Test
	void whatIsMissingIsSaidAndTheEntriesStay() {
		String token = freshToken();
		String page = send(form(token, " ", "SPAM", null, "kim@example.org")).getBody();
		assertThat(page).contains("Say where the content can be found.").contains("value=\"kim@example.org\"");

		page = send(form(token, "x", "ILLEGAL", "kurz", null)).getBody();
		assertThat(page).contains("at least 10 characters");

		page = send(form(token, "x", "SPAM", null, "not an address")).getBody();
		assertThat(page).contains("doesn&#39;t look right");
		// Nothing was sent, so the token is still good.
		assertThat(send(form(token, "x", "SPAM", null, null)).getBody()).contains(DONE);
	}

	@Test
	void aLimitShowsTheContactInsteadOfAnError() {
		for (int i = 0; i < PublicReports.PER_EMAIL_PER_DAY; i++) {
			address = 10 + i;
			assertThat(send(form(freshToken(), "x", "SPAM", null, "kim+" + i + "@example.org")).getBody()).contains(DONE);
		}
		address = 20;
		String limited = send(form(freshToken(), "x", "SPAM", null, "KIM@example.org")).getBody();
		assertThat(limited).contains("A lot of reports are coming in right now").contains("moderation@example.org");

		address = 30;
		for (int i = 0; i < PublicForms.PER_ADDRESS_PER_HOUR; i++) {
			assertThat(send(form(freshToken(), "x", "SPAM", null, null)).getBody()).contains(DONE);
		}
		assertThat(send(form(freshToken(), "x", "SPAM", null, null)).getBody()).contains("moderation@example.org");
		verify(mail, never()).sendNotification(eq("kim@example.org"), anyString(), anyString(), anyString(), any(),
				any(), any(), any());
	}

	/** One policy for every page the server renders itself. */
	@Test
	void thePagesShareOnePolicy() {
		assertThat(controller.form(null, "en", browser()).getHeaders().getFirst("Content-Security-Policy"))
				.isEqualTo(PublicPage.CSP);
		assertThat(controller.rules(null, "en").getHeaders().getFirst("Content-Security-Policy")).isEqualTo(PublicPage.CSP);
		assertThat(me.passwordResetForm("token", "en").getHeaders().getFirst("Content-Security-Policy"))
				.isEqualTo(PublicPage.CSP);
	}
}
