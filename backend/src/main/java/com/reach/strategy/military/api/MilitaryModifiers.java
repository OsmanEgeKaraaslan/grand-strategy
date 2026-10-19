package com.reach.strategy.military.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A nation's technology and doctrine effects on military rules — implemented by the research
 * module, so the military and siege modules never depend on it. Read from authoritative state at
 * the moment a battle, a siege round or an upkeep settlement is calculated; never from a client.
 * Without an implementation (or for games before ruleset v4) every nation has {@link #NONE}.
 */
public interface MilitaryModifiers {

	NationModifiers NONE = new NationModifiers(1, List.of(), Map.of(), 0, 0, 0, 0);

	NationModifiers of(UUID gameId, UUID nationId);

	/**
	 * @param damageBonusBps extra damage per unit type name (military technology)
	 * @param riverPenaltyReductionBps share of the river attack penalty removed (doctrine)
	 * @param mountainPenaltyReductionBps share of the mountain attack penalty removed (doctrine)
	 * @param siegeArtilleryBonusBps extra artillery siege progress (doctrine)
	 * @param upkeepReductionBps battalion upkeep reduction (doctrine)
	 */
	record NationModifiers(int militaryLevel, List<String> doctrines, Map<String, Integer> damageBonusBps,
			int riverPenaltyReductionBps, int mountainPenaltyReductionBps, int siegeArtilleryBonusBps,
			int upkeepReductionBps) {

		public NationModifiers {
			doctrines = List.copyOf(doctrines);
			damageBonusBps = Map.copyOf(damageBonusBps);
		}

	}

}
