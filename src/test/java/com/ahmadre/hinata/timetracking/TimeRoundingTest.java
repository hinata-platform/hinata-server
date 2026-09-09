package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.TimePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a duration folds onto an increment.
 *
 * <p>Worth a test of its own because it is a pure function that money will
 * eventually be computed from: an invoice built on "round up to 15" that
 * rounded 30 up to 45 would be wrong in a way nobody notices until a client
 * queries it. The interesting inputs are the ones on the boundary — a value that
 * is already a multiple, a value exactly half way — and they are the ones a
 * hand-written spot check skips.
 */
class TimeRoundingTest {

	@ParameterizedTest
	@EnumSource(TimePolicy.Rounding.class)
	void aValueOnTheIncrementIsNeverMoved(TimePolicy.Rounding mode) {
		assertThat(TimeRounding.round(30, mode, 15)).isEqualTo(30);
		assertThat(TimeRounding.round(0, mode, 15)).isZero();
	}

	@ParameterizedTest
	@CsvSource({
			// minutes, increment, up, down, nearest
			"1,  15, 15, 0,  0",
			"7,  15, 15, 0,  0",
			// Exactly half goes up, the way every invoice in the world rounds.
			"8,  15, 15, 0,  15",
			"14, 15, 15, 0,  15",
			"16, 15, 30, 15, 15",
			"23, 15, 30, 15, 30",
			"1,  5,  5,  0,  0",
			"3,  5,  5,  0,  5",
			"31, 60, 60, 0,  60",
			"29, 60, 60, 0,  0",
	})
	void everyModeFoldsTheSameValueItsOwnWay(int minutes, int increment, int up, int down,
			int nearest) {
		assertThat(TimeRounding.round(minutes, TimePolicy.Rounding.UP, increment)).isEqualTo(up);
		assertThat(TimeRounding.round(minutes, TimePolicy.Rounding.DOWN, increment)).isEqualTo(down);
		assertThat(TimeRounding.round(minutes, TimePolicy.Rounding.NEAREST, increment))
				.isEqualTo(nearest);
		assertThat(TimeRounding.round(minutes, TimePolicy.Rounding.NONE, increment))
				.isEqualTo(minutes);
	}

	@Test
	void nothingIsRoundedWithoutAModeOrAnIncrement() {
		// The values come from a settings document an operator edited and an
		// environment variable a deployment set. A report that died at midnight
		// because somebody typed a zero would be a worse answer than the
		// unrounded truth.
		assertThat(TimeRounding.round(7, null, 15)).isEqualTo(7);
		assertThat(TimeRounding.round(7, TimePolicy.Rounding.UP, 0)).isEqualTo(7);
		assertThat(TimeRounding.round(7, TimePolicy.Rounding.UP, -5)).isEqualTo(7);
		assertThat(TimeRounding.round(7, TimePolicy.Rounding.UP, 1)).isEqualTo(7);
	}

	@Test
	void aNegativeDurationFoldsDownwardsLikeAPositiveOne() {
		// Nothing produces one today. Folding towards zero instead would be a
		// second rule hiding inside the first, and the caller that started
		// producing negatives would inherit it silently.
		assertThat(TimeRounding.round(-7, TimePolicy.Rounding.DOWN, 15)).isEqualTo(-15);
		assertThat(TimeRounding.round(-7, TimePolicy.Rounding.UP, 15)).isZero();
		assertThat(TimeRounding.round(-8, TimePolicy.Rounding.NEAREST, 15)).isEqualTo(-15);
	}

	@Test
	void theResolvedPolicyIsReadTheSameWay() {
		assertThat(TimeRounding.round(7,
				new TimeTrackingSettings.Rounding(TimePolicy.Rounding.UP, 15))).isEqualTo(15);
		assertThat(TimeRounding.round(7, null)).isEqualTo(7);
	}
}
