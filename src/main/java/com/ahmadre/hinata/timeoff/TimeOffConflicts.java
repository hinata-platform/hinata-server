package com.ahmadre.hinata.timeoff;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Who else is away at the same time — the one piece of arithmetic behind every clash warning.
 *
 * <p>Pure and Spring-free, like {@code availability.Capacity}: the decider's inbox (A2), the team
 * calendar and its capacity band (A3), and later shift planning (HIN-43/45) all count overlaps
 * here, so "two others are away then" means the same thing on every screen. A second copy would
 * disagree on the one edge nobody tests: a span that ends on the day another begins.
 *
 * <p>It counts people and spans; it never names a type. Whether somebody is on vacation or ill is
 * not part of any question asked here (R10, R11).
 */
public final class TimeOffConflicts {

	private TimeOffConflicts() {
	}

	/**
	 * One person away over [from]..[to], both days included. [settled] is an approved request or
	 * an entered absence; unsettled is a request nobody has decided yet.
	 */
	public record Span(String userId, LocalDate from, LocalDate to, boolean settled) {

		boolean touches(LocalDate first, LocalDate last) {
			return !from.isAfter(last) && !to.isBefore(first);
		}
	}

	/** How many people are away on one day, split by whether it is settled. */
	public record Day(LocalDate date, int away, int requested) {
	}

	/**
	 * How many of [others] clash with [userId] being away from [from] to [to]: spans of somebody
	 * else that share at least one day with it. Counted per span, as the inbox has always counted.
	 */
	public static int clashesWith(List<Span> others, String userId, LocalDate from, LocalDate to) {
		int count = 0;
		for (Span other : others) {
			if (!other.userId().equals(userId) && other.touches(from, to)) {
				count++;
			}
		}
		return count;
	}

	/**
	 * For every day from [from] to [to]: how many different people are away with it settled, and
	 * how many more have only asked. Somebody with both an absence and an open request on a day
	 * counts once, as away.
	 */
	public static List<Day> perDay(List<Span> spans, LocalDate from, LocalDate to) {
		int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
		List<Set<String>> settled = new ArrayList<>(days);
		List<Set<String>> asked = new ArrayList<>(days);
		for (int i = 0; i < days; i++) {
			settled.add(new HashSet<>());
			asked.add(new HashSet<>());
		}
		for (Span span : spans) {
			if (!span.touches(from, to)) {
				continue;
			}
			LocalDate first = span.from().isBefore(from) ? from : span.from();
			LocalDate last = span.to().isAfter(to) ? to : span.to();
			for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
				int index = (int) ChronoUnit.DAYS.between(from, day);
				(span.settled() ? settled : asked).get(index).add(span.userId());
			}
		}
		List<Day> result = new ArrayList<>(days);
		for (int i = 0; i < days; i++) {
			asked.get(i).removeAll(settled.get(i));
			result.add(new Day(from.plusDays(i), settled.get(i).size(), asked.get(i).size()));
		}
		return List.copyOf(result);
	}
}
