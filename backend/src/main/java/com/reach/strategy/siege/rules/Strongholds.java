package com.reach.strategy.siege.rules;

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
 * The fortresses and sieges of one game as immutable values (ENGINE_DESIGN S3). Every change returns
 * a new {@code Strongholds}; unchanged rows stay the same objects, so a store can persist the
 * difference by id. A {@code Strongholds} may hold only part of a game; it is compared with the one
 * it was derived from. Nothing is ever removed: an ended siege keeps its record.
 */
public final class Strongholds {

	/** The order of {@code ORDER BY id} on a PostgreSQL uuid column (and of {@code UUID.toString()}). */
	public static final Comparator<UUID> ID_ORDER = (a, b) -> {
		int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
		return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
	};

	private static final Strongholds EMPTY = new Strongholds(new TreeMap<>(ID_ORDER), new TreeMap<>(ID_ORDER));

	/** A city's fortress (level 0 while its first level is still being built). */
	public record Fortress(UUID id, UUID cityId, int level, FortressWorks.Status status, int integrity, int maxIntegrity,
			FortressWorks.Project project, Integer projectTargetLevel, Integer projectOrderedRound,
			Integer projectCompletesAfterRound, long projectCostMg, String rulesetVersion, Instant createdAt) {

		/** An intact fortress that exists when the game starts. */
		public static Fortress starting(UUID id, UUID cityId, int level, int maxIntegrity, String rulesetVersion,
				Instant now) {
			return new Fortress(id, cityId, level, FortressWorks.Status.ACTIVE, maxIntegrity, maxIntegrity, null, null, null,
					null, 0, rulesetVersion, now);
		}

		/** A city without a fortress that orders its first level: level 0 until the project completes. */
		public static Fortress firstLevelProject(UUID id, UUID cityId, String rulesetVersion, Instant now) {
			return new Fortress(id, cityId, 0, FortressWorks.Status.ACTIVE, 0, 0, null, null, null, null, 0, rulesetVersion,
					now);
		}

		public int effectiveLevel() {
			return FortressWorks.effectiveLevel(level, status);
		}

		public Fortress projectStarted(FortressWorks.Project kind, Integer targetLevel, int orderedRound,
				int completesAfterRound, long costMg) {
			if (project != null) {
				throw new IllegalStateException("Fortification " + id + " already has a project");
			}
			return new Fortress(id, cityId, level, status, integrity, maxIntegrity, kind, targetLevel, orderedRound,
					completesAfterRound, costMg, rulesetVersion, createdAt);
		}

		/** Round-end completion: an upgrade raises the level, a repair restores the fortress; both to full integrity. */
		public Fortress projectCompleted(int newMaxIntegrity) {
			int newLevel = project == FortressWorks.Project.UPGRADE ? projectTargetLevel : level;
			return new Fortress(id, cityId, newLevel, FortressWorks.Status.ACTIVE, newMaxIntegrity, newMaxIntegrity, null,
					null, null, null, 0, rulesetVersion, createdAt);
		}

		/** Artillery capture: the fortress is DAMAGED and an unfinished upgrade is lost (COMBAT_AND_SIEGE §5.6). */
		public Fortress bombarded(int integrityLeft) {
			Fortress damaged = new Fortress(id, cityId, level, FortressWorks.Status.DAMAGED,
					Math.max(0, Math.min(integrity, integrityLeft)), maxIntegrity, project, projectTargetLevel,
					projectOrderedRound, projectCompletesAfterRound, projectCostMg, rulesetVersion, createdAt);
			return damaged.upgradeCancelled();
		}

		/** A fall by starvation also ends a running upgrade (paid, not refunded). */
		public Fortress upgradeCancelled() {
			if (project != FortressWorks.Project.UPGRADE) {
				return this;
			}
			return new Fortress(id, cityId, level, status, integrity, maxIntegrity, null, null, null, null, 0,
					rulesetVersion, createdAt);
		}

	}

