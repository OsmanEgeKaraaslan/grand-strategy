package com.reach.strategy.diplomacy.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Relationship rules between nations (docs/DIPLOMACY.md §2–3), for any number of nations. Every
 * pair of nations is in exactly one standing: at war, allied (normal or defensive), or at peace —
 * possibly under a truce after a treaty. The functions only judge; the application layer stores.
 */
public final class Diplomacy {

	private Diplomacy() {
	}

	public enum AllianceKind {

		/** May be called into offensive or defensive wars; grants military access (PROVISIONAL). */
		NORMAL,

		/** Members defend each other when one is attacked; no offensive support. */
		DEFENSIVE

	}

	/** An unordered pair of distinct nations, normalized so (a, b) and (b, a) are equal. */
	public record Pair(UUID a, UUID b) {

		public Pair {
			if (a.equals(b)) {
				throw new IllegalArgumentException("A pair needs two different nations");
			}
			if (a.compareTo(b) > 0) {
				UUID swap = a;
				a = b;
				b = swap;
			}
		}

		public static Pair of(UUID x, UUID y) {
			return new Pair(x, y);
		}

		public boolean contains(UUID nation) {
			return a.equals(nation) || b.equals(nation);
		}

		public UUID other(UUID nation) {
			if (a.equals(nation)) {
				return b;
			}
			if (b.equals(nation)) {
				return a;
			}
			throw new IllegalArgumentException(nation + " is not in " + this);
		}

	}

	/**
	 * The current diplomatic standing of a game.
	 *
	 * @param wars pairs of nations fighting each other in an active war
	 * @param alliances active alliances
	 * @param pendingAlliances alliance proposals awaiting an answer
	 * @param truceUntilRound last round of each truce (war may be declared again from the next round)
	 * @param coBelligerents pairs fighting on the same side of an active war
	 */
	public record Standing(Set<Pair> wars, Map<Pair, AllianceKind> alliances, Set<Pair> pendingAlliances,
			Map<Pair, Integer> truceUntilRound, Set<Pair> coBelligerents) {

		public Standing(Set<Pair> wars, Map<Pair, AllianceKind> alliances, Set<Pair> pendingAlliances,
				Map<Pair, Integer> truceUntilRound) {
			this(wars, alliances, pendingAlliances, truceUntilRound, Set.of());
		}

		public Standing {
			coBelligerents = Set.copyOf(coBelligerents);
			wars = Set.copyOf(wars);
			alliances = Map.copyOf(alliances);
			pendingAlliances = Set.copyOf(pendingAlliances);
			truceUntilRound = Map.copyOf(truceUntilRound);
		}

		public boolean atWar(UUID x, UUID y) {
			return !x.equals(y) && wars.contains(Pair.of(x, y));
		}

		public AllianceKind alliance(UUID x, UUID y) {
			return x.equals(y) ? null : alliances.get(Pair.of(x, y));
		}

		public boolean inTruce(UUID x, UUID y, int round) {
			if (x.equals(y)) {
				return false;
			}
			Integer until = truceUntilRound.get(Pair.of(x, y));
			return until != null && round <= until;
		}

		/** Allies of a nation in nation id order (deterministic). */
		public List<UUID> alliesOf(UUID nation) {
			return alliances.keySet().stream().filter(p -> p.contains(nation)).map(p -> p.other(nation)).sorted().toList();
		}

	}

	public enum WarBlocker {

		SELF, UNKNOWN_NATION, ALREADY_AT_WAR, ALLIED, TRUCE,

		/** Both fight on the same side of an active war: they cannot also fight each other. */
		FIGHTING_TOGETHER

	}

	/** Why {@code attacker} may not declare war on {@code target} in {@code round} (empty = allowed). */
	public static List<WarBlocker> warBlockers(UUID attacker, UUID target, Collection<UUID> nations, Standing standing,
			int round) {
		List<WarBlocker> blockers = new ArrayList<>();
		if (attacker.equals(target)) {
			blockers.add(WarBlocker.SELF);
			return blockers;
		}
		if (!nations.contains(target)) {
			blockers.add(WarBlocker.UNKNOWN_NATION);
			return blockers;
		}
		if (standing.atWar(attacker, target)) {
			blockers.add(WarBlocker.ALREADY_AT_WAR);
		}
		if (standing.alliance(attacker, target) != null) {
			blockers.add(WarBlocker.ALLIED);
		}
		if (standing.inTruce(attacker, target, round)) {
			blockers.add(WarBlocker.TRUCE);
		}
		if (standing.coBelligerents().contains(Pair.of(attacker, target))) {
			blockers.add(WarBlocker.FIGHTING_TOGETHER);
		}
		return blockers;
	}

