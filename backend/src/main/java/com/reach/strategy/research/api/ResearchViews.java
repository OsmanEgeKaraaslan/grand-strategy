package com.reach.strategy.research.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read models and command results of the research module. */
public final class ResearchViews {

	private ResearchViews() {
	}

	/**
	 * The caller's own research — private to its nation.
	 *
	 * @param available whether the game's ruleset has research (false before v4)
	 */
	public record Overview(UUID gameId, int round, boolean yourTurn, boolean available, UUID nationId,
			List<PoolView> pools, TrackView military, TrackView civilian, List<DoctrineView> doctrines,
			List<InstitutionView> institutions, Effects effects) {
	}

	/**
	 * @param perRound points the next round end adds from institutions producing now
	 * @param lastRound points gained at the last round end (null before the first)
	 */
	public record PoolView(String pool, int points, int perRound, Integer lastRound) {
	}

	/** @param next the next level as the server would sell it now; null at the maximum */
	public record TrackView(String track, int level, int maxLevel, List<LevelView> levels, Quote next) {
	}

	/**
	 * @param damageBonusBps military levels: extra damage per unit type
	 * @param unlocksWorkshopTier civilian levels: workshop tier unlocked, or null
	 * @param growthBonusBps civilian levels: extra growth per round
	 */
	public record LevelView(int level, int costPoints, boolean unlocked, Map<String, Integer> damageBonusBps,
			Integer unlocksWorkshopTier, int growthBonusBps) {
	}

	public record Quote(int targetLevel, int costPoints, boolean allowed, List<String> blockers) {
	}

	/** All values in basis points, straight from the ruleset. */
	public record DoctrineView(String key, int tier, int costPoints, int riverPenaltyReductionBps,
			int mountainPenaltyReductionBps, int siegeArtilleryBonusBps, int militaryUpkeepReductionBps, boolean chosen,
			boolean allowed, List<String> blockers) {
	}

	/** @param producing whether it adds points at the next round end (ACTIVE and its city owned and controlled) */
	public record InstitutionView(UUID id, UUID cityId, String cityName, String provinceKey, String type, String pool,
			String status, int completesAfterRound, int operationalFromRound, int pointsPerRound, long upkeepMg,
			boolean producing) {
	}

	/** The authoritative modifiers the rules engines apply for this nation now. */
	public record Effects(int militaryLevel, int civilianLevel, Map<String, Integer> damageBonusBps,
			int riverPenaltyReductionBps, int mountainPenaltyReductionBps, int siegeArtilleryBonusBps,
			int militaryUpkeepReductionBps, int growthBonusBps, int highestUnlockedWorkshopTier, List<String> doctrines) {
	}

	/** One city's institutions and what the caller could build there. */
	public record CityInstitutions(UUID cityId, String cityName, String provinceKey, boolean available,
			List<InstitutionOption> options) {
	}

	/**
	 * @param existing the city's institution of this type, if any
	 */
	public record InstitutionOption(String type, String pool, long costMg, int durationRounds, int completesAfterRound,
			int operationalFromRound, int pointsPerRound, long upkeepMg, boolean allowed, List<String> blockers,
			InstitutionView existing) {
	}

	public record InstitutionResult(InstitutionView institution, long costMg, long treasuryMg) {
	}

	public record UnlockResult(String kind, String subject, Integer level, int costPoints, Overview research) {
	}

}
