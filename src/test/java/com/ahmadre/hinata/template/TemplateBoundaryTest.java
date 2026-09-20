package com.ahmadre.hinata.template;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which way project templates are allowed to depend, fixed while the module is still small enough
 * for the rule to be true.
 *
 * <p>The direction is {@code template -> project, issue, availability} and never the reverse.
 * {@code issue} is the package every screen in the product goes through; a dependency from there
 * into a module an administrator can switch off would make "off" mean "broken". Where the core
 * genuinely needs an answer from the module, it asks through an interface the core itself declares
 * — {@code issue.IssueDeadlinePolicy} — which is the same inversion {@code availability} and
 * {@code timeoff} already use.
 *
 * <p>The one exception is the stored shape. {@code common.RelativeDate} is a field of
 * {@code Issue} and therefore lives in {@code common}, not here: a stored type is a contract, and
 * putting it in the module would have forced the very dependency this file forbids.
 */
class TemplateBoundaryTest {

	private static final String ROOT = "com.ahmadre.hinata";

	private static final JavaClasses PRODUCTION = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(ROOT);

	@Test
	@DisplayName("the rule sees production code and not the test that describes it")
	void theRuleSeesSomething() {
		assertThat(PRODUCTION).isNotEmpty();
		assertThat(PRODUCTION.stream().map(JavaClass::getFullName))
				.contains(ROOT + ".template.ProjectScheduleService")
				.doesNotContain(ROOT + ".template.TemplateBoundaryTest");
	}

	/**
	 * The classes allowed to reach into the module, each for a stated reason. Both are adapters:
	 * they exist so that another protocol reaches the same service the REST route reaches,
	 * rather than reimplementing its rules.
	 */
	private static final Set<String> BRIDGES = Set.of(
			// The MCP tools for copying a project and for keeping a deadline as an offset.
			ROOT + ".mcp.ProjectTemplateTools",
			// The demo workspace, which seeds a template and a project made from it so the
			// feature is visible on a first start rather than only described.
			ROOT + ".demo.DemoSeeder");

	private static DescribedPredicate<JavaClass> named(Set<String> names, String description) {
		return DescribedPredicate.describe(description, javaClass -> {
			String fullName = javaClass.getFullName();
			return names.stream().anyMatch(
					name -> fullName.equals(name) || fullName.startsWith(name + "$"));
		});
	}

	@Test
	@DisplayName("nothing outside the module depends on it, bar the two named bridges")
	void nothingOutsideReachesIn() {
		// Including the core packages it reads: a project and an issue know nothing about
		// templates, which is what makes the feature removable by deleting one package.
		noClasses()
				.that(DescribedPredicate.describe("outside the module",
						(JavaClass javaClass) -> !javaClass.getPackageName()
								.startsWith(ROOT + ".template"))
						.and(not(named(BRIDGES, "one of the named bridges"))))
				.should().dependOnClassesThat().resideInAnyPackage("..template..")
				.because("project templates have to be switchable off by an administrator")
				.check(PRODUCTION);
	}

	@Test
	@DisplayName("the arithmetic needs no application context and no database")
	void theCalculationStaysPure() {
		// Three callers must get the same date out of it — the preview, the copy and the
		// rewrite after an event date moves — and a calculation that can be tested in
		// microseconds is a calculation people actually test. RelativeDate is its input and
		// lives in common, so it is named rather than reached for.
		noClasses()
				.that().haveSimpleName("RelativeDates").or().haveSimpleName("WorkdayCalendar")
				.should().dependOnClassesThat()
				.resideInAnyPackage("org.springframework..", "com.ahmadre.hinata.config..",
						"com.ahmadre.hinata.availability..", "com.mongodb..")
				.because("the one place a date is computed has to be testable without a server")
				.check(PRODUCTION);
	}

	@Test
	@DisplayName("holidays are read through one class, and only there")
	void availabilityHasOneDoor() {
		classes()
				.that().resideInAPackage("..template..")
				// By name rather than by simple name: the door holds a nested calendar that
				// caches one year of holidays, and a nested class has a simple name of its own.
				.and().haveNameNotMatching(".*HolidayCalendars(\\$.*)?")
				.should().onlyDependOnClassesThat().resideOutsideOfPackage("..availability..")
				.because("a calendar is a property of days; capacity is a property of people")
				.check(PRODUCTION);
	}
}
