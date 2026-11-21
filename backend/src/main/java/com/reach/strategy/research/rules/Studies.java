package com.reach.strategy.research.rules;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import com.reach.strategy.research.rules.InstitutionType.Pool;
import com.reach.strategy.research.rules.ResearchRules.Track;

/**
 * The research state of one game as immutable values (ENGINE_DESIGN S3): each nation's points and
 * technology levels, the institutions of its cities and its chosen doctrines. Every change returns
 * a new {@code Studies}; unchanged rows stay the same objects, so a store can persist the
 * difference by id. A {@code Studies} may hold only part of a game; it is compared with the one it
 * was derived from. Nothing is ever removed.
 */
public final class Studies {

	/** The order of {@code ORDER BY id} on a PostgreSQL uuid column (and of {@code UUID.toString()}). */
	public static final Comparator<UUID> ID_ORDER = (a, b) -> {
		int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
		return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
	};

	private static final Studies EMPTY = new Studies(new TreeMap<>(ID_ORDER), new TreeMap<>(ID_ORDER),
			new TreeMap<>(ID_ORDER));

	/** A nation's research points per pool and its level per technology track. */
	public record Progress(UUID nationId, int militaryPoints, int civilianPoints, int doctrinePoints, int militaryLevel,
			int civilianLevel, String rulesetVersion) {

		/** Every nation starts at level 1 of both tracks with no points. */
		public static Progress start(UUID nationId, String rulesetVersion) {
			return new Progress(nationId, 0, 0, 0, 1, 1, rulesetVersion);
		}

		public int points(Pool pool) {
			return switch (pool) {
				case MILITARY -> militaryPoints;
				case CIVILIAN -> civilianPoints;
				case DOCTRINE -> doctrinePoints;
			};
		}

		public int level(Track track) {
			return track == Track.MILITARY ? militaryLevel : civilianLevel;
		}

		public Progress add(Pool pool, int points) {
			if (points < 0) {
				throw new IllegalArgumentException("Negative research income");
			}
			return with(pool, Math.addExact(points(pool), points));
		}

		/** Deducts points the caller has validated; a shortfall is a bug and fails the command. */
		public Progress spend(Pool pool, int points) {
			if (points <= 0 || points > points(pool)) {
				throw new IllegalStateException("Cannot spend " + points + " of " + points(pool) + " " + pool + " points");
			}
			return with(pool, points(pool) - points);
		}

		public Progress raise(Track track, int newLevel) {
			if (newLevel != level(track) + 1) {
				throw new IllegalStateException("Technology levels rise one at a time");
			}
			return track == Track.MILITARY
					? new Progress(nationId, militaryPoints, civilianPoints, doctrinePoints, newLevel, civilianLevel,
							rulesetVersion)
					: new Progress(nationId, militaryPoints, civilianPoints, doctrinePoints, militaryLevel, newLevel,
							rulesetVersion);
		}

		private Progress with(Pool pool, int value) {
			return new Progress(nationId, pool == Pool.MILITARY ? value : militaryPoints,
					pool == Pool.CIVILIAN ? value : civilianPoints, pool == Pool.DOCTRINE ? value : doctrinePoints,
					militaryLevel, civilianLevel, rulesetVersion);
		}

	}

	/** A research institution of a city (Phase 8): built once, ACTIVE from the round after it completes. */
	public record Institution(UUID id, UUID cityId, InstitutionType type, Status status, int orderedRound,
			int completesAfterRound, long costPaidMg, String rulesetVersion, Instant createdAt) {

		public enum Status {

			UNDER_CONSTRUCTION, ACTIVE

		}

		public static Institution project(UUID id, UUID cityId, InstitutionType type, int orderedRound,
				int completesAfterRound, long costMg, String rulesetVersion, Instant now) {
			return new Institution(id, cityId, type, Status.UNDER_CONSTRUCTION, orderedRound, completesAfterRound, costMg,
					rulesetVersion, now);
		}

		public boolean active() {
			return status == Status.ACTIVE;
		}

		public Institution completed() {
			if (status != Status.UNDER_CONSTRUCTION) {
				throw new IllegalStateException("Institution " + id + " is already active");
			}
			return new Institution(id, cityId, type, Status.ACTIVE, orderedRound, completesAfterRound, costPaidMg,
					rulesetVersion, createdAt);
		}

	}

