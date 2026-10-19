package com.reach.strategy.economy.application;

import java.util.List;
import java.util.UUID;

/**
 * API views of the economy. All amounts are integer milligold ({@code …Mg}; 1 gold = 1000 mg),
 * computed on the server from the game's ruleset — clients never calculate or submit them.
 */
public final class EconomyViews {

	private EconomyViews() {
	}

	/** Everything the game screen needs: the caller's treasury plus every city's economy (no fog, Q-T4). */
	public record Overview(UUID gameId, int round, boolean yourTurn, Rules rules, NationEconomy nation,
			List<City> cities) {
	}

	public record Rules(String version, long agricultureMgPerWorker, int workshopCapacity, int maxWorkshopsPerCity,
			List<Tier> workshopTiers) {
	}

	public record Tier(int tier, long mgPerWorker, long buildCostMg, int buildDurationRounds, boolean constructible) {
	}

	/**
	 * The caller's nation. Treasuries of other nations are private.
	 *
	 * @param projectedGrossIncomeMg collectible income at the next round end with today's buildings
	 * @param projectedUpkeepMg upkeep at the next round end (nothing costs upkeep yet)
	 * @param lastRound settlement of the most recently completed round, or null before the first
	 */
	public record NationEconomy(UUID nationId, long treasuryMg, boolean inArrears, long projectedGrossIncomeMg,
			long projectedUpkeepMg, long projectedNetMg, LastRound lastRound) {
	}

	public record LastRound(int round, long grossIncomeMg, long upkeepMg, long deficitMg, long treasuryAfterMg,
			boolean inArrears) {
	}

	/**
	 * One city. {@code potentialIncome} is what its workers produce; {@code collectibleIncomeMg} is
	 * what its owner is credited — 0 while it is occupied ({@code incomeStatus = OCCUPIED}, Q-W2).
	 */
	public record City(UUID cityId, String provinceKey, String cityName, int population, UUID ownerNationId,
			UUID controllerNationId, String incomeStatus, Employment employment, PotentialIncome potentialIncome,
			long collectibleIncomeMg, List<Workshop> workshops, int workshopCount, ConstructionOption construction, Growth growth) {
	}

	public record Employment(int agricultureWorkers, int tier1Workers, int tier2Workers) {
	}

	public record PotentialIncome(long agricultureMg, long tier1Mg, long tier2Mg, long totalMg) {
	}

	/**
	 * @param completesAfterRound round at whose end a project completes (null for starting workshops)
	 * @param operationalFromRound first round whose income includes it (null for starting workshops)
	 */
	/**
	 * @param repairCompletesAfterRound round at whose end a running repair completes, else null
	 * @param repair quote for the viewer when the workshop is DAMAGED, else null
	 */
	public record Workshop(UUID id, int tier, String status, int orderedRound, Integer completesAfterRound,
			Integer operationalFromRound, Integer repairCompletesAfterRound, RepairOption repair,
			Integer upgradeTargetTier, Integer upgradeCompletesAfterRound, UpgradeOption upgrade) {
	}

	/**
	 * Tier 1 → 2 upgrade quote for the viewer (ACTIVE Tier 1 workshops in v4+ games).
	 *
	 * @param potentialIncomeAfterMg the city's production once the upgrade has completed, same population
	 */
	public record UpgradeOption(int targetTier, long costMg, int durationRounds, int completesAfterRound,
			int operationalFromRound, long potentialIncomeAfterMg, boolean allowed, List<String> blockers) {
	}

	public record UpgradeResult(Workshop workshop, long costMg, long treasuryMg) {
	}

	/**
	 * @param rateBps growth rate this city would get (base + civilian technology)
	 * @param nextRoundGrowth people added at the next round end if nothing changes
	 * @param blockers why it does not grow (OCCUPIED, BESIEGED)
	 */
	public record Growth(boolean eligible, int rateBps, int nextRoundGrowth, List<String> blockers) {
	}

	public record RepairOption(long costMg, int durationRounds, int completesAfterRound, int operationalFromRound,
			boolean allowed, List<String> blockers) {
	}

	public record RepairResult(Workshop workshop, long costMg, long treasuryMg) {
	}

	/**
	 * Building a Tier 1 workshop here, for the caller: price, timing, and why it is not possible if
	 * {@code allowed} is false (the server enforces the same checks when the order arrives).
	 */
	public record ConstructionOption(int tier, long costMg, int durationRounds, int completesAfterRound,
			int operationalFromRound, boolean allowed, List<String> blockers) {
	}

	public record BuildResult(Workshop workshop, long treasuryMg, City city) {
	}

}
