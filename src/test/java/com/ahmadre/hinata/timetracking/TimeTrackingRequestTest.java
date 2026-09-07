package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.mcp.TimeTrackingTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.assertj.core.api.InstanceOfAssertFactories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The request layer of time tracking: the bounds bean validation rejects before
 * anything reaches the service, and the two pure rules the service applies to
 * whatever gets through.
 *
 * <p>The interesting one is the patch: "leave this field alone" and "clear this
 * field" arrive as the same JSON {@code null}, and only the fact that Jackson
 * calls a setter exclusively for a property that is <em>present</em> tells them
 * apart. That is the reason the patch body is a class with setters rather than
 * a record, and it is worth a test because a well-meant refactor to a record
 * would silently turn every "keep" into a "clear".
 */
class TimeTrackingRequestTest {

	private static ValidatorFactory factory;
	private static Validator validator;

	private final ObjectMapper json = new ObjectMapper()
			.findAndRegisterModules();

	@BeforeAll
	static void openValidator() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		factory.close();
	}

	private static List<String> violations(Object request) {
		return validator.validate(request).stream()
				.map(ConstraintViolation::getPropertyPath)
				.map(Object::toString)
				.sorted()
				.toList();
	}

	private static TimeTrackingController.WorkItemRequest request(Integer minutes,
			List<String> tags, String description) {
		return new TimeTrackingController.WorkItemRequest(minutes, LocalDate.of(2026, 9, 7), null,
				description, null, null, tags, null);
	}

	// --- what never reaches the service --------------------------------------------

	@Test
	void aDurationOutsideTheAllowedRangeIsRejectedBeforeTheService() {
		assertThat(violations(request(0, null, null))).containsExactly("durationMinutes");
		assertThat(violations(request(TimeTrackingService.MAX_MINUTES + 1, null, null)))
				.containsExactly("durationMinutes");

		assertThat(violations(request(1, null, null))).isEmpty();
		assertThat(violations(request(TimeTrackingService.MAX_MINUTES, null, null))).isEmpty();
		assertThat(violations(request(null, null, null)))
				.as("absent is legal — an interval may define the duration instead").isEmpty();
	}

	@Test
	void tooManyTagsOrTooLongATagIsRejected() {
		List<String> tooMany = Stream.iterate(1, i -> i + 1).limit(21).map(String::valueOf).toList();
		assertThat(violations(request(30, tooMany, null))).containsExactly("tags");

		assertThat(violations(request(30, List.of("x".repeat(41)), null)))
				.singleElement(InstanceOfAssertFactories.STRING)
				.startsWith("tags[0]");

		assertThat(violations(request(30, Stream.iterate(1, i -> i + 1).limit(20)
				.map(String::valueOf).toList(), null))).isEmpty();
		assertThat(violations(request(30, List.of("x".repeat(40)), null))).isEmpty();
	}

	@Test
	void anOverlongDescriptionIsRejected() {
		assertThat(violations(request(30, null, "x".repeat(2001)))).containsExactly("description");
		assertThat(violations(request(30, null, "x".repeat(2000)))).isEmpty();
	}

	@Test
	void theSameBoundsApplyToAPatch() throws Exception {
		TimeTrackingController.WorkItemPatchRequest patch = json.readValue(
				"{\"durationMinutes\":1441}", TimeTrackingController.WorkItemPatchRequest.class);

		assertThat(violations(patch)).containsExactly("durationMinutes");
	}

	// --- absent versus explicitly null -----------------------------------------------

	@Test
	void aFieldTheBodyDoesNotMentionIsLeftAlone() throws Exception {
		TimeTrackingController.WorkItemPatchRequest patch = json.readValue(
				"{\"durationMinutes\":45}", TimeTrackingController.WorkItemPatchRequest.class);

		TimeTrackingService.WorkItemPatch mapped = patch.toPatch();

		assertThat(mapped.durationMinutes()).isEqualTo(45);
		assertThat(mapped.startedAtSet()).isFalse();
		assertThat(mapped.endedAtSet()).isFalse();
	}

	@Test
	void anExplicitNullClearsTheField() throws Exception {
		TimeTrackingController.WorkItemPatchRequest patch = json.readValue(
				"{\"startedAt\":null,\"endedAt\":null}",
				TimeTrackingController.WorkItemPatchRequest.class);

		TimeTrackingService.WorkItemPatch mapped = patch.toPatch();

		assertThat(mapped.startedAtSet()).isTrue();
		assertThat(mapped.startedAt()).isNull();
		assertThat(mapped.endedAtSet()).isTrue();
		assertThat(mapped.endedAt()).isNull();
	}

	@Test
	void aGivenInstantIsCarriedThrough() throws Exception {
		TimeTrackingController.WorkItemPatchRequest patch = json.readValue(
				"{\"endedAt\":\"2026-09-07T09:30:00Z\"}",
				TimeTrackingController.WorkItemPatchRequest.class);

		assertThat(patch.toPatch().endedAt()).isEqualTo(Instant.parse("2026-09-07T09:30:00Z"));
	}

	// --- the two pure rules ------------------------------------------------------------

	@Test
	void anIntervalOutrankstheClaimedDuration() {
		Instant start = Instant.parse("2026-09-07T08:00:00Z");

		assertThat(TimeTrackingService.resolveDuration(5, start, start.plusSeconds(5400)))
				.isEqualTo(90);
		assertThat(TimeTrackingService.resolveDuration(45, null, null)).isEqualTo(45);
		assertThat(TimeTrackingService.resolveDuration(45, start, null))
				.as("half an interval defines nothing").isEqualTo(45);
	}

	@Test
	void anImpossibleIntervalIsRefused() {
		Instant start = Instant.parse("2026-09-07T08:00:00Z");

		assertThatThrownBy(() -> TimeTrackingService.resolveDuration(null, start, start))
				.isInstanceOf(ApiException.class).hasMessage("error.time.invalidDuration");
		assertThatThrownBy(() -> TimeTrackingService.resolveDuration(null, start,
				start.plusSeconds(60L * 60 * 25)))
				.isInstanceOf(ApiException.class).hasMessage("error.time.invalidDuration");
	}

	@Test
	void tagsAreTrimmedDeduplicatedAndNeverNull() {
		assertThat(TimeTrackingService.normalizeTags(null)).isEmpty();
		assertThat(TimeTrackingService.normalizeTags(new ArrayList<>(
				Arrays.asList(" a ", "a", "", "   ", null, "b"))))
				.containsExactly("a", "b");
	}

	/** Sanity: the response DTO names every entity field, so nothing new can be forgotten. */
	@Test
	void theResponseNamesEveryFieldOfTheEntity() {
		assertThat(componentsOf(TimeTrackingController.WorkItemResponse.class))
				.containsExactlyInAnyOrderElementsOf(entityFields());
	}

	/**
	 * The MCP view of an entry is the same shape by a different name, and it has
	 * no parity test of its own — so a field added to the entity would fail the
	 * assertion above, be added to the REST DTO, and go quietly missing from
	 * every MCP client.
	 */
	@Test
	void theMcpViewNamesEveryFieldOfTheEntityToo() {
		assertThat(componentsOf(TimeTrackingTools.WorkItemView.class))
				.containsExactlyInAnyOrderElementsOf(entityFields());
	}

	private static Set<String> entityFields() {
		return Stream.of(WorkItem.class.getDeclaredFields())
				.filter(field -> !field.isSynthetic() && !Modifier.isStatic(field.getModifiers()))
				.map(Field::getName)
				.collect(Collectors.toSet());
	}

	private static Set<String> componentsOf(Class<?> record) {
		return Stream.of(record.getRecordComponents()).map(RecordComponent::getName)
				.collect(Collectors.toSet());
	}
}
