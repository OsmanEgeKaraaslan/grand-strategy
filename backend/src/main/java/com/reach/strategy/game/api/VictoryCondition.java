package com.reach.strategy.game.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a game ends with the round just completed (Phase 9, PROVISIONAL Q-T2) —
 * implemented by the module that owns the ruleset's victory rules. The game module asks it once per
 * round end, after every {@link RoundPhase}, inside the same transaction; if it answers, the game
 * becomes FINISHED and the result is stored exactly once. Without an implementation, or for
 * rulesets without a victory condition, no game ever ends.
 */
public interface VictoryCondition {

	Optional<Outcome> evaluate(UUID gameId, int completedRound, String rulesetVersion);

	/**
	 * Whether the game ends right now, outside a round end (Phase 10: only one nation left, or no human
	 * player left). Without such a rule: empty.
	 */
	default Optional<Outcome> evaluateNow(UUID gameId, int currentRound, String rulesetVersion) {
		return Optional.empty();
	}

	/**
	 * @param reason stable code, e.g. {@code ROUND_LIMIT}
	 * @param winnerNationId null for a draw
	 * @param standings every nation, best first
	 * @param statistics game-level figures for the summary (e.g. wars declared, treaties signed)
	 */
	record Outcome(String reason, UUID winnerNationId, List<Standing> standings, Map<String, Long> statistics) {

		public Outcome {
			standings = List.copyOf(standings);
			statistics = Map.copyOf(statistics);
		}

	}

	/**
	 * One nation's final figures.
	 *
	 * @param rank 1 = best; equal scores share a rank
	 */
	record Standing(UUID nationId, int rank, long score, int ownedProvinces, long ownedPopulation, long treasuryMg) {
	}

}
