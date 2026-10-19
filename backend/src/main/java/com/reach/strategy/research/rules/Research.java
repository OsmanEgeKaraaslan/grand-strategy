package com.reach.strategy.research.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.reach.strategy.research.rules.InstitutionType.Pool;
import com.reach.strategy.research.rules.ResearchRules.Track;

/**
 * Research calculations and checks (PROVISIONAL content, Q-X1): points per round, technology and
 * doctrine unlocks, the modifiers a nation's research state produces, and institution construction.
 * Exact integers only; shared by the UI quotes, the commands and the round phase.
 */
public final class Research {

	private Research() {
	}

	// --- points ---------------------------------------------------------------------------------

	/** Points per pool from ACTIVE institutions that count for the nation this round. */
	public static Map<Pool, Integer> pointsPerRound(Collection<InstitutionType> activeInstitutions, ResearchRules rules) {
		Map<Pool, Integer> points = new EnumMap<>(Pool.class);
		for (Pool pool : Pool.values()) {
			points.put(pool, 0);
		}
		for (InstitutionType type : activeInstitutions) {
			points.merge(type.pool(), rules.institution(type).pointsPerRound(), Math::addExact);
		}
		return points;
	}

	/**
	 * Whether an institution produces for its city's owner this round: ACTIVE and its city owned and
	 * controlled by that nation (PROVISIONAL, mirrors Q-W2: an occupied city's institutions work for
	 * nobody). A besieged city's institutions keep working, like its income (Q-S6).
	 */
	public static boolean produces(boolean active, UUID ownerNationId, UUID controllerNationId) {
		return active && ownerNationId.equals(controllerNationId);
	}

	// --- technology -----------------------------------------------------------------------------

	public enum Blocker {

		NOT_YOUR_TURN,

		MAX_LEVEL_REACHED,

		INSUFFICIENT_POINTS,

		/** Doctrines: already chosen. */
		ALREADY_CHOSEN,

		/** Doctrines: the nation already holds the allowed number of doctrines of this tier. */
		TIER_FULL,

		UNKNOWN_DOCTRINE

	}

	/** @param targetLevel the next level of the track (current + 1, or current at the maximum) */
	public record Quote(int targetLevel, int costPoints, boolean allowed, List<Blocker> blockers) {
	}

	public static Quote technology(Track track, int currentLevel, int availablePoints, boolean yourTurn,
			ResearchRules rules) {
		List<Blocker> blockers = new ArrayList<>();
		if (!yourTurn) {
			blockers.add(Blocker.NOT_YOUR_TURN);
		}
		if (currentLevel >= rules.maxLevel(track)) {
			blockers.add(Blocker.MAX_LEVEL_REACHED);
			return new Quote(currentLevel, 0, false, List.copyOf(blockers));
		}
		int cost = rules.levelCost(track, currentLevel + 1);
		if (availablePoints < cost) {
			blockers.add(Blocker.INSUFFICIENT_POINTS);
		}
		return new Quote(currentLevel + 1, cost, blockers.isEmpty(), List.copyOf(blockers));
	}

	public static Quote doctrine(String key, Set<String> chosen, int availablePoints, boolean yourTurn,
			ResearchRules rules) {
		ResearchRules.DoctrineRules doctrine = rules.doctrines().get(key);
		if (doctrine == null) {
			return new Quote(0, 0, false, List.of(Blocker.UNKNOWN_DOCTRINE));
		}
		List<Blocker> blockers = new ArrayList<>();
		if (!yourTurn) {
			blockers.add(Blocker.NOT_YOUR_TURN);
		}
		if (chosen.contains(key)) {
			blockers.add(Blocker.ALREADY_CHOSEN);
		}
		else {
			long sameTier = chosen.stream()
				.map(rules.doctrines()::get)
				.filter(d -> d != null && d.tier() == doctrine.tier())
				.count();
			if (sameTier >= rules.doctrinesPerTier()) {
				blockers.add(Blocker.TIER_FULL);
			}
		}
		if (availablePoints < doctrine.costPoints()) {
			blockers.add(Blocker.INSUFFICIENT_POINTS);
		}
		return new Quote(doctrine.tier(), doctrine.costPoints(), blockers.isEmpty(), List.copyOf(blockers));
	}

	// --- effects --------------------------------------------------------------------------------

