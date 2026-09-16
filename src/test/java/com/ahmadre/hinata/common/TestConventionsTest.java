package com.ahmadre.hinata.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

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

	/**
	 * The image the containers run is named in {@link TestMongo} alone, so a machine whose kernel
	 * MongoDB refuses can point every test elsewhere at once, and CI keeps testing the version that
	 * serves people.
	 */
	@Test
	void noTestNamesTheMongoImageItself() throws IOException {
		Path tests = Path.of("src/test/java");
		try (Stream<Path> sources = Files.walk(tests)) {
			List<String> naming = sources
					.filter(path -> path.toString().endsWith(".java"))
					// The two files that name an image by trade: the constant, and this rule.
					.filter(path -> !path.endsWith("common/TestMongo.java"))
					.filter(path -> !path.endsWith("common/TestConventionsTest.java"))
					.filter(path -> readable(path).contains("mongo:"))
					.map(path -> tests.relativize(path).toString())
					.toList();
			assertThat(naming)
					.as("tests naming a Mongo image instead of TestMongo.IMAGE")
					.isEmpty();
		}
	}

	private static String readable(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException failure) {
			throw new IllegalStateException("cannot read " + path, failure);
		}
	}

	@Test
	void noTestIsWrittenAgainstJUnit4() {
		noClasses()
				.should().dependOnClassesThat().resideInAnyPackage("org.junit", "junit.framework")
				.because("only JUnit 5 runs here; a JUnit 4 test would compile and never be executed")
				.check(TESTS);
	}
}
