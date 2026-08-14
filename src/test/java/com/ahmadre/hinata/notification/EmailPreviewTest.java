package com.ahmadre.hinata.notification;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes a browsable gallery of every transactional mail to
 * {@code build/email-preview/index.html} — the way these designs get reviewed
 * without a running server or an SMTP round-trip.
 *
 * <pre>
 *   ./gradlew emailPreview
 *   open build/email-preview/index.html
 * </pre>
 *
 * Each mail is rendered four ways: German and English × light and dark. The dark
 * pass is not a re-style — it rewrites the template's own
 * {@code @media (prefers-color-scheme: dark)} guard to {@code @media all}, so
 * what the gallery shows is literally the CSS an Apple Mail or Outlook reader in
 * dark mode will apply, not a preview-only approximation.
 */
class EmailPreviewTest {

	private static final Path OUT = Path.of("build", "email-preview");

	private static final List<String> LOCALES = List.of("de", "en");

	private final SpringTemplateEngine engine = EmailFixtures.engine();

	@Test
	void writesTheGallery() throws IOException {
		Files.createDirectories(OUT);
		copyMasthead();

		List<String> written = new ArrayList<>();
		for (String template : EmailFixtures.TEMPLATES) {
			for (String locale : LOCALES) {
				String html = render(template, locale);
				write(name(template, locale, "light"), html);
				write(name(template, locale, "dark"), darken(html));
				written.add(name(template, locale, "light"));
			}
		}
		Files.writeString(OUT.resolve("index.html"), gallery(), StandardCharsets.UTF_8);

		assertThat(written).hasSize(EmailFixtures.TEMPLATES.size() * LOCALES.size());
		System.out.println("\n  E-mail preview → "
				+ OUT.resolve("index.html").toAbsolutePath().normalize() + "\n");
	}

	private String render(String template, String locale) {
		Map<String, Object> model = EmailFixtures.model(template, locale);
		// A browser cannot resolve cid:, so the gallery reads the band off disk.
		// Everything else — copy, locale, layout — is byte-identical to a send.
		String variant = EmailFixtures.mastheadVariant(template);
		model.put("mastheadSrc", variant != null ? "masthead-" + variant + ".jpg" : "masthead.jpg");
		model.put("mastheadHeight", variant != null ? 200 : 118);
		return engine.process(template, new Context(Locale.forLanguageTag(locale), model));
	}

	/**
	 * Turns the conditional dark block into an unconditional one. Narrow on
	 * purpose: rewriting only the guard keeps the previewed rules identical to
	 * the shipped ones, so the gallery cannot drift from the real thing.
	 */
	private String darken(String html) {
		return html.replace("@media (prefers-color-scheme: dark) {", "@media all {");
	}

	private void copyMasthead() throws IOException {
		for (String art : List.of("masthead.jpg", "masthead-welcome.jpg", "masthead-invite.jpg")) {
			try (InputStream in = new ClassPathResource("email/" + art).getInputStream()) {
				Files.write(OUT.resolve(art), in.readAllBytes());
			}
		}
	}

	private void write(String file, String html) throws IOException {
		Files.writeString(OUT.resolve(file), html, StandardCharsets.UTF_8);
	}

	private static String name(String template, String locale, String theme) {
		return template.substring(template.indexOf('/') + 1) + "." + locale + "." + theme + ".html";
	}

	// ---- the gallery page ---------------------------------------------------

	private String gallery() {
		StringBuilder cards = new StringBuilder();
		for (String template : EmailFixtures.TEMPLATES) {
			String id = template.substring(template.indexOf('/') + 1);
			cards.append("""
					  <section class="card">
					    <header><h2>%s</h2><code>templates/%s.html</code></header>
					    <div class="frame"><iframe data-mail="%s" title="%s" src="%s.de.light.html"></iframe></div>
					  </section>
					""".formatted(id, template, id, id, id));
		}
		return GALLERY.replace("<!--CARDS-->", cards.toString())
				.replace("<!--COUNT-->", String.valueOf(EmailFixtures.TEMPLATES.size()));
	}

