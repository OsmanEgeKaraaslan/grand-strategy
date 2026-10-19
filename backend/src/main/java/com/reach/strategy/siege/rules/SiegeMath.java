package com.reach.strategy.siege.rules;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The deterministic siege calculations (docs/COMBAT_AND_SIEGE.md §5.3–§5.6, PROPOSED model with
 * versioned coefficients). Exact integer arithmetic only.
 */
public final class SiegeMath {

	private SiegeMath() {
	}

	/** Progress points needed to take a fortress of this effective level (Q-S1). */
	public static int required(int effectiveLevel, SiegeRules rules) {
		if (effectiveLevel < 1) {
			throw new IllegalArgumentException("An unfortified province is occupied, not besieged");
		}
		SiegeRules.Progress p = rules.progress();
		return Math.multiplyExact(p.requiredBase() + p.requiredPerLevel() * effectiveLevel, p.pointsPerUnit());
	}

	/**
	 * Progress gained at one round end. Artillery counts by total surviving manpower of the besiegers'
	 * artillery (summed before flooring, so splitting or regrouping batteries changes nothing): a
	 * 650-man battery adds floor(50 × 650 / 1000) = 32 points.
	 */
	public static int progressPerRound(int artilleryManpower, SiegeRules rules) {
		return progressPerRound(artilleryManpower, rules, 0);
	}

	/**
	 * With the besieger's artillery bonus (Siege Engineering doctrine, PROVISIONAL): only the artillery
	 * part grows, {@code floor(artilleryPerBattalion × manpower × (10,000 + bps) / (battalionManpower × 10,000))};
	 * a siege without artillery is not sped up.
	 */
	public static int progressPerRound(int artilleryManpower, SiegeRules rules, int artilleryBonusBps) {
		if (artilleryManpower < 0 || artilleryBonusBps < 0) {
			throw new IllegalArgumentException("Negative artillery or bonus");
		}
		SiegeRules.Progress p = rules.progress();
		// floor toward zero of a non-negative quotient (COMBAT_AND_SIEGE §5.3)
		long bonus = Math.multiplyExact((long) p.artilleryPerBattalion() * artilleryManpower, 10_000L + artilleryBonusBps)
				/ ((long) p.battalionManpower() * 10_000L);
		return Math.toIntExact(p.basePerRound() + bonus);
	}

	/** Rounds still needed at the current rate (the round being processed counts as one). */
	public static int estimatedRoundsLeft(int progress, int required, int ratePerRound) {
		if (progress >= required) {
			return 0;
		}
		return Math.ceilDiv(required - progress, ratePerRound);
	}

	/** Rounds a fresh siege takes at a constant artillery strength. */
	public static int duration(int effectiveLevel, int artilleryManpower, SiegeRules rules) {
		return estimatedRoundsLeft(0, required(effectiveLevel, rules), progressPerRound(artilleryManpower, rules));
	}

	/**
	 * Civilians lost to one round of siege (APPROVED ~5%, PROPOSED rounding §5.4):
	 * {@code floor(population × attritionBps / 10,000)} — never negative, never more than the city has,
	 * and flooring can never create people.
	 */
	public static int attrition(int population, SiegeRules rules) {
		if (population < 0) {
			throw new IllegalArgumentException("Negative population");
		}
		return Math.toIntExact((long) population * rules.attritionBps() / 10_000);
	}

	public record WorkshopCandidate(UUID buildingId, int tier, boolean active) {
	}

	/**
	 * The workshops an artillery capture damages (Q-S3, PROPOSED): ACTIVE ones only, highest tier
	 * first, ties by lowest building id (as text); at most {@code workshopsDamaged}. None if the city
	 * has no ACTIVE workshop — nothing else is damaged instead.
	 */
	public static List<UUID> workshopsToDamage(List<WorkshopCandidate> workshops, SiegeRules rules) {
		return workshops.stream()
			.filter(WorkshopCandidate::active)
			.sorted(Comparator.comparingInt(WorkshopCandidate::tier)
				.reversed()
				.thenComparing(w -> w.buildingId().toString()))
			.limit(rules.bombardment().workshopsDamaged())
			.map(WorkshopCandidate::buildingId)
			.toList();
	}

	/** Integrity left after bombardment (all of it is lost with the provisional 10,000 bps). */
	public static int integrityAfterBombardment(int integrity, SiegeRules rules) {
		long lost = Math.ceilDiv((long) integrity * rules.bombardment().fortressIntegrityLossBps(), 10_000L);
		return (int) Math.max(0, integrity - lost);
	}

	/**
	 * Repair price: {@code ceil(constructionCost × costBps / 10,000)} (APPROVED 30% for workshops,
	 * PROPOSED for fortresses Q-S5; rounded up like reinforcement so no fraction is free).
	 */
	public static long repairCost(long constructionCostMg, SiegeRules.Repair repair) {
		return Math.ceilDiv(Math.multiplyExact(constructionCostMg, (long) repair.costBps()), 10_000L);
	}

	/** Q-E8: a project of d rounds ordered in round N completes at the end of round N + d − 1. */
	public static int completesAfterRound(int orderedRound, int durationRounds) {
		if (orderedRound < 1 || durationRounds < 1) {
			throw new IllegalArgumentException("Invalid project timing");
		}
		return orderedRound + durationRounds - 1;
	}

}
