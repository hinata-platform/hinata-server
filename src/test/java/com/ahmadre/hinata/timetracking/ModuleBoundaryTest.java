package com.ahmadre.hinata.timetracking;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
	private static final String TIME_OFF = ROOT + ".timeoff";
	private static final String TEMPLATE = ROOT + ".template";

	/** The module and the three packages that hang off it. */
	private static final String[] MODULE_PACKAGES = {
			"..timetracking..", "..availability..", "..billing..", "..timeoff.." };

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
	void onlyTimeTrackingAndShiftPlanningReadAvailability() {
		// No bridges and no storage contract here. Capacity has three readers by design:
		// time tracking, which shows absences and holidays beside the entries, absence
		// management, which needs the shape of a working week for § 3 BUrlG, and shift
		// planning (HIN-43/45), which checks shifts against them. None may ask it before
		// a write: a marking must never turn into a refusal (R9).
		noClasses()
				.that(resideOutsideOfPackages("..availability..", "..timetracking..", "..timeoff..",
						"..schedule..", "..template.."))
				.should().dependOnClassesThat().resideInAnyPackage("..availability..")
				.because("capacity is read by time tracking and shift planning, and by nobody else")
				.check(PRODUCTION);
	}

	@Test
	void nothingOutsideBillingReachesIntoIt() {
		// Billing arrives in stage 15 with nothing outside it entitled to know. Whether
		// time tracking may ask it about invoiced days is decided there, on purpose.
		noClasses()
				.that(resideOutsideOfPackages("..billing.."))
				.should().dependOnClassesThat().resideInAnyPackage("..billing..")
				.because("money is the module's own business")
				.check(PRODUCTION);
	}

	@Test
	void availabilityNeverDependsOnTimeTracking() {
		// The allowed direction is timetracking -> availability: a timesheet asks
		// what somebody was available for. The reverse would make shift planning
		// (HIN-49), which needs the same capacity data, drag time tracking in.
		noClasses()
				.that().resideInAPackage("..availability..")
				.should().dependOnClassesThat()
				.resideInAnyPackage("..timetracking..", "..billing..", "..timeoff..")
				.because("timetracking -> availability is the one direction that is allowed")
				.check(PRODUCTION);
	}

	@Test
	void absenceManagementIsBuiltOnTopAndNothingIsBuiltOnIt() {
		// timeoff -> timetracking is allowed and used once: the absence flag is a field of the
		// timeTracking settings block, and TimeTrackingSettings resolves that block. A second
		// resolver over the same document is how two answers to one question get created.
		//
		// The reverse is refused. Recording time knows nothing about entitlements, balances or
		// requests, and must not learn: the day it asks whether somebody has vacation left is the
		// day a balance could refuse an entry (R9). What time tracking needs from absence
		// management later — the "requested" layer of A3 — arrives inverted, the way
		// AvailabilityPolicy and SettingsGuard already do it.
		noClasses()
				.that().resideInAPackage("..timetracking..")
				.should().dependOnClassesThat().resideInAnyPackage("..timeoff..")
				.because("absences are managed on top of time tracking, never underneath it")
				.check(PRODUCTION);
		// And nothing outside the three module packages reaches in at all. Account deletion and
		// the data export do not: they arrive through the UserDeletedEvent and the
		// PersonalDataExport interface, so the core keeps knowing nothing.
		noClasses()
				.that(resideOutsideOfPackages(MODULE_PACKAGES))
				.should().dependOnClassesThat().resideInAnyPackage("..timeoff..")
				.because("absence management has to be switchable off by an administrator")
				.check(PRODUCTION);
	}

	/** The classes of the module that read availability: two for display, one for who sees it. */
	private static final Set<String> AVAILABILITY_READERS = Set.of(
			// The absences, holidays and planned minutes the calendar draws.
			TIME + ".TimeCalendarLayers",
			// The holidays a working-time hint names.
			TIME + ".TimeHintsService",
			// Tells availability whether, and through which projects, leads see absences.
			TIME + ".TimeAvailabilityPolicy",
			// Skips a reminder on a day that is no working day (HIN-92). It writes marks and
			// notifications, never an entry, and asks only yes or no per person.
			TIME + ".TimeReminders",
			// The working days in somebody's week, for the statutory minimum leave (§ 3 BUrlG).
			// Days, never minutes: a balance counts working days and capacity counts minutes,
			// and neither is computed from the other.
			TIME_OFF + ".TimeOffWorkWeek",
			// The catalogue's answer to the one question availability asks it (HIN-116): which of
			// the three stored kinds an operator's own absence type is. It names TimeOff.Type and
			// implements an interface availability declares — it reads no absence and asks no
			// capacity, which is why it is a bridge and not a reader of anybody's days.
			TIME_OFF + ".TimeOffCatalogueBridge",
			// What a span of dates is worth in working days (HIN-117). It reads one capacity
			// window for the person's own pattern and their holidays, throws the minutes and the
			// absences away, and answers in days.
			TIME_OFF + ".TimeOffWorkingDays",
			// The whole contact surface between the approval flow and the calendar an approval
			// writes into: enter, shorten, remove. One named class rather than five, so the
			// question "who may learn that somebody is away?" has one place to be asked.
			TIME_OFF + ".TimeOffAbsences",
			// Answers the question availability asks about a direct entry — whether the type is
			// one somebody has to approve. It names the interface availability declares and
			// reads no absence at all.
			TIME_OFF + ".TimeOffApprovalGuard",
			// Answers the other question availability asks: whether somebody keeps absences
			// without being an administrator (HIN-116's named circle). It reads no absence either.
			TIME_OFF + ".TimeOffKeeperBridge",
			// Which days a holiday calendar marks, so a working-day deadline can skip them
			// (HIN-122). A calendar, never a person: it asks holidaysOf(calendarId, year) and
			// nothing else, reads no working pattern, no capacity and no absence. A deadline
			// four working days before an event must not move because somebody booked leave,
			// which is exactly why this is the only class of the module that may ask.
			TEMPLATE + ".HolidayCalendars");

	/**
	 * The readers and the routes that serve what they compute: the only classes that may call a
	 * reader. A route writes through its service, and no service is on this list. The reminder
	 * job is the schedule of its reader, the way a controller is the route of one.
	 */
	private static final Set<String> READERS_AND_THEIR_ROUTES = Set.of(
			TIME + ".TimeCalendarLayers", TIME + ".TimeHintsService", TIME + ".TimeAvailabilityPolicy",
			TIME + ".TimeReminders", TIME + ".TimeEntryController", TIME + ".TimeHintsController",
			TIME + ".TimeReminderJob",
			TIME_OFF + ".TimeOffWorkWeek", TIME_OFF + ".TimeOffBalanceService",
			TIME_OFF + ".TimeOffCatalogueBridge",
			TIME_OFF + ".TimeOffWorkingDays", TIME_OFF + ".TimeOffAbsences",
			TIME_OFF + ".TimeOffApprovalGuard", TIME_OFF + ".TimeOffKeeperBridge",
			// The approval flow: it calls the readers above and holds nothing of availability
			// itself, which is why TimeOffAbsences exists at all.
			TIME_OFF + ".TimeOffRequestService",
			// The two classes that turn a holiday calendar into a date: the answer an issue
			// write asks for, and the rewrite after an event date moves. Both go through
			// HolidayCalendars and neither names availability itself.
			TEMPLATE + ".ProjectDeadlines", TEMPLATE + ".ProjectScheduleService",
			// And the copy, which resolves the new project's deadlines against its own event
			// date as it writes them — the one moment a copy needs to know about holidays.
			TEMPLATE + ".ProjectCopyService",
			// And the door itself: it holds a nested calendar that caches one year's holidays,
			// so it accesses its own reader.
			TEMPLATE + ".HolidayCalendars");

	@Test
	void nobodyOutsideTheNamedReadersAsksAvailability() {
		// HIN-92 review: CapacityService.workingOn answers for any set of people. A caller in any
		// other module could learn who is away on which day without AvailabilityAccess, so outside
		// its own package only the readers named above may ask, each with its reason.
		noClasses()
				.that().resideOutsideOfPackage("..availability..")
				.and(not(named(AVAILABILITY_READERS, "a named reader of availability")))
				.should().dependOnClassesThat().resideInAnyPackage("..availability..")
				.because("a view of other people's absences belongs in AvailabilityAccess (HIN-91)")
				.check(PRODUCTION);
	}

	/** The services that write an entry, a timer, a submission or a correction. */
	private static final Set<String> WRITE_PATHS = Set.of(
			TIME + ".TimeTrackingService", TIME + ".TimerService", TIME + ".TimeLocks",
			TIME + ".TimesheetApprovalService", TIME + ".TimeCorrectionService",
			TIME + ".TimeTagService");

	@Test
	void recordingTimeNeverAsksAvailability() {
		// R9 (HIN-91): a holiday, an absence or a day without planned hours is marked,
		// never refused. § 9 ArbZG forbids the work, not recording it, and § 16 Abs. 2
		// ArbZG wants Sunday and holiday work recorded. So availability is known only
		// by the named readers, and no write path may reach those readers.
		noClasses()
				.that().resideInAPackage("..timetracking..")
				.and(not(named(AVAILABILITY_READERS, "a read of availability for display")))
				.should().dependOnClassesThat().resideInAnyPackage("..availability..")
				.because("a marking must never turn into a refusal to record time (R9)")
				.check(PRODUCTION);
		noClasses()
				.that(named(WRITE_PATHS, "a write path of the module"))
				.should().dependOnClassesThat(named(AVAILABILITY_READERS, "a read of availability"))
				.because("a write path that knew a holiday could refuse one (R9)")
				.check(PRODUCTION);
		// The list of write paths misses the next service, and a helper between a write path and a
		// reader passes the rule above. So the readers also name who may call them.
		classes()
				.that(named(AVAILABILITY_READERS, "a read of availability"))
				.should().onlyBeAccessed().byClassesThat(
						named(READERS_AND_THEIR_ROUTES, "a reader, or the route that serves what it reads"))
				.because("a reader called from anywhere else is one step away from a refusal (R9)")
				.check(PRODUCTION);
	}

	@Test
	void theCalendarPackageKnowsNothingOfTheModulesThatReadThroughIt() {
		// ics is shared: holiday imports (stage 10) and calendar subscriptions
		// (stage 13) read through it, and shift planning puts a writer beside it
		// (HIN-47). A parser that knew about time entries could not be handed on.
		noClasses()
				.that().resideInAPackage("..ics..")
				.should().dependOnClassesThat().resideInAnyPackage(MODULE_PACKAGES)
				.because("ics serves time tracking, availability and shift planning alike")
				.check(PRODUCTION);
	}

	@Test
	void readingACalendarNeedsNoApplicationContext() {
		// The parser needs no application context and keeps no state between calls.
		// Spring and the configuration come in at the fetcher and the cipher, and
		// nowhere else.
		noClasses()
				.that().resideInAPackage("..ics..")
				.and().doNotHaveSimpleName("IcsFetcher")
				.and().doNotHaveSimpleName("IcsUrlCipher")
				.should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "com.ahmadre.hinata.config..")
				.because("the parser must run without an application context")
				.check(PRODUCTION);
	}

	@Test
	void onlyTheCalendarParserUsesIcal4jAndOnlyItsRecurrenceEngine() {
		// ical4j's builder expands time zones without a bound, takes zone ids from a
		// JVM-wide pool and writes ATTACH values to temporary files; the ics package
		// documentation has the measurements. Whoever needs more of the library, a
		// calendar writer for instance, widens this rule on purpose.
		Set<String> parser = Set.of(ROOT + ".ics.IcsParser");
		noClasses()
				.that(not(named(parser, "the ics parser")))
				.should().dependOnClassesThat().resideInAnyPackage("net.fortuna.ical4j..")
				.because("only the recurrence engine of ical4j is safe on calendars from strangers")
				.check(PRODUCTION);
		noClasses()
				.that(named(parser, "the ics parser"))
				.should().dependOnClassesThat(resideInAnyPackage("net.fortuna.ical4j..")
						.and(not(HasName.Predicates.name("net.fortuna.ical4j.model.Recur"))))
				.because("the parser borrows the recurrence engine and nothing else")
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
