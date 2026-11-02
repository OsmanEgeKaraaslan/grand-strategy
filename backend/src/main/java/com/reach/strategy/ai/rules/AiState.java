package com.reach.strategy.ai.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What one computer nation knows at a moment of its turn: exactly what the same nation's human
 * player would see in the game's read models (map, every battalion — there is no fog of war — its
 * own economy, research and recruitment options, public diplomacy and its own proposals). Built by
 * the application layer, consumed by the pure planners. Immutable.
 *
 * @param me the deciding nation
 * @param maxRounds the round after which the game is scored (0 = no limit)
 */
public record AiState(UUID gameId, int round, int turnNumber, int maxRounds, UUID me, String meKey,
		Map<UUID, Nation> nations, Map<String, Province> provinces, List<Unit> units, Economy economy,
		List<RecruitCity> recruitment, Research research, Diplomacy diplomacy, Map<String, Integer> fortLevels,
		List<Siege> sieges, Combat combat, List<FortressOption> fortresses) {

	public AiState {
		fortresses = List.copyOf(fortresses);
		nations = Map.copyOf(nations);
		provinces = Map.copyOf(provinces);
		units = List.copyOf(units);
		recruitment = List.copyOf(recruitment);
		fortLevels = Map.copyOf(fortLevels);
		sieges = List.copyOf(sieges);
	}

	/**
	 * @param control HUMAN, AI or NPC
	 * @param status the deciding nation's standing with it: SELF, WAR, ALLIED or PEACE
	 * @param weMayEnter whether the deciding nation's battalions may enter its territory
	 */
	public record Nation(UUID id, String key, String name, String control, boolean eliminated, String capitalKey,
			String status, String allianceKind, UUID allianceId, Integer truceUntilRound, boolean weMayEnter,
			List<String> warBlockers, List<String> allianceBlockers) {

		public Nation {
			warBlockers = List.copyOf(warBlockers);
			allianceBlockers = List.copyOf(allianceBlockers);
		}

		public boolean atWar() {
			return "WAR".equals(status);
		}

		public boolean allied() {
			return "ALLIED".equals(status);
		}

	}

	/** @param neighbours adjacent province keys; {@code riverTo} those across a river */
	public record Province(String key, String terrain, UUID owner, UUID controller, int population, UUID cityId,
			List<String> neighbours, Set<String> riverTo) {

		public Province {
			neighbours = List.copyOf(neighbours);
			riverTo = Set.copyOf(riverTo);
		}

		public boolean mountains() {
			return "MOUNTAINS".equals(terrain);
		}

		public boolean occupied() {
			return !owner.equals(controller);
		}

	}

	/**
	 * @param commander the ally commanding it while attached, else null
	 * @param ready whether the deciding nation may move it now (its own or attached to it, eligible)
	 */
	public record Unit(UUID id, UUID nation, UUID commander, String type, int manpower, int maxManpower,
			String province, boolean ready) {

		/** Who gives this battalion its orders. */
		public UUID commandedBy() {
			return commander != null ? commander : nation;
		}

	}

	public record Economy(long treasuryMg, boolean inArrears, long grossIncomeMg, long upkeepMg, long netIncomeMg,
			int maxWorkshopsPerCity, long workshopGainPerWorkerMg, int workshopCapacity, List<City> cities) {

		public Economy {
			cities = List.copyOf(cities);
		}

	}

	/**
	 * One city the deciding nation owns.
	 *
	 * @param agricultureWorkers people who could move into a new workshop
	 * @param build a new Tier 1 workshop, if the server would allow one now
	 */
	public record City(UUID cityId, String province, int population, boolean controlled, int agricultureWorkers,
			int workshopCount, Option build, List<WorkshopOption> workshops) {

		public City {
			workshops = List.copyOf(workshops);
		}

	}

	/** A priced option exactly as the server offers it now. */
	public record Option(long costMg, boolean allowed) {
	}

	/**
	 * @param kind UPGRADE or REPAIR
	 * @param gainMg extra income per round once done (estimated from the server's figures)
	 */
	public record WorkshopOption(UUID workshopId, String kind, long costMg, long gainMg, boolean allowed) {
	}

	public record RecruitCity(UUID cityId, String province, int population, List<RecruitOption> options) {

		public RecruitCity {
			options = List.copyOf(options);
		}

	}

	public record RecruitOption(String unitType, long costMg, long upkeepMg, boolean allowed) {
	}

	/**
	 * @param institutionTypes institutions the nation already has (any status)
	 * @param institutionOptions buildable institutions per city key (only cities that were checked)
	 */
	public record Research(boolean available, Map<String, Integer> points, List<TechOption> technologies,
			List<DoctrineOption> doctrines, List<String> chosenDoctrines, List<String> institutionTypes,
			List<InstitutionOption> institutionOptions) {

		public Research {
			points = Map.copyOf(points);
			technologies = List.copyOf(technologies);
			doctrines = List.copyOf(doctrines);
			chosenDoctrines = List.copyOf(chosenDoctrines);
			institutionTypes = List.copyOf(institutionTypes);
			institutionOptions = List.copyOf(institutionOptions);
		}

		public static Research none() {
			return new Research(false, Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());
		}

	}

	public record TechOption(String track, int level, int targetLevel, int costPoints, boolean allowed) {
	}

	public record DoctrineOption(String key, int costPoints, int riverBps, int mountainBps, int siegeBps,
			int upkeepBps, boolean allowed) {
	}

	public record InstitutionOption(UUID cityId, String province, String type, long costMg, long upkeepMg,
			boolean allowed) {
	}

	/** @param besieger the nation besieging; @param roundsLeft the server's estimate, null if unknown */
	public record Siege(String province, UUID besieger, UUID defender, Integer roundsLeft) {
	}

	/** Fortress works the server would sell now in one of the deciding nation's cities. */
	public record FortressOption(UUID cityId, String province, int level, int targetLevel, long costMg, boolean allowed) {
	}

	/** The deciding nation's own attack multipliers (with doctrines) and technology level. */
	public record Combat(int militaryLevel, int riverAttackBps, int mountainAttackBps) {
	}

	/** Public diplomacy and the deciding nation's own pending business. */
	public record Diplomacy(boolean available, Features features, List<War> wars, List<Alliance> alliances,
			List<Call> calls, List<Conference> conferences, List<Gift> gifts, long giftMaxMg, int maxPeaceTerms,
			long reparationsMaxMg) {

		public Diplomacy {
			wars = List.copyOf(wars);
			alliances = List.copyOf(alliances);
			calls = List.copyOf(calls);
			conferences = List.copyOf(conferences);
			gifts = List.copyOf(gifts);
		}

		public static Diplomacy none() {
			return new Diplomacy(false, new Features(false, false, false, false, false, false), List.of(), List.of(),
					List.of(), List.of(), List.of(), 0, 0, 0);
		}

	}

	/** @param offensiveCallsToArms, truceAllowsCallsToArms the v8 call-to-arms rules (docs/DIPLOMACY.md §12a) */
	public record Features(boolean attachments, boolean callsToArms, boolean peaceConferences, boolean separatePeace,
			boolean capitalsCedable, boolean elimination, boolean offensiveCallsToArms, boolean truceAllowsCallsToArms) {

		public Features(boolean attachments, boolean callsToArms, boolean peaceConferences, boolean separatePeace,
				boolean capitalsCedable, boolean elimination) {
			this(attachments, callsToArms, peaceConferences, separatePeace, capitalsCedable, elimination, false, false);
		}

	}

	/** @param participants nations still fighting, with their side */
	public record War(UUID id, UUID attackerLeader, UUID defenderLeader, Map<UUID, String> participants,
			int declaredRound, int net) {

		public War {
			participants = Map.copyOf(participants);
		}

		public Optional<String> sideOf(UUID nation) {
			return Optional.ofNullable(participants.get(nation));
		}

		public boolean leads(UUID nation) {
			return attackerLeader.equals(nation) || defenderLeader.equals(nation);
		}

		public UUID leaderOf(String side) {
			return "ATTACKER".equals(side) ? attackerLeader : defenderLeader;
		}

		public static String opposite(String side) {
			return "ATTACKER".equals(side) ? "DEFENDER" : "ATTACKER";
		}

		/** War score from {@code side}'s point of view (positive = it is winning). */
		public int scoreFor(String side) {
			return "ATTACKER".equals(side) ? net : -net;
		}

		public List<UUID> sideMembers(String side) {
			return participants.entrySet()
				.stream()
				.filter(e -> e.getValue().equals(side))
				.map(Map.Entry::getKey)
				.sorted()
				.toList();
		}

	}

	public record Alliance(UUID id, String kind, UUID a, UUID b, UUID proposer, String status, boolean canAccept,
			boolean canEnd, int proposedRound) {

		public boolean involves(UUID nation) {
			return a.equals(nation) || b.equals(nation);
		}

		public UUID other(UUID nation) {
			return a.equals(nation) ? b : a;
		}

	}

	/** @param side the side the called ally would join (DEFENDER, or ATTACKER for an invitation to attack) */
	public record Call(UUID id, UUID warId, UUID caller, UUID ally, String status, boolean canAnswer, String side) {

		public Call(UUID id, UUID warId, UUID caller, UUID ally, String status, boolean canAnswer) {
			this(id, warId, caller, ally, status, canAnswer, "DEFENDER");
		}

	}

	public record Gift(UUID sender, UUID recipient, long amountMg, int round) {
	}

	/** A term exactly as the conference shows it. */
	public record Term(String type, UUID from, UUID to, String provinceKey, Long amountMg, UUID sourceCityId,
			UUID destinationCityId, Integer people) {
	}

	/**
	 * @param approvals nation → decision (APPROVED, REJECTED or null) for the current revision
	 * @param revisionsByMe how many revisions the deciding nation has authored
	 */
	public record Conference(UUID id, UUID warId, String kind, UUID requester, UUID counterpart, String status,
			int currentRevision, List<Term> terms, Map<UUID, String> approvals, List<String> violations,
			boolean canAnswer, boolean canRevise, boolean mustDecide, boolean canCancel, int revisionsByMe,
			UUID lastAuthor, int requestedRound) {

		public Conference {
			terms = List.copyOf(terms);
			approvals = approvals == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.HashMap<>(approvals));
			violations = List.copyOf(violations);
		}

		public boolean active() {
			return "ACTIVE".equals(status);
		}

		public boolean separate() {
			return "SEPARATE".equals(kind);
		}

	}

	// --- derived views (cheap, recomputed per state) -------------------------------------------

	public Nation self() {
		return nations.get(me);
	}

	public List<Province> ownedBy(UUID nation) {
		return provinces.values()
			.stream()
			.filter(p -> p.owner().equals(nation))
			.sorted(Comparator.comparing(Province::key))
			.toList();
	}

	public List<Province> controlledBy(UUID nation) {
		return provinces.values()
			.stream()
			.filter(p -> p.controller().equals(nation))
			.sorted(Comparator.comparing(Province::key))
			.toList();
	}

	public List<Unit> unitsOf(UUID nation) {
		return units.stream().filter(u -> u.nation().equals(nation)).toList();
	}

	public List<Unit> unitsIn(String province) {
		return units.stream().filter(u -> u.province().equals(province)).toList();
	}

	/** Battalions the deciding nation may order now, by province (sorted keys and ids). */
	public Map<String, List<Unit>> readyStacks() {
		Map<String, List<Unit>> stacks = new java.util.TreeMap<>();
		units.stream()
			.filter(Unit::ready)
			.filter(u -> u.commandedBy().equals(me))
			.sorted(Comparator.comparing(u -> u.id().toString()))
			.forEach(u -> stacks.computeIfAbsent(u.province(), k -> new ArrayList<>()).add(u));
		return stacks;
	}

	/** Nations at war with the deciding nation (directly, in any war). */
	public Set<UUID> enemies() {
		return nations.values().stream().filter(Nation::atWar).map(Nation::id).collect(Collectors.toSet());
	}

	public boolean atWarWith(UUID nation) {
		Nation n = nations.get(nation);
		return n != null && n.atWar();
	}

	/** Nations fighting on the deciding nation's side in some active war, plus active allies. */
	public Set<UUID> friends() {
		Set<UUID> friends = new java.util.HashSet<>();
		nations.values().stream().filter(Nation::allied).forEach(n -> friends.add(n.id()));
		for (War war : diplomacy.wars()) {
			war.sideOf(me).ifPresent(side -> friends.addAll(war.sideMembers(side)));
		}
		friends.remove(me);
		return friends;
	}

	public Optional<String> capitalOf(UUID nation) {
		return Optional.ofNullable(nations.get(nation)).map(Nation::capitalKey);
	}

	/** Whether the deciding nation's battalions may stand in this province (DIPLOMACY §2.2). */
	public boolean mayEnter(String provinceKey) {
		Province p = provinces.get(provinceKey);
		if (p == null) {
			return false;
		}
		if (p.controller().equals(me) || p.owner().equals(me)) {
			return true;
		}
		Nation controller = nations.get(p.controller());
		return controller != null && (controller.atWar() || controller.weMayEnter());
	}

	public int roundsLeft() {
		return maxRounds <= 0 ? Integer.MAX_VALUE : Math.max(0, maxRounds - round + 1);
	}

}