	/**
	 * What a nation's research state does in the rules engines. Military bonuses come from the level
	 * reached; doctrine modifiers add up over the chosen doctrines and are capped at 100%.
	 */
	public record Effects(int militaryLevel, int civilianLevel, Map<String, Integer> damageBonusBps,
			int riverPenaltyReductionBps, int mountainPenaltyReductionBps, int siegeArtilleryBonusBps,
			int militaryUpkeepReductionBps, int growthBonusBps, int highestUnlockedWorkshopTier, List<String> doctrines) {

		/** No research at all: games created before ruleset v4. */
		public static final Effects NONE = new Effects(1, 1, Map.of(), 0, 0, 0, 0, 0, 1, List.of());

	}

	public static Effects effects(int militaryLevel, int civilianLevel, Collection<String> doctrines,
			ResearchRules rules) {
		Map<String, Integer> damage = new TreeMap<>(rules.military(militaryLevel).damageBonusBps());
		int river = 0;
		int mountain = 0;
		int siege = 0;
		int upkeep = 0;
		for (String key : new java.util.TreeSet<>(doctrines)) {
			ResearchRules.DoctrineRules d = rules.doctrines().get(key);
			if (d == null) {
				throw new IllegalStateException("Doctrine " + key + " is not in ruleset " + rules.version());
			}
			river += d.riverPenaltyReductionBps();
			mountain += d.mountainPenaltyReductionBps();
			siege += d.siegeArtilleryBonusBps();
			upkeep += d.militaryUpkeepReductionBps();
		}
		int growth = 0;
		int tier = 1;
		for (int level = 1; level <= civilianLevel; level++) {
			ResearchRules.CivilianLevel c = rules.civilian(level);
			growth = Math.max(growth, c.growthBonusBps());
			if (c.unlocksWorkshopTier() != null) {
				tier = Math.max(tier, c.unlocksWorkshopTier());
			}
		}
		return new Effects(militaryLevel, civilianLevel, Map.copyOf(damage), Math.min(river, 10_000),
				Math.min(mountain, 10_000), siege, Math.min(upkeep, 10_000), growth, tier,
				List.copyOf(new java.util.TreeSet<>(doctrines)));
	}

	/** The civilian level needed for a workshop tier, or 0 when no level unlocks it. */
	public static int civilianLevelUnlocking(int workshopTier, ResearchRules rules) {
		for (ResearchRules.CivilianLevel level : rules.civilianLevels()) {
			if (level.unlocksWorkshopTier() != null && level.unlocksWorkshopTier() >= workshopTier) {
				return level.level();
			}
		}
		return 0;
	}

	// --- institutions ---------------------------------------------------------------------------

	public enum InstitutionBlocker {

		NOT_YOUR_TURN,

		NOT_OWNER,

		OCCUPIED,

		BESIEGED,

		/** One institution of each type per city (MVP), counting one under construction. */
		ALREADY_EXISTS,

		IN_ARREARS,

		INSUFFICIENT_GOLD

	}

	public record InstitutionRequest(UUID nationId, UUID ownerNationId, UUID controllerNationId, boolean yourTurn,
			boolean besieged, boolean alreadyInCity, long treasuryMg, boolean inArrears) {
	}

	public static List<InstitutionBlocker> institutionBlockers(InstitutionType type, InstitutionRequest request,
			ResearchRules rules) {
		List<InstitutionBlocker> blockers = new ArrayList<>();
		if (!request.yourTurn()) {
			blockers.add(InstitutionBlocker.NOT_YOUR_TURN);
		}
		if (!request.nationId().equals(request.ownerNationId())) {
			blockers.add(InstitutionBlocker.NOT_OWNER);
		}
		else if (!request.nationId().equals(request.controllerNationId())) {
			blockers.add(InstitutionBlocker.OCCUPIED);
		}
		if (request.besieged()) {
			blockers.add(InstitutionBlocker.BESIEGED);
		}
		if (request.alreadyInCity()) {
			blockers.add(InstitutionBlocker.ALREADY_EXISTS);
		}
		if (request.inArrears()) {
			blockers.add(InstitutionBlocker.IN_ARREARS);
		}
		if (request.treasuryMg() < rules.institution(type).buildCostMg()) {
			blockers.add(InstitutionBlocker.INSUFFICIENT_GOLD);
		}
		return List.copyOf(blockers);
	}

	/** Q-E8: ordered in round N with d rounds → ACTIVE at the end of N + d − 1, producing from round N + d. */
	public static int completesAfterRound(int orderedRound, int durationRounds) {
		if (orderedRound < 1 || durationRounds < 1) {
			throw new IllegalArgumentException("Invalid project timing");
		}
		return orderedRound + durationRounds - 1;
	}

}
