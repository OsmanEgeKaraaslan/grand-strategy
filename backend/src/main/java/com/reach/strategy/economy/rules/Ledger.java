package com.reach.strategy.economy.rules;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The economic state of one game as immutable values (ENGINE_DESIGN S3): the nations' treasuries and
 * the cities' buildings. Every change returns a new {@code Ledger}; unchanged rows stay the same
 * objects, so a store can persist the difference by id. A ledger may hold only part of a game (one
 * treasury, say): it is compared with the ledger it was derived from, never with the whole game.
 * Nothing is ever removed: treasuries and buildings only change.
 */
public final class Ledger {

	/** The order of {@code ORDER BY id} on a PostgreSQL uuid column (and of {@code UUID.toString()}). */
	public static final Comparator<UUID> ID_ORDER = (a, b) -> {
		int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
		return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
	};

	private static final Ledger EMPTY = new Ledger(new TreeMap<>(ID_ORDER), new TreeMap<>(ID_ORDER));

	/** A nation's treasury in milligold; never negative (also a database CHECK). */
	public record Purse(UUID nationId, long treasuryMg, boolean inArrears) {

		public Purse {
			if (treasuryMg < 0) {
				throw new IllegalArgumentException("Negative treasury " + treasuryMg + " of " + nationId);
			}
		}

		public static Purse opening(UUID nationId, long treasuryMg) {
			return new Purse(nationId, treasuryMg, false);
		}

		/** Pays for something; the caller has checked affordability. */
		public Purse spend(long amountMg) {
			if (amountMg < 0 || amountMg > treasuryMg) {
				throw new IllegalStateException("Cannot spend " + amountMg + " mg from " + treasuryMg + " mg");
			}
			return new Purse(nationId, treasuryMg - amountMg, inArrears);
		}

		/** Receives gold another nation paid (reparations, tribute): never created, only moved. */
		public Purse receive(long amountMg) {
			if (amountMg < 0) {
				throw new IllegalArgumentException("Cannot receive " + amountMg + " mg");
			}
			return new Purse(nationId, Math.addExact(treasuryMg, amountMg), inArrears);
		}

		public Purse settled(Treasury.Settlement settlement) {
			return new Purse(nationId, settlement.treasuryAfterMg(), settlement.inArrears());
		}

	}

	/**
	 * A building of a city: a workshop, whose {@code tier} is stored as {@code level}. Upgrade and
	 * repair fields describe the running or last project.
	 */
	public record Building(UUID id, UUID cityId, int tier, WorkshopStatus status, int orderedRound,
			Integer completesAfterRound, long costPaidMg, Instant createdAt, Integer repairOrderedRound,
			Integer repairCompletesAfterRound, long repairCostPaidMg, Integer upgradeTargetTier, Integer upgradeOrderedRound,
			Integer upgradeCompletesAfterRound, long upgradeCostPaidMg) {

		/** A workshop that exists when the game starts (round 0, nothing paid). */
		public static Building startingWorkshop(UUID id, UUID cityId, int tier, Instant now) {
			return new Building(id, cityId, tier, WorkshopStatus.ACTIVE, 0, null, 0, now, null, null, 0, null, null, null,
					0);
		}

		/** A paid construction project. */
		public static Building workshopProject(UUID id, UUID cityId, int tier, int orderedRound, int completesAfterRound,
				long costPaidMg, Instant now) {
			return new Building(id, cityId, tier, WorkshopStatus.UNDER_CONSTRUCTION, orderedRound, completesAfterRound,
					costPaidMg, now, null, null, 0, null, null, null, 0);
		}

		public boolean upgrading() {
			return upgradeTargetTier != null;
		}

		public CityEconomy.Workshop asWorkshop() {
			return new CityEconomy.Workshop(tier, status);
		}

		/** Round-end completion of a project. */
		public Building completed() {
			require(status == WorkshopStatus.UNDER_CONSTRUCTION, "is not under construction");
			return withStatus(WorkshopStatus.ACTIVE);
		}

		/**
		 * Artillery capture (COMBAT_AND_SIEGE §5.6): the workshop keeps its record but employs nobody
		 * until repaired. A running upgrade is lost with the damage (paid, not refunded; PROVISIONAL).
		 */
		public Building damaged() {
			require(status == WorkshopStatus.ACTIVE, "is not ACTIVE and cannot be damaged");
			return new Building(id, cityId, tier, WorkshopStatus.DAMAGED, orderedRound, completesAfterRound, costPaidMg,
					createdAt, repairOrderedRound, repairCompletesAfterRound, repairCostPaidMg, null, null, null,
					upgradeCostPaidMg);
		}

		/** A paid repair (already validated); it completes at the end of {@code completesAfterRound}. */
		public Building repairStarted(int orderedRound, int completesAfterRound, long costMg) {
			require(status == WorkshopStatus.DAMAGED, "is not damaged");
			return new Building(id, cityId, tier, WorkshopStatus.UNDER_REPAIR, this.orderedRound, this.completesAfterRound,
					costPaidMg, createdAt, orderedRound, completesAfterRound, costMg, upgradeTargetTier, upgradeOrderedRound,
					upgradeCompletesAfterRound, upgradeCostPaidMg);
		}

		/** Round-end completion of a repair. */
		public Building repairCompleted() {
			require(status == WorkshopStatus.UNDER_REPAIR, "is not under repair");
			return withStatus(WorkshopStatus.ACTIVE);
		}

		/**
		 * A paid upgrade (already validated). The workshop stays ACTIVE and keeps producing at its current
		 * tier until the upgrade completes at the end of {@code completesAfterRound} (Q-E3).
		 */
		public Building upgradeStarted(int targetTier, int orderedRound, int completesAfterRound, long costMg) {
			require(status == WorkshopStatus.ACTIVE && upgradeTargetTier == null && targetTier == tier + 1,
					"cannot be upgraded now");
			return new Building(id, cityId, tier, status, this.orderedRound, this.completesAfterRound, costPaidMg, createdAt,
					repairOrderedRound, repairCompletesAfterRound, repairCostPaidMg, targetTier, orderedRound,
					completesAfterRound, costMg);
		}

		/** Round-end completion: the same workshop is now of the higher tier. */
		public Building upgradeCompleted() {
			require(upgradeTargetTier != null && status == WorkshopStatus.ACTIVE, "has no upgrade to complete");
			return new Building(id, cityId, upgradeTargetTier, status, orderedRound, completesAfterRound, costPaidMg,
					createdAt, repairOrderedRound, repairCompletesAfterRound, repairCostPaidMg, null, null, null,
					upgradeCostPaidMg);
		}

		private Building withStatus(WorkshopStatus newStatus) {
			return new Building(id, cityId, tier, newStatus, orderedRound, completesAfterRound, costPaidMg, createdAt,
					repairOrderedRound, repairCompletesAfterRound, repairCostPaidMg, upgradeTargetTier, upgradeOrderedRound,
					upgradeCompletesAfterRound, upgradeCostPaidMg);
		}

		private void require(boolean condition, String problem) {
			if (!condition) {
				throw new IllegalStateException("Building " + id + " " + problem);
			}
		}

	}

