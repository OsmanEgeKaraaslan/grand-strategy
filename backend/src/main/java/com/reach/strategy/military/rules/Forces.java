package com.reach.strategy.military.rules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The military state of one game as immutable values (ENGINE_DESIGN S2c): battalions, armies and the
 * per-origin cohorts of every battalion. Every change returns a new {@code Forces}; unchanged
 * battalions stay the same objects, so a store can persist the difference by id. Iteration is in
 * id order as the database sorts uuids (unsigned, byte by byte), so results never depend on load
 * order (AGENTS.md §3).
 *
 * <p>Invariant, checked on every change: the cohorts of a battalion add up to exactly its manpower.
 */
public final class Forces {

	/** The order of {@code ORDER BY id} on a PostgreSQL uuid column (and of {@code UUID.toString()}). */
	public static final Comparator<UUID> ID_ORDER = (a, b) -> {
		int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
		return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
	};

	private static final Forces EMPTY = new Forces(new TreeMap<>(ID_ORDER), new TreeMap<>(ID_ORDER), new TreeMap<>(ID_ORDER));

	/** A battalion (see the table {@code battalion} for the meaning of each field). */
	public record Unit(UUID id, UUID nationId, UnitType unitType, int manpower, int maxManpower, UUID originCityId,
			UUID provinceStateId, UUID armyId, int equipmentLevel, long recruitmentCostMg, int recruitedRound,
			int recruitedTurnNumber, Integer lastMovedTurnNumber, Integer lastReinforcedTurnNumber, Integer lastMovedRound,
			Integer lastReinforcedRound, UUID commanderNationId, int reinforcedThisTurn, Instant createdAt) {

		public Unit {
			if (manpower <= 0 || manpower > maxManpower) {
				throw new IllegalArgumentException("Invalid manpower " + manpower + "/" + maxManpower + " of " + id);
			}
		}

		/**
		 * A new full-strength battalion in its origin city's province.
		 *
		 * @param recruitedTurnNumber the turn it may not move in (0 for starting forces: they are ready)
		 */
		public static Unit raised(UUID id, UUID nationId, UnitType type, int manpower, UUID originCityId,
				UUID provinceStateId, long costMg, int round, int recruitedTurnNumber, Instant now) {
			return new Unit(id, nationId, type, manpower, manpower, originCityId, provinceStateId, null, 0, costMg, round,
					recruitedTurnNumber, null, null, null, null, null, 0, now);
		}

		public Movement.Eligibility eligibility(Movement.Clock clock) {
			return Movement.eligibility(recruitedTurnNumber, recruitedRound, lastMovedTurnNumber, lastMovedRound,
					lastReinforcedTurnNumber, lastReinforcedRound, clock);
		}

		/** Who gives this battalion orders: its owner, or the ally it is attached to. */
		public UUID commander() {
			return commanderNationId == null ? nationId : commanderNationId;
		}

		/** Spends this round's (or turn's) movement; the province stays the same if the attack failed. */
		public Unit movedTo(UUID provinceStateId, Movement.Clock clock) {
			return new Unit(id, nationId, unitType, manpower, maxManpower, originCityId, provinceStateId, armyId,
					equipmentLevel, recruitmentCostMg, recruitedRound, recruitedTurnNumber, clock.turnNumber(),
					lastReinforcedTurnNumber, clock.roundBased() ? Integer.valueOf(clock.round()) : lastMovedRound,
					lastReinforcedRound, commanderNationId, reinforcedThisTurn, createdAt);
		}

		/** Relocates without spending movement (retreat, withdrawal); the army is kept or left as given. */
		public Unit relocatedTo(UUID provinceStateId, UUID armyId) {
			return new Unit(id, nationId, unitType, manpower, maxManpower, originCityId, provinceStateId, armyId,
					equipmentLevel, recruitmentCostMg, recruitedRound, recruitedTurnNumber, lastMovedTurnNumber,
					lastReinforcedTurnNumber, lastMovedRound, lastReinforcedRound, commanderNationId, reinforcedThisTurn,
					createdAt);
		}

		public Unit inArmy(UUID armyId) {
			return relocatedTo(provinceStateId, armyId);
		}

		/** Under an ally's command; it leaves its (national) army. Movement is unchanged. */
		public Unit attachedTo(UUID commander) {
			return withCommander(commander).inArmy(null);
		}

		public Unit detached() {
			return withCommander(null);
		}

		private Unit withCommander(UUID commander) {
			return new Unit(id, nationId, unitType, manpower, maxManpower, originCityId, provinceStateId, armyId,
					equipmentLevel, recruitmentCostMg, recruitedRound, recruitedTurnNumber, lastMovedTurnNumber,
					lastReinforcedTurnNumber, lastMovedRound, lastReinforcedRound, commander, reinforcedThisTurn, createdAt);
		}

		/** Soldiers this battalion has already received in the given player turn. */
		public int reinforcedInTurn(int turnNumber) {
			return lastReinforcedTurnNumber != null && lastReinforcedTurnNumber == turnNumber ? reinforcedThisTurn : 0;
		}

		/**
		 * Adds replacements (already validated and paid). Spends the battalion's movement and attack for
		 * this turn (PROVISIONAL tactical rule); it still defends.
		 */
		public Unit reinforcedBy(int soldiers, Movement.Clock clock) {
			if (soldiers <= 0 || manpower + soldiers > maxManpower) {
				throw new IllegalArgumentException("Cannot add " + soldiers + " to " + manpower + "/" + maxManpower);
			}
			return new Unit(id, nationId, unitType, manpower + soldiers, maxManpower, originCityId, provinceStateId,
					armyId, equipmentLevel, recruitmentCostMg, recruitedRound, recruitedTurnNumber, lastMovedTurnNumber,
					clock.turnNumber(), lastMovedRound,
					clock.roundBased() ? Integer.valueOf(clock.round()) : lastReinforcedRound, commanderNationId,
					reinforcedInTurn(clock.turnNumber()) + soldiers, createdAt);
		}

		/** Survivors after losses smaller than its manpower (a wiped-out battalion is removed instead). */
		public Unit afterLosses(int losses) {
			if (losses < 0 || losses >= manpower) {
				throw new IllegalArgumentException("Invalid losses " + losses + " for " + manpower + " soldiers");
			}
			return new Unit(id, nationId, unitType, manpower - losses, maxManpower, originCityId, provinceStateId,
					armyId, equipmentLevel, recruitmentCostMg, recruitedRound, recruitedTurnNumber, lastMovedTurnNumber,
					lastReinforcedTurnNumber, lastMovedRound, lastReinforcedRound, commanderNationId, reinforcedThisTurn,
					createdAt);
		}

	}

