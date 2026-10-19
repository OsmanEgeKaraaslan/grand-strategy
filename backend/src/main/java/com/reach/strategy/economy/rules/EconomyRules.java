package com.reach.strategy.economy.rules;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Economy balance values of one ruleset version (resources {@code rulesets/<version>/economy.json}).
 * Amounts are milligold (1 gold = 1000 mg); rates are per person per global round, which keeps every
 * approved rate an exact integer (docs/ECONOMY.md §1.2).
 *
 * @param agricultureMgPerWorker income of one agricultural worker (APPROVED: 1 gold per 1000)
 * @param workshopCapacity workers per workshop of any tier (APPROVED: 4000)
 * @param maxWorkshopsPerCity workshops per city, counting projects (Q-E2: 6)
 * @param startingTreasuryMg treasury of each nation at game start (Q-W3: 50 gold)
 * @param startingCapitalWorkshops active Tier 1 workshops in each capital at game start (Q-W3: 1)
 * @param workshopTiers per tier: rate, cost, duration and whether players may build it
 * @param workshopRepair repair price and duration of damaged workshops; null in rulesets before v3,
 * whose games have no sieges and therefore never have damaged workshops
 * @param workshopUpgrade the Tier 1 → Tier 2 upgrade (v4+, needs civilian technology); null before
 * @param populationGrowth civilian growth per round (v4+); null before v4: no growth (Q-E6 MVP default)
 */
public record EconomyRules(String version, long agricultureMgPerWorker, int workshopCapacity, int maxWorkshopsPerCity,
		long startingTreasuryMg, int startingCapitalWorkshops, Map<Integer, WorkshopTier> workshopTiers,
		WorkshopRepair workshopRepair, WorkshopUpgrade workshopUpgrade, GrowthRules populationGrowth) {

	public EconomyRules {
		if (agricultureMgPerWorker < 0 || workshopCapacity <= 0 || maxWorkshopsPerCity < 0 || startingTreasuryMg < 0
				|| startingCapitalWorkshops < 0 || startingCapitalWorkshops > maxWorkshopsPerCity) {
			throw new IllegalArgumentException("Invalid economy ruleset " + version);
		}
		if (workshopTiers == null || !workshopTiers.containsKey(1)) {
			throw new IllegalArgumentException("Economy ruleset " + version + " must define workshop tier 1");
		}
		workshopTiers = Map.copyOf(workshopTiers);
		if (workshopUpgrade != null && (!workshopTiers.containsKey(workshopUpgrade.fromTier())
				|| !workshopTiers.containsKey(workshopUpgrade.toTier()))) {
			throw new IllegalArgumentException("Economy ruleset " + version + " upgrades between unknown tiers");
		}
	}

	/**
	 * @param mgPerWorker income of one worker employed at this tier (APPROVED: T1 3 mg, T2 5 mg)
	 * @param buildCostMg cost of a new building of this tier (Q-E7, provisional: T1 40 gold)
	 * @param buildDurationRounds construction duration d (Q-E8, provisional: 1)
	 * @param constructible whether players may order it now (Tier 2 needs civilian technology)
	 */
	public record WorkshopTier(long mgPerWorker, long buildCostMg, int buildDurationRounds, boolean constructible) {

		public WorkshopTier {
			if (mgPerWorker < 0 || buildCostMg < 0 || buildDurationRounds < 1) {
				throw new IllegalArgumentException("Invalid workshop tier");
			}
		}

	}

	/**
	 * @param costBps share of the tier's build cost a repair costs (APPROVED 30%: 3000)
	 * @param durationRounds APPROVED 1 round, Q-E8 timing
	 */
	public record WorkshopRepair(int costBps, int durationRounds) {

		public WorkshopRepair {
			if (costBps < 0 || durationRounds < 1) {
				throw new IllegalArgumentException("Invalid workshop repair rules");
			}
		}

	}

	/**
	 * @param costMg price of upgrading one workshop (PROPOSED 60 gold)
	 * @param durationRounds Q-E8 duration (PROPOSED 1)
	 */
	public record WorkshopUpgrade(int fromTier, int toTier, long costMg, int durationRounds) {

		public WorkshopUpgrade {
			if (toTier <= fromTier || costMg < 0 || durationRounds < 1) {
				throw new IllegalArgumentException("Invalid workshop upgrade rules");
			}
		}

	}

	/** @param baseBps growth per round of an eligible city in basis points (PROVISIONAL 50 = 0.5%) */
	public record GrowthRules(int baseBps) {

		public GrowthRules {
			if (baseBps < 0 || baseBps > 10_000) {
				throw new IllegalArgumentException("Invalid population growth rules");
			}
		}

	}

	public WorkshopTier tier(int tier) {
		WorkshopTier rule = workshopTiers.get(tier);
		if (rule == null) {
			throw new IllegalArgumentException("Unknown workshop tier " + tier);
		}
		return rule;
	}

	/** Tiers from most to least productive — the order in which workers fill workshops. */
	public NavigableMap<Integer, WorkshopTier> tiersByPriority() {
		TreeMap<Integer, WorkshopTier> sorted = new TreeMap<>((a, b) -> {
			int byRate = Long.compare(workshopTiers.get(b).mgPerWorker(), workshopTiers.get(a).mgPerWorker());
			return byRate != 0 ? byRate : Integer.compare(b, a);
		});
		sorted.putAll(workshopTiers);
		return sorted;
	}

}