	/** A siege of a city by one nation; progress is lost when it is lifted (Q-S8, PROPOSED). */
	public record Siege(UUID id, UUID provinceStateId, UUID cityId, UUID besiegerNationId, UUID defenderNationId,
			int fortressLevel, int requiredProgress, int progress, Status status, int startedRound, int startedTurnNumber,
			Integer lastProcessedRound, Integer endedRound, EndReason endReason, Boolean artillerySiege,
			UUID damagedBuildingId, int populationLost, Instant createdAt) {

		public enum Status {

			ACTIVE, SUCCEEDED, LIFTED

		}

		public enum EndReason {

			CAPTURED,

			/** No besieging battalion left: withdrawn, destroyed, or beaten by a relief army. */
			BESIEGERS_GONE

		}

		public static Siege start(UUID id, UUID provinceStateId, UUID cityId, UUID besieger, UUID defender,
				int fortressLevel, int requiredProgress, int round, int turnNumber, Instant now) {
			return new Siege(id, provinceStateId, cityId, besieger, defender, fortressLevel, requiredProgress, 0,
					Status.ACTIVE, round, turnNumber, null, null, null, null, null, 0, now);
		}

		/** Whether this round end may still advance the siege (at most once per round). */
		public boolean processableIn(int round) {
			return status == Status.ACTIVE && (lastProcessedRound == null || lastProcessedRound < round);
		}

		/** One round of siege; {@link #fallen()} tells whether the requirement is now reached. */
		public Siege advanced(int round, int progressAdded, int civiliansLost) {
			if (!processableIn(round)) {
				throw new IllegalStateException("Siege " + id + " cannot advance again in round " + round);
			}
			return new Siege(id, provinceStateId, cityId, besiegerNationId, defenderNationId, fortressLevel,
					requiredProgress, Math.addExact(progress, progressAdded), status, startedRound, startedTurnNumber, round,
					endedRound, endReason, artillerySiege, damagedBuildingId, Math.addExact(populationLost, civiliansLost),
					createdAt);
		}

		public boolean fallen() {
			return progress >= requiredProgress;
		}

		public Siege succeeded(int round, boolean withArtillery, UUID damagedBuilding) {
			requireActive();
			return new Siege(id, provinceStateId, cityId, besiegerNationId, defenderNationId, fortressLevel,
					requiredProgress, progress, Status.SUCCEEDED, startedRound, startedTurnNumber, lastProcessedRound, round,
					EndReason.CAPTURED, withArtillery, damagedBuilding, populationLost, createdAt);
		}

		/** Progress is lost (Q-S8, PROPOSED); civilians already dead stay dead. */
		public Siege lifted(int round) {
			requireActive();
			return new Siege(id, provinceStateId, cityId, besiegerNationId, defenderNationId, fortressLevel,
					requiredProgress, progress, Status.LIFTED, startedRound, startedTurnNumber, lastProcessedRound, round,
					EndReason.BESIEGERS_GONE, artillerySiege, damagedBuildingId, populationLost, createdAt);
		}

		private void requireActive() {
			if (status != Status.ACTIVE) {
				throw new IllegalStateException("Siege " + id + " has already ended");
			}
		}

	}

	private final SortedMap<UUID, Fortress> fortresses;
	private final SortedMap<UUID, Siege> sieges;

	private Strongholds(SortedMap<UUID, Fortress> fortresses, SortedMap<UUID, Siege> sieges) {
		this.fortresses = Collections.unmodifiableSortedMap(fortresses);
		this.sieges = Collections.unmodifiableSortedMap(sieges);
	}

	public static Strongholds empty() {
		return EMPTY;
	}

	public static Strongholds of(Collection<Fortress> fortresses, Collection<Siege> sieges) {
		SortedMap<UUID, Fortress> f = new TreeMap<>(ID_ORDER);
		fortresses.forEach(x -> f.put(x.id(), x));
		SortedMap<UUID, Siege> s = new TreeMap<>(ID_ORDER);
		sieges.forEach(x -> s.put(x.id(), x));
		return new Strongholds(f, s);
	}

	/** Every fortress in id order. */
	public List<Fortress> fortresses() {
		return List.copyOf(fortresses.values());
	}

	public Optional<Fortress> fortress(UUID id) {
		return Optional.ofNullable(fortresses.get(id));
	}

	/** Every siege in id order. */
	public List<Siege> sieges() {
		return List.copyOf(sieges.values());
	}

	public Optional<Siege> siege(UUID id) {
		return Optional.ofNullable(sieges.get(id));
	}

	/** Adds a new fortress or replaces an existing one. */
	public Strongholds with(Fortress fortress) {
		SortedMap<UUID, Fortress> f = new TreeMap<>(fortresses);
		f.put(fortress.id(), fortress);
		return new Strongholds(f, sieges);
	}

	/** Adds a new siege or replaces an existing one. */
	public Strongholds with(Siege siege) {
		SortedMap<UUID, Siege> s = new TreeMap<>(sieges);
		s.put(siege.id(), siege);
		return new Strongholds(fortresses, s);
	}

}
