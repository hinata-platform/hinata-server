package com.ahmadre.hinata.article;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Labels arrive from the app and from MCP tools; whatever comes in is stored tidy and bounded. */
class ArticleLabelsTest {

	@Test
	void labelsAreTrimmedCollapsedAndDeduplicated() {
		assertThat(ArticleService.labels(Arrays.asList("  howto ", "how  to", "howto", "", null, " ")))
				.containsExactly("howto", "how to");
	}

	@Test
	void labelsAreBoundedInNumberAndLength() {
		List<String> many = new ArrayList<>();
		for (int i = 0; i < 50; i++) many.add("label-" + i);
		assertThat(ArticleService.labels(many)).hasSize(ArticleService.MAX_LABELS);
		assertThat(ArticleService.labels(List.of("x".repeat(100))).getFirst()).hasSize(ArticleService.MAX_LABEL_LENGTH);
	}

	@Test
	void noLabelsIsAnEmptyList() {
		assertThat(ArticleService.labels(null)).isEmpty();
	}
}
