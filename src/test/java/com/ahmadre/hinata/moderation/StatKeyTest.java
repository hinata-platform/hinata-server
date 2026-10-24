package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ReportCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A counter's key reads back as it was written, and a name this version no longer knows is skipped. */
class StatKeyTest {

	@Test
	void aKeyReadsBackPartByPart() {
		String written = StatKey.of(ReportCategory.THREAT, Decision.Ground.LAW, Decision.Basis.AUTOMATED_DETECTION);

		StatKey read = StatKey.parse(written);

		assertThat(written).isEqualTo("THREAT:LAW:AUTOMATED_DETECTION");
		assertThat(read.part(0, ReportCategory.class)).isEqualTo(ReportCategory.THREAT);
		assertThat(read.part(1, Decision.Ground.class)).isEqualTo(Decision.Ground.LAW);
		assertThat(read.part(2, Decision.Basis.class)).isEqualTo(Decision.Basis.AUTOMATED_DETECTION);
		assertThat(StatKey.parse(StatKey.of(ReportCategory.SPAM, 12)).part(1)).isEqualTo("12");
	}

	@Test
	void aMissingOrUnknownPartIsNone() {
		StatKey read = StatKey.parse("RETIRED_CATEGORY:RULES");

		assertThat(read.part(0, ReportCategory.class)).isNull();
		assertThat(read.part(1, Decision.Ground.class)).isEqualTo(Decision.Ground.RULES);
		assertThat(read.part(2, Decision.Basis.class)).isNull();
		assertThat(read.part(2)).isNull();
	}
}
