package com.ahmadre.hinata.shifts;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The overlap arithmetic and the two conditional queries of the assignment path, without Spring. */
class ShiftConflictsTest {

	private static final Instant TEN = Instant.parse("2026-11-02T10:00:00Z");
	private static final Instant NOON = Instant.parse("2026-11-02T12:00:00Z");
	private static final Instant TWO = Instant.parse("2026-11-02T14:00:00Z");

	private static ShiftAssignment held(String shiftId, Instant start, Instant end) {
		return ShiftAssignment.builder().userId("ann").shiftId(shiftId).eventId("e").projectId("p")
				.startsAt(start).endsAt(end).status(ShiftAssignment.Status.CONFIRMED).cancelled(false).build();
	}

	@Test
	void backToBackIsNoClash() {
		assertThat(ShiftConflicts.firstOverlap(NOON, TWO, "s2", List.of(held("s1", TEN, NOON)))).isEmpty();
		assertThat(ShiftConflicts.firstOverlap(TEN.plusSeconds(3600), TWO, "s2", List.of(held("s1", TEN, NOON))))
				.map(ShiftAssignment::getShiftId).contains("s1");
	}

	@Test
	void cancelledInactiveAndTheSameShiftOccupyNothing() {
		ShiftAssignment cancelled = held("s1", TEN, TWO);
		cancelled.setCancelled(true);
		ShiftAssignment released = held("s3", TEN, TWO);
		released.setStatus(ShiftAssignment.Status.RELEASED);
		ShiftAssignment same = held("s2", TEN, TWO);
		assertThat(ShiftConflicts.firstOverlap(TEN, TWO, "s2", List.of(cancelled, released, same))).isEmpty();
	}

	@Test
	void collisionsNameThePersonTheMovedShiftAndTheOtherOne() {
		ShiftAssignment moved = held("moved", TEN, NOON);
		ShiftAssignment other = held("other", TEN.plusSeconds(1800), TWO);
		other.setProjectId("q");
		List<ShiftConflicts.Collision> found = ShiftConflicts.collisions(List.of(moved), List.of(moved, other));
		assertThat(found).containsExactly(new ShiftConflicts.Collision("ann", "moved", "other", "q"));
	}

	@Test
	void collisionsLookOnlyAtThePersonsOwnPlacesAndNameTheEarliest() {
		ShiftAssignment moved = held("moved", TEN, TWO);
		ShiftAssignment later = held("later", NOON, TWO.plusSeconds(3600));
		ShiftAssignment earlier = held("earlier", TEN.minusSeconds(3600), TEN.plusSeconds(60));
		ShiftAssignment dayBefore = held("dayBefore", TEN.minus(ShiftRules.SHIFT_MAX), TEN);
		ShiftAssignment bobs = held("bobs", TEN, TWO);
		bobs.setUserId("bob");
		ShiftAssignment bobMoved = held("bobMoved", TWO, TWO.plusSeconds(7200));
		bobMoved.setUserId("bob");

		List<ShiftConflicts.Collision> found = ShiftConflicts.collisions(List.of(moved, bobMoved),
				List.of(later, bobs, moved, earlier, dayBefore, bobMoved));

		assertThat(found).containsExactly(new ShiftConflicts.Collision("ann", "moved", "earlier", "p"));
	}

	@Test
	void theCapacityClaimRendersAsTheDatabaseShouldSeeIt() {
		Document claim = Query.query(ShiftAssignments.claimable("s1")).getQueryObject();
		assertThat(claim.get("_id")).isEqualTo("s1");
		assertThat(claim.get("deleted")).isEqualTo(false);
		assertThat(claim.get("cancelled")).isEqualTo(false);
		List<?> or = claim.getList("$or", Object.class);
		assertThat(or).hasSize(2);
		assertThat(((Document) or.get(0)).toJson()).isEqualTo(new Document("maxHelpers", null).toJson());
		Object expr = ((Document) or.get(1)).get("$expr");
		Document rendered = expr instanceof org.springframework.data.mongodb.core.aggregation.AggregationExpression expression
				? expression.toDocument(org.springframework.data.mongodb.core.aggregation.Aggregation.DEFAULT_CONTEXT)
				: (Document) expr;
		assertThat(rendered.toJson())
				.isEqualTo(new Document("$lt", List.of("$assignedCount", "$maxHelpers")).toJson());
	}

	@Test
	void theOverlapReadIsBoundedBelowByTheLongestShift() {
		Document around = Query.query(ShiftAssignments.around("ann", NOON, TWO)).getQueryObject();
		Document startsAt = around.get("startsAt", Document.class);
		assertThat(startsAt.get("$lt")).isEqualTo(TWO);
		assertThat(startsAt.get("$gt")).isEqualTo(NOON.minus(ShiftRules.SHIFT_MAX));
		assertThat(around.get("cancelled")).isEqualTo(false);
	}

	@Test
	void writeConflictsAreRecognisedThroughTheTranslation() {
		com.mongodb.MongoException conflict = new com.mongodb.MongoException(ShiftTx.WRITE_CONFLICT, "WriteConflict");
		assertThat(ShiftTx.transientConflict(new org.springframework.dao.DataAccessResourceFailureException("x", conflict)))
				.isTrue();
		com.mongodb.MongoException labelled = new com.mongodb.MongoException(1, "other");
		labelled.addLabel(com.mongodb.MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
		assertThat(ShiftTx.transientConflict(labelled)).isTrue();
		assertThat(ShiftTx.transientConflict(new IllegalStateException("nope"))).isFalse();
	}
}
