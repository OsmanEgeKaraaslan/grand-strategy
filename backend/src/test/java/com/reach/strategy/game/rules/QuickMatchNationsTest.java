package com.reach.strategy.game.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.reach.strategy.game.rules.QuickMatchNations.Candidate;
import com.reach.strategy.game.rules.QuickMatchNations.Pair;
import org.junit.jupiter.api.Test;

class QuickMatchNationsTest {

	/** A line of provinces p0 – p1 – … – p9. */
	private static List<String> line(String province) {
		int i = Integer.parseInt(province.substring(1));
		return i == 0 ? List.of("p1") : i == 9 ? List.of("p8") : List.of("p" + (i - 1), "p" + (i + 1));
	}

	private static final List<Candidate> NATIONS = List.of(new Candidate("west", "POWER", true, "p0"),
			new Candidate("centre", "POWER", true, "p2"), new Candidate("east", "POWER", true, "p9"),
			new Candidate("rich", "MAJOR", false, "p5"), new Candidate("small", "MINOR", true, "p7"));

	@Test
	void onlyPlayableNationsOfTheLargestTierThatStartFarApart() {
		Set<Set<String>> seen = new HashSet<>();
		for (long seed = 0; seed < 40; seed++) {
			Pair pair = QuickMatchNations.choose(NATIONS, QuickMatchNationsTest::line, seed);
			assertThat(pair.first()).isNotEqualTo(pair.second());
			assertThat(Set.of(pair.first(), pair.second())).isSubsetOf("west", "centre", "east");
			assertThat(pair.capitalDistance()).isGreaterThanOrEqualTo(QuickMatchNations.PREFERRED_CAPITAL_DISTANCE);
			seen.add(Set.of(pair.first(), pair.second()));
		}
		// west–centre are only two borders apart; the other two pairs both occur.
		assertThat(seen).containsExactlyInAnyOrder(Set.of("west", "east"), Set.of("centre", "east"));
	}

	@Test
	void theSeedAlsoDecidesWhoGetsWhichFactionAndIsReproducible() {
		Set<String> firstFactions = new HashSet<>();
		for (long seed = 0; seed < 8; seed++) {
			firstFactions.add(QuickMatchNations.choose(NATIONS, QuickMatchNationsTest::line, seed).first());
			assertThat(QuickMatchNations.choose(NATIONS, QuickMatchNationsTest::line, seed))
				.isEqualTo(QuickMatchNations.choose(NATIONS, QuickMatchNationsTest::line, seed));
		}
		assertThat(firstFactions).hasSizeGreaterThan(1);
	}

	@Test
	void fallsBackToEveryPairWhenNoneStartsFarApart() {
		List<Candidate> neighbours = List.of(new Candidate("a", "POWER", true, "p0"), new Candidate("b", "POWER", true, "p1"));
		Pair pair = QuickMatchNations.choose(neighbours, QuickMatchNationsTest::line, 3);
		assertThat(Set.of(pair.first(), pair.second())).containsExactlyInAnyOrder("a", "b");
	}

	@Test
	void refusesMapsWithoutTwoComparableHumanNations() {
		assertThatThrownBy(() -> QuickMatchNations.choose(List.of(new Candidate("a", "POWER", true, "p0"),
				new Candidate("b", "MINOR", true, "p9")), QuickMatchNationsTest::line, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void distancesAreShortestPaths() {
		Map<String, List<String>> ring = Map.of("a", List.of("b", "d"), "b", List.of("a", "c"), "c", List.of("b", "d"), "d",
				List.of("c", "a"));
		assertThat(QuickMatchNations.distance("a", "c", ring::get)).isEqualTo(2);
		assertThat(QuickMatchNations.distance("a", "a", ring::get)).isZero();
		assertThat(QuickMatchNations.distance("a", "x", k -> ring.getOrDefault(k, List.of()))).isEqualTo(Integer.MAX_VALUE);
	}

}