	private final SortedMap<UUID, Purse> purses;
	private final SortedMap<UUID, Building> buildings;

	private Ledger(SortedMap<UUID, Purse> purses, SortedMap<UUID, Building> buildings) {
		this.purses = Collections.unmodifiableSortedMap(purses);
		this.buildings = Collections.unmodifiableSortedMap(buildings);
	}

	public static Ledger empty() {
		return EMPTY;
	}

	public static Ledger of(Collection<Purse> purses, Collection<Building> buildings) {
		SortedMap<UUID, Purse> p = new TreeMap<>(ID_ORDER);
		purses.forEach(purse -> p.put(purse.nationId(), purse));
		SortedMap<UUID, Building> b = new TreeMap<>(ID_ORDER);
		buildings.forEach(building -> b.put(building.id(), building));
		return new Ledger(p, b);
	}

	// --- reading ------------------------------------------------------------------------------

	/** Every treasury in nation id order. */
	public List<Purse> purses() {
		return List.copyOf(purses.values());
	}

	public Purse purse(UUID nationId) {
		Purse purse = purses.get(nationId);
		if (purse == null) {
			throw new IllegalStateException("Nation " + nationId + " has no treasury");
		}
		return purse;
	}

	/** Every building in id order. */
	public List<Building> buildings() {
		return List.copyOf(buildings.values());
	}

	public Optional<Building> building(UUID id) {
		return Optional.ofNullable(buildings.get(id));
	}

	// --- changes ------------------------------------------------------------------------------

	/** A new treasury (game start). */
	public Ledger open(Purse purse) {
		if (purses.containsKey(purse.nationId())) {
			throw new IllegalArgumentException("Nation " + purse.nationId() + " already has a treasury");
		}
		return withPurse(purse);
	}

	public Ledger spend(UUID nationId, long amountMg) {
		return withPurse(purse(nationId).spend(amountMg));
	}

	/** Moves gold between two treasuries; nothing is created or lost. */
	public Ledger transfer(UUID fromNationId, UUID toNationId, long amountMg) {
		if (amountMg < 0 || fromNationId.equals(toNationId)) {
			throw new IllegalArgumentException("Invalid transfer of " + amountMg + " mg");
		}
		return spend(fromNationId, amountMg).withPurse(purse(toNationId).receive(amountMg));
	}

	public Ledger settle(UUID nationId, Treasury.Settlement settlement) {
		return withPurse(purse(nationId).settled(settlement));
	}

	/** A new building (a paid project or a starting workshop). */
	public Ledger build(Building building) {
		if (buildings.containsKey(building.id())) {
			throw new IllegalArgumentException("Building " + building.id() + " already exists");
		}
		return withBuilding(building);
	}

	/** Replaces an existing building (completion, damage, repair, upgrade). */
	public Ledger update(Building building) {
		if (!buildings.containsKey(building.id())) {
			throw new IllegalArgumentException("Unknown building " + building.id());
		}
		return withBuilding(building);
	}

	private Ledger withPurse(Purse purse) {
		SortedMap<UUID, Purse> p = new TreeMap<>(purses);
		p.put(purse.nationId(), purse);
		return new Ledger(p, buildings);
	}

	private Ledger withBuilding(Building building) {
		SortedMap<UUID, Building> b = new TreeMap<>(buildings);
		b.put(building.id(), building);
		return new Ledger(purses, b);
	}

}
