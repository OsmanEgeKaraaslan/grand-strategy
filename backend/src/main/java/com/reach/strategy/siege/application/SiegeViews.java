package com.reach.strategy.siege.application;

import java.util.List;
import java.util.UUID;

/** Read models and command results of the siege module (amounts in milligold). */
public final class SiegeViews {

	private SiegeViews() {
	}

	/**
	 * @param fortificationsAvailable whether the game's ruleset has fortifications (false for games
	 * created before Phase 7)
	 */
	public record Overview(UUID gameId, int round, boolean fortificationsAvailable,
			List<FortificationView> fortifications, List<SiegeSummary> activeSieges, List<SiegeSummary> recentSieges) {
	}

	/**
	 * @param effectiveLevel what a siege has to overcome: the level while ACTIVE, 0 while DAMAGED
	 */
	public record FortificationView(UUID cityId, String provinceKey, String cityName, int level, int effectiveLevel,
			String status, int integrity, int maxIntegrity, ProjectView project) {
	}

	/** @param kind UPGRADE (including building the first level) or REPAIR */
	public record ProjectView(String kind, Integer targetLevel, int completesAfterRound, int operationalFromRound) {
	}

	/**
	 * @param ratePerRound progress the besiegers present now would add at the next round end (active sieges)
	 * @param artilleryManpower artillery soldiers of the besieger in the province now (active sieges)
	 * @param artilleryBonusBps the besieger's Siege Engineering bonus on artillery progress (active sieges)
	 * @param estimatedRoundsLeft round ends until the fall at the current rate (active sieges)
	 * @param nextAttrition civilians the next round end would cost the city (active sieges)
	 */
	public record SiegeSummary(UUID id, String provinceKey, String cityName, UUID cityId, UUID besiegerNationId,
			UUID defenderNationId, String status, int startedRound, Integer endedRound, String endReason,
			int fortressLevel, int requiredProgress, int progress, Integer ratePerRound, Integer artilleryManpower,
			Integer artilleryBonusBps, Integer estimatedRoundsLeft, Integer nextAttrition, int populationLost, Boolean artillerySiege,
			UUID damagedBuildingId) {
	}

	public record SiegeDetails(SiegeSummary siege, List<RoundView> rounds) {
	}

	public record RoundView(int round, int artilleryManpower, int progressAdded, int progressAfter,
			int populationBefore, int populationLost) {
	}

	/** Everything the province panel shows about fortification and siege, with the caller's options. */
	public record ProvinceFortification(String provinceKey, UUID cityId, String cityName, int population,
			UUID ownerNationId, UUID controllerNationId, boolean fortificationsAvailable,
			FortificationView fortification, SiegeSummary activeSiege, WorksOption upgrade, WorksOption repair) {
	}

	/**
	 * A fortress project the caller could order now, as the server would price and schedule it.
	 *
	 * @param blockers why it is not allowed (FortressWorks.Blocker names); empty if allowed
	 */
	public record WorksOption(int targetLevel, long costMg, int durationRounds, int completesAfterRound,
			int operationalFromRound, boolean allowed, List<String> blockers) {
	}

	public record WorksResult(FortificationView fortification, long costMg, long treasuryMg) {
	}

}
