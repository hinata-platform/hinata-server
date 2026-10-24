package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentCopies;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentSurface;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every stored field that can hold words is reviewed (HIN-169). Three rounds of patching found
 * the next leak each time; only counting every place content can be copied to showed the whole
 * surface. A new {@code @Document} or text field fails here until somebody decides what removing
 * content does about it, in {@link UgcInventory} and {@code hin-26-design/ugc-inventory.md}.
 */
class UgcInventoryTest extends RemovalIntegrationSupport {

	@Autowired
	private ApplicationContext context;

	@Test
	void everyStoredTextFieldIsReviewedAndNoReviewIsStale() {
		TreeSet<String> found = UgcFields.pathsOf(UgcFields.documents());

		assertThat(without(found, UgcInventory.REVIEWED.keySet()))
				.as("text fields with no line in UgcInventory.REVIEWED; decide what removing content does "
						+ "about them (hin-26-design/ugc-inventory.md)")
				.isEmpty();
		assertThat(without(UgcInventory.REVIEWED.keySet(), found))
				.as("lines in UgcInventory.REVIEWED for fields that are gone")
				.isEmpty();
	}

	@Test
	void everyKindOfContentHasItsSurfaceAndEveryCopyItsScrubber() {
		Set<ContentKind> kinds = surfaces.stream().map(ContentSurface::kind).collect(Collectors.toSet());
		Set<String> scrubbers = context.getBeansOfType(ContentCopies.class).values().stream()
				.map(bean -> AopUtils.getTargetClass(bean).getSimpleName())
				.collect(Collectors.toSet());

		for (UgcInventory.Classification classification : UgcInventory.REVIEWED.values()) {
			if (classification instanceof UgcInventory.Content content) {
				assertThat(kinds).as("a surface for %s", content.kind()).contains(content.kind());
			}
			if (classification instanceof UgcInventory.Copy copy) {
				assertThat(scrubbers).as("the scrubber %s", copy.by()).contains(copy.by());
			}
		}
		assertThat(kinds).containsExactlyInAnyOrder(ContentKind.ISSUE, ContentKind.COMMENT, ContentKind.ATTACHMENT,
				ContentKind.ARTICLE, ContentKind.PERSON);
	}

	@Test
	void aFieldNobodyReviewedIsCaught() {
		TreeSet<String> probe = UgcFields.pathsOf(List.of(UgcInventoryProbe.class));

		assertThat(probe).contains("ugc_probe.words");
		assertThat(without(probe, UgcInventory.REVIEWED.keySet())).contains("ugc_probe.words");
	}

	private static Set<String> without(Set<String> these, Set<String> those) {
		TreeSet<String> rest = new TreeSet<>(these);
		rest.removeAll(those);
		return rest;
	}
}
