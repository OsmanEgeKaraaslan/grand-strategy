package com.reach.strategy.military.api;

import java.util.Set;
import java.util.UUID;

/**
 * Who may fight whom and who may enter whose territory — implemented by the diplomacy module, so
 * the military and siege modules never depend on it. Read from authoritative state when a command
 * or a round end needs it, never from a client. Without an implementation, and for games of
 * rulesets before v5, every pair of different nations is permanently at war and movement is
 * unrestricted (the pre-diplomacy rule, GAME_DESIGN §11.1): {@link Relations#TOTAL_WAR}.
 */
public interface Hostility {

	Relations relations(UUID gameId);

	/** An unordered pair of nations. */
	record Pair(UUID a, UUID b) {

		public static Pair of(UUID x, UUID y) {
			return x.compareTo(y) <= 0 ? new Pair(x, y) : new Pair(y, x);
		}

	}

	/** Directed: {@code grantor} lets {@code grantee}'s battalions enter its territory. */
	record Access(UUID grantor, UUID grantee) {
	}

	/**
	 * @param totalWar the pre-diplomacy rule: every pair at war, no movement restriction
	 * @param wars pairs at war in an active war
	 * @param access military access from alliances or a shared war side
	 */
	record Relations(boolean totalWar, Set<Pair> wars, Set<Access> access) {

		public static final Relations TOTAL_WAR = new Relations(true, Set.of(), Set.of());

		public Relations {
			wars = Set.copyOf(wars);
			access = Set.copyOf(access);
		}

		public boolean atWar(UUID x, UUID y) {
			if (x.equals(y)) {
				return false;
			}
			return totalWar || wars.contains(Pair.of(x, y));
		}

		/**
		 * Whether battalions of {@code nation} may enter a province: their own (owned or controlled)
		 * territory, territory of a nation they are at war with, or territory of a nation granting them
		 * military access. Neutral foreign territory is closed.
		 */
		public boolean mayEnter(UUID nation, UUID ownerNationId, UUID controllerNationId) {
			if (totalWar || nation.equals(ownerNationId) || nation.equals(controllerNationId)) {
				return true;
			}
			if (atWar(nation, ownerNationId) || atWar(nation, controllerNationId)) {
				return true;
			}
			// Access is granted by whoever holds the province now.
			return access.contains(new Access(controllerNationId, nation));
		}

	}

}
