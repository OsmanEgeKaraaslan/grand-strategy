package com.reach.strategy.military.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Origin-aware manpower (PROVISIONAL design, docs/GAME_DESIGN.md §7.6): a battalion's soldiers
 * are kept as cohorts, one per origin city, whose sizes always add up to the battalion's
 * manpower. Disbanding returns each cohort to its own city, so reinforcing from city B and
 * disbanding can never move B's people to the battalion's first city.
 */
public final class Cohorts {

	private Cohorts() {
	}

	public record Cohort(UUID originCityId, int soldiers) {

		public Cohort {
			if (soldiers <= 0) {
				throw new IllegalArgumentException("A cohort needs soldiers");
			}
		}

	}

	public static int total(List<Cohort> cohorts) {
		return cohorts.stream().mapToInt(Cohort::soldiers).sum();
	}

	/** Adds soldiers from a city, merging with an existing cohort of the same origin. */
	public static List<Cohort> add(List<Cohort> cohorts, UUID originCityId, int soldiers) {
		if (soldiers <= 0) {
			throw new IllegalArgumentException("Nothing to add");
		}
		Map<UUID, Integer> merged = new LinkedHashMap<>();
		cohorts.forEach(c -> merged.merge(c.originCityId(), c.soldiers(), Integer::sum));
		merged.merge(originCityId, soldiers, Integer::sum);
		return sorted(merged);
	}

	/**
	 * Removes {@code losses} soldiers in proportion to cohort size: floors first, then the remaining
	 * deaths go to the largest fractional remainders, ties by origin city id (as text). The result
	 * totals exactly {@code total − losses}; emptied cohorts disappear.
	 */
	public static List<Cohort> afterLosses(List<Cohort> cohorts, int losses) {
		int total = total(cohorts);
		if (losses < 0 || losses > total) {
			throw new IllegalArgumentException("Invalid losses " + losses + " for " + total + " soldiers");
		}
		if (losses == 0) {
			return sorted(cohorts);
		}
		List<Cohort> ordered = new ArrayList<>(sorted(cohorts));
		long[] dead = new long[ordered.size()];
		long[] remainder = new long[ordered.size()];
		long assigned = 0;
		for (int i = 0; i < ordered.size(); i++) {
			long numerator = (long) losses * ordered.get(i).soldiers();
			dead[i] = numerator / total;
			remainder[i] = numerator % total;
			assigned += dead[i];
		}
		List<Integer> byRemainder = new ArrayList<>();
		for (int i = 0; i < ordered.size(); i++) {
			byRemainder.add(i);
		}
		// Stable sort on the id-ordered list: equal remainders keep origin-city order.
		byRemainder.sort(Comparator.comparingLong((Integer i) -> remainder[i]).reversed());
		for (int k = 0; k < losses - assigned; k++) {
			dead[byRemainder.get(k)]++;
		}
		Map<UUID, Integer> left = new LinkedHashMap<>();
		for (int i = 0; i < ordered.size(); i++) {
			int soldiers = (int) (ordered.get(i).soldiers() - dead[i]);
			if (soldiers > 0) {
				left.put(ordered.get(i).originCityId(), soldiers);
			}
		}
		return sorted(left);
	}

	/**
	 * Where the survivors of a disbanded battalion go (APPROVED, GAME_DESIGN §7.6): each cohort to
	 * its origin city if that city is still controlled by the battalion's nation, otherwise to the
	 * city of the province where it disbands.
	 *
	 * @return soldiers per receiving city, in city id order
	 */
	public static Map<UUID, Integer> disbandReturns(List<Cohort> cohorts, Set<UUID> friendlyCityIds,
			UUID localCityId) {
		Map<UUID, Integer> returns = new LinkedHashMap<>();
		for (Cohort cohort : sorted(cohorts)) {
			UUID city = friendlyCityIds.contains(cohort.originCityId()) ? cohort.originCityId() : localCityId;
			returns.merge(city, cohort.soldiers(), Integer::sum);
		}
		Map<UUID, Integer> ordered = new LinkedHashMap<>();
		returns.keySet()
			.stream()
			.sorted(Comparator.comparing(UUID::toString))
			.forEach(city -> ordered.put(city, returns.get(city)));
		return ordered;
	}

	private static List<Cohort> sorted(List<Cohort> cohorts) {
		return cohorts.stream().sorted(Comparator.comparing(c -> c.originCityId().toString())).toList();
	}

	private static List<Cohort> sorted(Map<UUID, Integer> soldiersByCity) {
		return sorted(soldiersByCity.entrySet().stream().map(e -> new Cohort(e.getKey(), e.getValue())).toList());
	}

}