	public enum AllianceBlocker {

		SELF, UNKNOWN_NATION, AT_WAR, ALREADY_ALLIED, ALREADY_PROPOSED, ENEMY_OF_ALLY

	}

	/**
	 * Why {@code proposer} may not propose (or, at acceptance, form) an alliance with {@code target}.
	 * Allies must not be at war with each other's war enemies: an alliance with a nation that fights
	 * one's own ally (or whose ally one fights) would make the standing contradictory.
	 *
	 * @param proposing true for a new proposal (a pending one blocks), false when accepting it
	 */
	public static List<AllianceBlocker> allianceBlockers(UUID proposer, UUID target, Collection<UUID> nations,
			Standing standing, boolean proposing) {
		List<AllianceBlocker> blockers = new ArrayList<>();
		if (proposer.equals(target)) {
			blockers.add(AllianceBlocker.SELF);
			return blockers;
		}
		if (!nations.contains(target)) {
			blockers.add(AllianceBlocker.UNKNOWN_NATION);
			return blockers;
		}
		if (standing.atWar(proposer, target)) {
			blockers.add(AllianceBlocker.AT_WAR);
		}
		if (standing.alliance(proposer, target) != null) {
			blockers.add(AllianceBlocker.ALREADY_ALLIED);
		}
		if (proposing && standing.pendingAlliances().contains(Pair.of(proposer, target))) {
			blockers.add(AllianceBlocker.ALREADY_PROPOSED);
		}
		boolean enemyOfAlly = standing.alliesOf(proposer).stream().anyMatch(ally -> standing.atWar(ally, target))
				|| standing.alliesOf(target).stream().anyMatch(ally -> standing.atWar(ally, proposer));
		if (enemyOfAlly) {
			blockers.add(AllianceBlocker.ENEMY_OF_ALLY);
		}
		return blockers;
	}

	/**
	 * The defensive response (docs/DIPLOMACY.md §3.3, PROVISIONAL Q-D6): when {@code attacker}
	 * declares war on {@code defender}, every ally of the defender (normal or defensive alliance)
	 * joins the war on the defender's side — unless it is allied with the attacker as well, in truce
	 * with it, already at war with it, or fighting on the attacker's side of another war. Deterministic: nation id order.
	 */
	public static List<UUID> defensiveJoiners(UUID attacker, UUID defender, Standing standing, int round) {
		return standing.alliesOf(defender)
			.stream()
			.filter(ally -> !ally.equals(attacker))
			.filter(ally -> standing.alliance(ally, attacker) == null)
			.filter(ally -> !standing.inTruce(ally, attacker, round))
			.filter(ally -> !standing.atWar(ally, attacker))
			.filter(ally -> !standing.coBelligerents().contains(Pair.of(ally, attacker)))
			.toList();
	}

	public enum TerminationBlocker {

		NOT_ACTIVE, FIGHTING_TOGETHER

	}

	/**
	 * An active alliance can be ended by either member at any time, except while both members fight
	 * on the same side of an active war (PROVISIONAL): leaving a partner mid-war needs a peace first.
	 */
	public static List<TerminationBlocker> terminationBlockers(boolean active, boolean fightingTogether) {
		List<TerminationBlocker> blockers = new ArrayList<>();
		if (!active) {
			blockers.add(TerminationBlocker.NOT_ACTIVE);
		}
		if (fightingTogether) {
			blockers.add(TerminationBlocker.FIGHTING_TOGETHER);
		}
		return blockers;
	}

	/** Whether a pending proposal created in {@code createdRound} expires at the end of {@code completedRound}. */
	public static boolean expires(int createdRound, int completedRound, DiplomacyRules rules) {
		return completedRound >= createdRound + rules.proposalExpiryRounds();
	}

}
