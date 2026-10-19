package com.reach.strategy.economy.rules;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Employment and income of one city (docs/ECONOMY.md §2–3). Pure functions over plain values:
 * population is an input, so anything that changes it later (recruitment, sieges, growth) only
 * has to update the city and the next calculation reflects it.
 */
public final class CityEconomy {

	private CityEconomy() {
	}

	/** A workshop as the calculation sees it. */
	public record Workshop(int tier, WorkshopStatus status) {
	}

	/**
	 * Workers of each tier, the remaining farmers, and the income they would produce.
	 *
	 * @param workersByTier employed workers per workshop tier (only tiers that exist in the ruleset)
	 * @param incomeByTierMg income per workshop tier
	 * @param potentialIncomeMg everything the city produces, collectible or not
	 */
	public record Production(int population, Map<Integer, Integer> workersByTier, int agricultureWorkers,
			Map<Integer, Long> incomeByTierMg, long agricultureIncomeMg, long potentialIncomeMg) {

		public int workersOfTier(int tier) {
			return workersByTier.getOrDefault(tier, 0);
		}

		public long incomeOfTierMg(int tier) {
			return incomeByTierMg.getOrDefault(tier, 0L);
		}

	}

	/** Why a city's production is or is not credited to a treasury. */
	public enum IncomeStatus {

		/** Owner and controller are the same nation: the owner collects it. */
		COLLECTIBLE,

		/** Owner and controller differ: nobody collects anything (APPROVED, Q-W2). */
		OCCUPIED

	}

	/** Production plus who, if anyone, collects it. */
	public record Income(Production production, IncomeStatus status, long collectibleIncomeMg) {
	}

	/**
	 * Fills workplaces from the most to the least productive tier (APPROVED): workers take active
	 * workshop slots of the best tier first, everyone left farms. Removing people therefore empties
	 * farms first, then Tier 1, then Tier 2. Only ACTIVE workshops have capacity; workers of
	 * damaged workshops and of unfinished projects farm. Income is exact: whole milligold per
	 * person, no rounding (docs/ECONOMY.md §1.2).
	 */
	public static Production production(int population, Collection<Workshop> workshops, EconomyRules rules) {
		if (population < 0) {
			throw new IllegalArgumentException("Negative population");
		}
		Map<Integer, Integer> workersByTier = new LinkedHashMap<>();
		Map<Integer, Long> incomeByTier = new LinkedHashMap<>();
		int remaining = population;
		long total = 0;
		for (var tier : rules.tiersByPriority().entrySet()) {
			long activeWorkshops = workshops.stream()
				.filter(workshop -> workshop.tier() == tier.getKey() && workshop.status() == WorkshopStatus.ACTIVE)
				.count();
			long capacity = Math.multiplyExact(activeWorkshops, (long) rules.workshopCapacity());
			int employed = (int) Math.min(remaining, capacity);
			long income = Math.multiplyExact((long) employed, tier.getValue().mgPerWorker());
			workersByTier.put(tier.getKey(), employed);
			incomeByTier.put(tier.getKey(), income);
			remaining -= employed;
			total = Math.addExact(total, income);
		}
		long agricultureIncome = Math.multiplyExact((long) remaining, rules.agricultureMgPerWorker());
		total = Math.addExact(total, agricultureIncome);
		return new Production(population, Map.copyOf(workersByTier), remaining, Map.copyOf(incomeByTier),
				agricultureIncome, total);
	}

	/**
	 * Collectible income (APPROVED, Q-W2): a city credits its production to its legal owner only
	 * while the owner also controls it. When owner and controller differ, collectible income is
	 * zero for both — the production figure is still reported so the UI can explain the loss.
	 */
	public static Income income(Production production, UUID ownerNationId, UUID controllerNationId) {
		boolean occupied = !ownerNationId.equals(controllerNationId);
		return occupied ? new Income(production, IncomeStatus.OCCUPIED, 0)
				: new Income(production, IncomeStatus.COLLECTIBLE, production.potentialIncomeMg());
	}

	/** The income a nation collects from this city: its collectible income if it owns it, else 0. */
	public static long collectedBy(UUID nationId, UUID ownerNationId, Income income) {
		return nationId.equals(ownerNationId) ? income.collectibleIncomeMg() : 0;
	}

}
