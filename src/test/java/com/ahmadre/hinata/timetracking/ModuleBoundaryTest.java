package com.ahmadre.hinata.timetracking;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boundary around time tracking, availability and billing, stated as a rule
 * because the compiler cannot state it.
 *
 * <p>These three packages grow by twelve more stages, and every one of them adds
 * something the rest of the product would find convenient to call. The cost of
 * letting it is not felt on the day: it is felt when the module has to be
 * switched off — the whole point of {@code advanced_time_tracking} — or moved,
 * and half the codebase turns out to be holding on to it. So the direction is
 * fixed here, while the module is still small enough for the rule to be true.
 *
 * <p>Three kinds of contact are allowed, and each is named rather than described,
 * so adding a fourth is a decision somebody makes on purpose:
 *
 * <ul>
 * <li><b>The stored entry.</b> {@code WorkItem} and its repository are the
 * storage contract of the {@code work_items} collection — the reports, the
 * dashboard, the weekly summary, issue deletion and issue moves all read or
 * re-point those documents. They may name the shape; they may not reach for the
 * module's behaviour. {@code ProjectTimeSettings} and
 * {@code TimesheetApproval} join them for one reason only: deleting a project
 * has to take their rows with it, and a cascade that left a collection behind
 * because the module was switched off would be a leak with no owner. A
 * timesheet submission has a second reason of its own — it <em>freezes</em> the
 * entries it covers, so a row whose project is gone would freeze them against
 * a lead nobody can reach.</li>
 * <li><b>The named bridges.</b> Two classes exist to connect the outside to the
 * module and are listed one by one.</li>
 * <li><b>The wire contracts.</b> {@code AuditAction}, {@code Notification.Type}
 * and {@code pat/Scopes} carry time-tracking constants — {@code TIME_ENTRY_*},
 * {@code worklog:read} — because those names travel to clients and to the audit
 * log. Constants are all they may carry: a rule below keeps them from acquiring
 * a dependency on the module itself.</li>
 * </ul>
 *
 * <p>The rules about {@code ..availability..} and {@code ..billing..} pass
 * vacuously today; those packages arrive in stages 10 and 15. That is
 * deliberate. A boundary is cheap to hold and expensive to reinstate, and the
 * first import that would have crossed it is the one worth catching.
 */
class ModuleBoundaryTest {

	private static final String ROOT = "com.ahmadre.hinata";
	private static final String TIME = ROOT + ".timetracking";

	/** The module and the two packages that will hang off it. */
	private static final String[] MODULE_PACKAGES = {
			"..timetracking..", "..availability..", "..billing.." };

	/**
	 * The {@code work_items} documents themselves — the entity, its Lombok
	 * builder, its nested {@code Source} enum and the repository. Naming a stored
	 * shape is not a dependency on the module's behaviour.
	 */
	private static final Set<String> STORAGE_CONTRACT =
			Set.of(TIME + ".WorkItem", TIME + ".WorkItemRepository",
					TIME + ".ProjectTimeSettings", TIME + ".TimesheetApproval");

	/**
	 * The classes that are allowed to reach into the module, each for a stated
	 * reason. Every one of them is an adapter: it exists so that some other
	 * protocol — MCP, a git push, the demo dataset — can reach the same service
	 * the REST controller reaches, rather than reimplementing its rules.
	 */
	private static final Set<String> BRIDGES = Set.of(
			// The MCP tools for logging and reading time.
			ROOT + ".mcp.TimeTrackingTools",
			// #time in a commit message, logged as the commit's author.
			ROOT + ".git.GitService");
	// DemoSeeder is deliberately not here. It names WorkItem and its repository,
	// which the storage contract already allows, so exempting it would buy
	// nothing today and cost the warning on the day it reaches for the service —
	// which is the convenient thing for a seeder to do.

	/**
	 * The core types that name time-tracking concepts as constants. They are the
	 * sanctioned way for the module to appear in a contract the outside reads.
	 */
	private static final List<String> WIRE_CONTRACTS = List.of(
			ROOT + ".audit.AuditAction",
			ROOT + ".notification.Notification",
			ROOT + ".pat.Scopes");

	private static final JavaClasses PRODUCTION = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(ROOT);

	private static DescribedPredicate<JavaClass> named(Set<String> names, String description) {
		return DescribedPredicate.describe(description, javaClass -> {
			String fullName = javaClass.getFullName();
			return names.stream().anyMatch(
					name -> fullName.equals(name) || fullName.startsWith(name + "$"));
		});
	}

	@Test
	void theRuleSeesProductionCodeAndNotTheTestsThatDescribeIt() {
		// A boundary rule that quietly imported nothing, or imported this test
		// class along with everything it is allowed to reach for, would pass
		// forever and mean nothing.
		assertThat(PRODUCTION).isNotEmpty();
		assertThat(PRODUCTION.stream().map(JavaClass::getFullName))
				.contains(TIME + ".TimeTrackingService")
				.doesNotContain(ROOT + ".timetracking.ModuleBoundaryTest");
	}

	@Test
	void nothingOutsideTheModuleReachesForItsBehaviour() {
		ArchRule rule = noClasses()
				.that(resideOutsideOfPackages(MODULE_PACKAGES)
						.and(not(named(BRIDGES, "one of the named bridges"))))
				.should().dependOnClassesThat(resideInAnyPackage("..timetracking..")
						.and(not(named(STORAGE_CONTRACT, "the stored work-item contract"))))
				.because("the extended module has to be switchable off and movable; "
						+ "the outside may name a stored entry, not call the module");

		rule.check(PRODUCTION);
	}

	@Test
	void nothingOutsideTheNewPackagesReachesIntoThemAtAll() {
		// No bridges and no storage contract here: availability and billing arrive
		// in stages 10 and 15 with nothing outside them entitled to know.
		noClasses()
				.that(resideOutsideOfPackages("..availability..", "..billing.."))
				.should().dependOnClassesThat().resideInAnyPackage("..availability..", "..billing..")
				.because("capacity and money are the module's own business")
				.check(PRODUCTION);
	}

	@Test
	void availabilityNeverDependsOnTimeTracking() {
		// The allowed direction is timetracking -> availability: a timesheet asks
		// what somebody was available for. The reverse would make shift planning
		// (HIN-49), which needs the same capacity data, drag time tracking in.
		noClasses()
				.that().resideInAPackage("..availability..")
				.should().dependOnClassesThat().resideInAnyPackage("..timetracking..", "..billing..")
				.because("timetracking -> availability is the one direction that is allowed")
				// There is no availability package until stage 10, and ArchUnit
				// treats "nothing matched" as a mistake by default. Here it is the
				// expected state, and the rule is in place before the first class is.
				.allowEmptyShould(true)
				.check(PRODUCTION);
	}

	@Test
	void theWireContractsCarryNamesAndNothingElse() {
		// AuditAction has TIME_ENTRY_*, Scopes has worklog:read/write, and
		// Notification.Type will gain the approval events in stage 7. Constants
		// are the point: the moment one of these needs the module to compute
		// something, the contract has stopped being a contract.
		noClasses()
				.that(named(Set.copyOf(WIRE_CONTRACTS), "a wire contract naming module concepts"))
				.should().dependOnClassesThat().resideInAnyPackage(MODULE_PACKAGES)
				.because("they may spell the module's vocabulary, not depend on it")
				.check(PRODUCTION);
	}
}
