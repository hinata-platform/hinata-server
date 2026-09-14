package com.ahmadre.hinata.common;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Rules about the tests themselves.
 *
 * <p>JUnit 4 is on the test class path because mockwebserver needs it: its
 * {@code MockWebServer} extends a JUnit 4 rule. The build runs JUnit 5 only, so a test
 * written against {@code org.junit.Test} would compile and then silently never run,
 * which reads exactly like a green build.
 */
class TestConventionsTest {

	private static final JavaClasses TESTS = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
			.importPackages("com.ahmadre.hinata");

	@Test
	void noTestIsWrittenAgainstJUnit4() {
		noClasses()
				.should().dependOnClassesThat().resideInAnyPackage("org.junit", "junit.framework")
				.because("only JUnit 5 runs here; a JUnit 4 test would compile and never be executed")
				.check(TESTS);
	}
}