	private static final String GALLERY = """
			<!DOCTYPE html>
			<html lang="en">
			<head>
			<meta charset="UTF-8"/>
			<meta name="viewport" content="width=device-width,initial-scale=1"/>
			<title>Hinata · e-mail preview</title>
			<style>
			  :root{--canvas:#F4F3EF;--card:#fff;--line:#E7E5DE;--ink:#23223F;--soft:#6B6A85;
			        --faint:#9A99B0;--accent:#B9831F;--rail:#211F3D}
			  *{box-sizing:border-box}
			  body{margin:0;background:var(--canvas);color:var(--ink);
			       font:15px/1.5 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}
			  .top{position:sticky;top:0;z-index:9;background:var(--rail);color:#F4F3EF;
			       padding:14px 22px;display:flex;gap:22px;align-items:center;flex-wrap:wrap;
			       border-bottom:3px solid #D9A032}
			  .top h1{margin:0;font-size:15px;font-weight:700;letter-spacing:-.2px}
			  .top .sub{color:#A8A6C2;font-size:12.5px}
			  .seg{display:flex;border:1px solid #4A4870;border-radius:999px;overflow:hidden}
			  .seg button{appearance:none;border:0;background:transparent;color:#C9C7E0;cursor:pointer;
			              padding:6px 14px;font:inherit;font-size:12.5px;font-weight:600}
			  .seg button[aria-pressed=true]{background:#D9A032;color:#23223F}
			  .grid{display:grid;gap:22px;padding:22px;
			        grid-template-columns:repeat(auto-fill,minmax(min(100%,var(--w,660px)),1fr))}
			  .card{background:var(--card);border:1px solid var(--line);border-radius:14px;overflow:hidden}
			  .card header{padding:13px 16px;border-bottom:1px solid var(--line);
			               display:flex;justify-content:space-between;align-items:baseline;gap:12px}
			  .card h2{margin:0;font-size:14.5px;font-weight:700}
			  .card code{color:var(--faint);font-size:11.5px;font-family:ui-monospace,Menlo,monospace}
			  .frame{background:var(--canvas)}
			  iframe{display:block;width:100%;height:var(--h,900px);border:0}
			  @media (prefers-color-scheme:dark){
			    :root{--canvas:#131119;--card:#1C1B25;--line:#2E2D3B;--ink:#ECEBF3;--soft:#A8A6C2;--faint:#6F6D88}
			  }
			</style>
			</head>
			<body>
			<div class="top">
			  <h1>Hinata · e-mail preview</h1>
			  <span class="sub"><!--COUNT--> templates · de/en · light/dark</span>
			  <div class="seg" id="locale"><button data-v="de" aria-pressed="true">Deutsch</button
			    ><button data-v="en" aria-pressed="false">English</button></div>
			  <div class="seg" id="theme"><button data-v="light" aria-pressed="true">Light</button
			    ><button data-v="dark" aria-pressed="false">Dark</button></div>
			  <div class="seg" id="width"><button data-v="desktop" aria-pressed="true">Desktop</button
			    ><button data-v="mobile" aria-pressed="false">Mobile 390</button></div>
			</div>
			<div class="grid"><!--CARDS--></div>
			<script>
			  const state = {locale:'de', theme:'light', width:'desktop'};
			  function apply(){
			    for (const f of document.querySelectorAll('iframe'))
			      f.src = `${f.dataset.mail}.${state.locale}.${state.theme}.html`;
			    document.documentElement.style.setProperty('--w', state.width==='mobile'?'420px':'660px');
			  }
			  for (const group of ['locale','theme','width'])
			    document.getElementById(group).addEventListener('click', e => {
			      const b = e.target.closest('button'); if (!b) return;
			      state[group] = b.dataset.v;
			      for (const s of e.currentTarget.children) s.setAttribute('aria-pressed', s === b);
			      apply();
			    });
			  // Grow every frame to its content so nothing is cut off mid-mail.
			  addEventListener('load', () => setInterval(() => {
			    for (const f of document.querySelectorAll('iframe')) {
			      try { f.style.height = (f.contentDocument.body.scrollHeight + 24) + 'px'; }
			      catch (_) { /* not loaded yet */ }
			    }
			  }, 400));
			  apply();
			</script>
			</body>
			</html>
			""";
}