	/** An army: an organisational label for co-located battalions of one nation (PROVISIONAL Q-M6). */
	public record Formation(UUID id, UUID nationId, String name, Instant createdAt) {
	}

	private final SortedMap<UUID, Unit> units;
	private final SortedMap<UUID, Formation> formations;
	private final SortedMap<UUID, List<Cohorts.Cohort>> cohorts;

	private Forces(SortedMap<UUID, Unit> units, SortedMap<UUID, Formation> formations,
			SortedMap<UUID, List<Cohorts.Cohort>> cohorts) {
		this.units = Collections.unmodifiableSortedMap(units);
		this.formations = Collections.unmodifiableSortedMap(formations);
		this.cohorts = Collections.unmodifiableSortedMap(cohorts);
	}

	public static Forces empty() {
		return EMPTY;
	}

	/** Forces as stored; every battalion must come with cohorts that add up to its manpower. */
	public static Forces of(Collection<Unit> units, Collection<Formation> formations,
			Map<UUID, List<Cohorts.Cohort>> cohorts) {
		SortedMap<UUID, Unit> u = new TreeMap<>(ID_ORDER);
		units.forEach(unit -> u.put(unit.id(), unit));
		SortedMap<UUID, Formation> f = new TreeMap<>(ID_ORDER);
		formations.forEach(formation -> f.put(formation.id(), formation));
		SortedMap<UUID, List<Cohorts.Cohort>> c = new TreeMap<>(ID_ORDER);
		u.values().forEach(unit -> c.put(unit.id(), checked(unit, cohorts.getOrDefault(unit.id(), List.of()))));
		return new Forces(u, f, c);
	}

	// --- reading ------------------------------------------------------------------------------

	/** Every battalion in id order. */
	public List<Unit> units() {
		return List.copyOf(units.values());
	}

	public Optional<Unit> unit(UUID id) {
		return Optional.ofNullable(units.get(id));
	}

	/** The battalions with these ids that exist, in id order. */
	public List<Unit> units(Collection<UUID> ids) {
		return units.values().stream().filter(u -> ids.contains(u.id())).toList();
	}

	/** Battalions standing in a province, in id order. */
	public List<Unit> in(UUID provinceStateId) {
		return units.values().stream().filter(u -> u.provinceStateId().equals(provinceStateId)).toList();
	}

	public List<Unit> membersOf(UUID armyId) {
		return units.values().stream().filter(u -> armyId.equals(u.armyId())).toList();
	}

	/** Every army in id order. */
	public List<Formation> formations() {
		return List.copyOf(formations.values());
	}

	public Optional<Formation> formation(UUID id) {
		return Optional.ofNullable(formations.get(id));
	}

	/** The cohorts of a battalion, ordered by origin city id. */
	public List<Cohorts.Cohort> cohortsOf(UUID unitId) {
		List<Cohorts.Cohort> list = cohorts.get(unitId);
		if (list == null) {
			throw new IllegalArgumentException("Unknown battalion " + unitId);
		}
		return list;
	}

	// --- changes ------------------------------------------------------------------------------

	/** A newly raised battalion: all its soldiers come from its origin city. */
	public Forces raise(Unit unit) {
		if (units.containsKey(unit.id())) {
			throw new IllegalArgumentException("Battalion " + unit.id() + " already exists");
		}
		return withUnit(unit, List.of(new Cohorts.Cohort(unit.originCityId(), unit.manpower())));
	}

