package com.reach.strategy.social.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.reach.strategy.social.rules.MatchStatistics.Match;
import com.reach.strategy.social.rules.MatchStatistics.Outcome;
import org.junit.jupiter.api.Test;

class MatchStatisticsTest {

	private static Match match(Outcome outcome, String faction) {
		return new Match(outcome, faction, faction.toUpperCase());
	}

	@Test
	void noCompletedMatchesMeansNoWinRateAndNoFavourite() {
		MatchStatistics.Summary s = MatchStatistics.of(List.of());
		assertThat(s.completed()).isZero();
		assertThat(s.winRateBps()).isNull();
		assertThat(s.favouriteFaction()).isEmpty();
	}

	@Test
	void countsWinsLossesAndDrawsAndRoundsTheWinRateDown() {
		MatchStatistics.Summary s = MatchStatistics.of(List.of(match(Outcome.WIN, "varn"), match(Outcome.LOSS, "varn"),
				match(Outcome.DRAW, "ostra")));
		assertThat(s.completed()).isEqualTo(3);
		assertThat(s.wins()).isEqualTo(1);
		assertThat(s.losses()).isEqualTo(1);
		assertThat(s.draws()).isEqualTo(1);
		// 1 / 3 = 33.33…% → 3333 basis points (rounded down)
		assertThat(s.winRateBps()).isEqualTo(3333);
	}

	@Test
	void theFavouriteIsTheMostPlayedFactionThenMoreWinsThenTheKey() {
		assertThat(MatchStatistics.of(List.of(match(Outcome.LOSS, "ostra"), match(Outcome.LOSS, "varn"),
				match(Outcome.LOSS, "varn"))).favouriteFaction().orElseThrow().key()).isEqualTo("varn");
		assertThat(MatchStatistics.of(List.of(match(Outcome.LOSS, "varn"), match(Outcome.WIN, "ostra")))
			.favouriteFaction()
			.orElseThrow()
			.key()).as("tie on games, more wins").isEqualTo("ostra");
		assertThat(MatchStatistics.of(List.of(match(Outcome.LOSS, "varn"), match(Outcome.LOSS, "ostra")))
			.favouriteFaction()
			.orElseThrow()
			.key()).as("full tie: alphabetical").isEqualTo("ostra");
	}

	@Test
	void listsMatchesPerFaction() {
		MatchStatistics.Summary s = MatchStatistics.of(List.of(match(Outcome.WIN, "varn"), match(Outcome.WIN, "varn"),
				match(Outcome.LOSS, "ostra")));
		assertThat(s.factions()).containsExactly(new MatchStatistics.FactionRecord("varn", "VARN", 2, 2),
				new MatchStatistics.FactionRecord("ostra", "OSTRA", 1, 0));
	}

	@Test
	void everyWinIsCountedOnce() {
		MatchStatistics.Summary s = MatchStatistics.of(List.of(match(Outcome.WIN, "varn")));
		assertThat(s.winRateBps()).isEqualTo(10_000);
	}

}
