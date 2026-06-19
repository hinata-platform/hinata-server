package com.ahmadre.hinata.notification;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ensures the account-lifecycle e-mail templates parse and render for both
 * locales using the same Spring/SpEL Thymeleaf engine the application uses.
 */
class EmailTemplateRenderTest {

	private final SpringTemplateEngine engine = engine();

	private static SpringTemplateEngine engine() {
		GenericApplicationContext context = new GenericApplicationContext();
		context.refresh();
		var resolver = new SpringResourceTemplateResolver();
		resolver.setApplicationContext(context);
		resolver.setPrefix("classpath:/templates/");
		resolver.setSuffix(".html");
		resolver.setTemplateMode(TemplateMode.HTML);
		resolver.setCharacterEncoding("UTF-8");
		var engine = new SpringTemplateEngine();
		engine.setTemplateResolver(resolver);
		return engine;
	}

	@Test
	void rendersAllAccountTemplatesInBothLocales() {
		String[] templates = {
				"email/account-activated", "email/account-deactivated",
				"email/account-role-changed", "email/account-deleted"
		};
		for (String template : templates) {
			for (String locale : new String[] { "de", "en" }) {
				Map<String, Object> model = new HashMap<>();
				model.put("displayName", "Ada Lovelace");
				model.put("locale", locale);
				model.put("ctaLink", "https://hinata.example/login");
				model.put("isAdmin", true);
				model.put("roles", "Administrator, Member");

				String html = engine.process(template, new Context(null, model));

				assertThat(html).contains("Ada Lovelace").contains("Hinata");
			}
		}
	}
}