	/** Replaces battalions whose soldiers did not change (moved, regrouped, attached, ...). */
	public Forces update(Collection<Unit> changed) {
		SortedMap<UUID, Unit> u = new TreeMap<>(units);
		for (Unit unit : changed) {
			Unit old = units.get(unit.id());
			if (old == null || old.manpower() != unit.manpower()) {
				throw new IllegalArgumentException("Battalion " + unit.id() + " is unknown or changed its manpower");
			}
			u.put(unit.id(), unit);
		}
		return new Forces(u, formations, cohorts);
	}

	public Forces update(Unit changed) {
		return update(List.of(changed));
	}

	/** Replacements from {@code cityId} join the battalion and the cohort of their city. */
	public Forces reinforce(UUID unitId, UUID cityId, int soldiers, Movement.Clock clock) {
		Unit unit = existing(unitId).reinforcedBy(soldiers, clock);
		return withUnit(unit, Cohorts.add(cohortsOf(unitId), cityId, soldiers));
	}

	/**
	 * Battle losses: the dead are taken from the cohorts proportionally ({@link Cohorts#afterLosses});
	 * a battalion that loses all its soldiers is removed (its dead never return to civilians).
	 */
	public Forces suffer(UUID unitId, int losses) {
		Unit unit = existing(unitId);
		if (losses >= unit.manpower()) {
			return remove(unitId);
		}
		if (losses == 0) {
			return this;
		}
		return withUnit(unit.afterLosses(losses), Cohorts.afterLosses(cohortsOf(unitId), losses));
	}

	/** The battalion leaves the game (destroyed, disbanded, dissolved) with its cohorts. */
	public Forces remove(UUID unitId) {
		existing(unitId);
		SortedMap<UUID, Unit> u = new TreeMap<>(units);
		u.remove(unitId);
		SortedMap<UUID, List<Cohorts.Cohort>> c = new TreeMap<>(cohorts);
		c.remove(unitId);
		return new Forces(u, formations, c);
	}

	public Forces form(Formation formation) {
		if (formations.containsKey(formation.id())) {
			throw new IllegalArgumentException("Army " + formation.id() + " already exists");
		}
		SortedMap<UUID, Formation> f = new TreeMap<>(formations);
		f.put(formation.id(), formation);
		return new Forces(units, f, cohorts);
	}

	public Forces disband(UUID formationId) {
		if (units.values().stream().anyMatch(u -> formationId.equals(u.armyId()))) {
			throw new IllegalArgumentException("Army " + formationId + " still has battalions");
		}
		SortedMap<UUID, Formation> f = new TreeMap<>(formations);
		f.remove(formationId);
		return new Forces(units, f, cohorts);
	}

	/** Removes those of the given armies that no battalion belongs to any more, in id order. */
	public Forces withoutEmptyFormations(Set<UUID> formationIds) {
		Forces result = this;
		for (UUID id : formationIds.stream().sorted(ID_ORDER).toList()) {
			if (result.formations.containsKey(id) && result.membersOf(id).isEmpty()) {
				result = result.disband(id);
			}
		}
		return result;
	}

	/**
	 * Keeps the army invariant "an army's battalions stand together": battalions moving without the
	 * rest of their army leave it; a whole army moving stays an army.
	 */
	public List<Unit> leavingSplitArmies(List<Unit> moving) {
		Set<UUID> movingIds = moving.stream().map(Unit::id).collect(java.util.stream.Collectors.toSet());
		List<Unit> result = new ArrayList<>();
		for (Unit unit : moving) {
			boolean split = unit.armyId() != null
					&& membersOf(unit.armyId()).stream().anyMatch(m -> !movingIds.contains(m.id()));
			result.add(split ? unit.inArmy(null) : unit);
		}
		return result;
	}

	private Unit existing(UUID unitId) {
		Unit unit = units.get(unitId);
		if (unit == null) {
			throw new IllegalArgumentException("Unknown battalion " + unitId);
		}
		return unit;
	}

	private Forces withUnit(Unit unit, List<Cohorts.Cohort> unitCohorts) {
		SortedMap<UUID, Unit> u = new TreeMap<>(units);
		u.put(unit.id(), unit);
		SortedMap<UUID, List<Cohorts.Cohort>> c = new TreeMap<>(cohorts);
		c.put(unit.id(), checked(unit, unitCohorts));
		return new Forces(u, formations, c);
	}

	private static List<Cohorts.Cohort> checked(Unit unit, List<Cohorts.Cohort> unitCohorts) {
		if (Cohorts.total(unitCohorts) != unit.manpower()) {
			throw new IllegalStateException("Cohorts of battalion " + unit.id() + " total " + Cohorts.total(unitCohorts)
					+ " but it has " + unit.manpower() + " soldiers");
		}
		return unitCohorts.stream().sorted(Comparator.comparing(c -> c.originCityId().toString())).toList();
	}

}
