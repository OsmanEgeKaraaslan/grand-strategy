package com.reach.strategy.military.rules;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a defeated defender goes (PROVISIONAL Q-C2/Q-C3, docs/COMBAT_AND_SIEGE.md §2.8): an
 * adjacent province controlled by its own nation with no foreign battalions; the one holding most
 * of its battalions, then the lowest province key. None available: the defenders are destroyed.
 */
public final class Retreat {

	private Retreat() {
	}

	/**
	 * @param friendlyBattalions battalions of the retreating nation already there
	 * @param foreignBattalions battalions of any other nation there
	 */
	public record Candidate(String provinceKey, UUID controllerNationId, int friendlyBattalions,
			int foreignBattalions) {
	}

	public static Optional<String> destination(UUID retreatingNationId, List<Candidate> neighbours) {
		return neighbours.stream()
			.filter(c -> retreatingNationId.equals(c.controllerNationId()) && c.foreignBattalions() == 0)
			.sorted(Comparator.comparingInt(Candidate::friendlyBattalions)
				.reversed()
				.thenComparing(Candidate::provinceKey))
			.map(Candidate::provinceKey)
			.findFirst();
	}

}
