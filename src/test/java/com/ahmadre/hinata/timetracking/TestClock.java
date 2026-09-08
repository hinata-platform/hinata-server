package com.ahmadre.hinata.timetracking;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the timer tests move by hand.
 *
 * <p>Not {@code Clock.fixed}: most of what is worth asserting about a timer is
 * what happens as time passes — the 24-hour ceiling, a countdown reaching its
 * target, a pomodoro turning over — and sleeping through any of that is not a
 * test anyone runs.
 */
public class TestClock extends Clock {

	/** Where every test starts, so a stored instant reads as a literal. */
	public static final Instant START = Instant.parse("2026-09-07T08:00:00Z");

	private Instant now = START;

	public void set(Instant instant) {
		this.now = instant;
	}

	public void advance(Duration by) {
		this.now = this.now.plus(by);
	}

	@Override
	public ZoneOffset getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return now;
	}

	/** Swaps the application's clock for this one. {@code @Import} it. */
	@TestConfiguration
	public static class Config {
		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}
	}
}