	/** A doctrine a nation chose (PROVISIONAL: one per tier, permanent). */
	public record Doctrine(UUID id, UUID nationId, String doctrineKey, int tier, int costPoints, int unlockedRound,
			Instant createdAt) {
	}

	private final SortedMap<UUID, Progress> progress;
	private final SortedMap<UUID, Institution> institutions;
	private final SortedMap<UUID, Doctrine> doctrines;

	private Studies(SortedMap<UUID, Progress> progress, SortedMap<UUID, Institution> institutions,
			SortedMap<UUID, Doctrine> doctrines) {
		this.progress = Collections.unmodifiableSortedMap(progress);
		this.institutions = Collections.unmodifiableSortedMap(institutions);
		this.doctrines = Collections.unmodifiableSortedMap(doctrines);
	}

	public static Studies empty() {
		return EMPTY;
	}

	public static Studies of(Collection<Progress> progress, Collection<Institution> institutions,
			Collection<Doctrine> doctrines) {
		SortedMap<UUID, Progress> p = new TreeMap<>(ID_ORDER);
		progress.forEach(x -> p.put(x.nationId(), x));
		SortedMap<UUID, Institution> i = new TreeMap<>(ID_ORDER);
		institutions.forEach(x -> i.put(x.id(), x));
		SortedMap<UUID, Doctrine> d = new TreeMap<>(ID_ORDER);
		doctrines.forEach(x -> d.put(x.id(), x));
		return new Studies(p, i, d);
	}

	// --- reading ------------------------------------------------------------------------------

	/** Every nation's progress in nation id order. */
	public List<Progress> progress() {
		return List.copyOf(progress.values());
	}

	public Optional<Progress> progressOf(UUID nationId) {
		return Optional.ofNullable(progress.get(nationId));
	}

	/** Every institution in id order. */
	public List<Institution> institutions() {
		return List.copyOf(institutions.values());
	}

	public Optional<Institution> institution(UUID id) {
		return Optional.ofNullable(institutions.get(id));
	}

	/** Every chosen doctrine in id order. */
	public List<Doctrine> doctrines() {
		return List.copyOf(doctrines.values());
	}

	public Optional<Doctrine> doctrine(UUID id) {
		return Optional.ofNullable(doctrines.get(id));
	}

	// --- changes ------------------------------------------------------------------------------

	/** A nation's research row at game start. */
	public Studies begin(Progress start) {
		if (progress.containsKey(start.nationId())) {
			throw new IllegalArgumentException("Nation " + start.nationId() + " already has research");
		}
		return withProgress(start);
	}

	/** Replaces a nation's existing progress (points gained or spent, a level raised). */
	public Studies update(Progress changed) {
		if (!progress.containsKey(changed.nationId())) {
			throw new IllegalArgumentException("Nation " + changed.nationId() + " has no research");
		}
		return withProgress(changed);
	}

	/** A new (paid) institution project. */
	public Studies found(Institution institution) {
		if (institutions.containsKey(institution.id())) {
			throw new IllegalArgumentException("Institution " + institution.id() + " already exists");
		}
		return withInstitution(institution);
	}

	public Studies update(Institution changed) {
		if (!institutions.containsKey(changed.id())) {
			throw new IllegalArgumentException("Unknown institution " + changed.id());
		}
		return withInstitution(changed);
	}

	public Studies choose(Doctrine doctrine) {
		if (doctrines.containsKey(doctrine.id())) {
			throw new IllegalArgumentException("Doctrine " + doctrine.id() + " already exists");
		}
		SortedMap<UUID, Doctrine> d = new TreeMap<>(doctrines);
		d.put(doctrine.id(), doctrine);
		return new Studies(progress, institutions, d);
	}

	private Studies withProgress(Progress p) {
		SortedMap<UUID, Progress> m = new TreeMap<>(progress);
		m.put(p.nationId(), p);
		return new Studies(m, institutions, doctrines);
	}

	private Studies withInstitution(Institution i) {
		SortedMap<UUID, Institution> m = new TreeMap<>(institutions);
		m.put(i.id(), i);
		return new Studies(progress, m, doctrines);
	}

}
