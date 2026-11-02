package com.reach.strategy.ai.rules;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

/**
 * A small board for planner tests. Map (rivers marked ~, mountains *):
 *
 * <pre>
 *   a1 ─ a2 ─ b1 ─ b2*
 *   │     ~
 *   a3 ─ c1 ─ c2
 * </pre>
 *
 * Nation A (the deciding nation, capital a1) owns a1–a3, B owns b1–b2 (capital b1), C owns c1–c2
 * (capital c1). Everybody starts at peace with access only to its own land.
 */
final class Board {

	static final UUID A = new UUID(0, 1);
	static final UUID B = new UUID(0, 2);
	static final UUID C = new UUID(0, 3);

	static final AiRules RULES = loadRules("v7");

	/** Ruleset v8: the same values plus the v8 peace evaluation. */
	static final AiRules RULES_V8 = loadRules("v8");

	private static AiRules loadRules(String version) {
		try (InputStream in = Board.class.getResourceAsStream("/rulesets/" + version + "/ai.json")) {
			return JsonMapper.builder().build().readValue(in, AiRules.class);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private final Map<String, String[]> provinces = new LinkedHashMap<>();
	private final Map<UUID, String> keys = new HashMap<>();
	private final Map<UUID, String> status = new HashMap<>();
	private final Map<UUID, Boolean> access = new HashMap<>();
	private final Map<UUID, List<String>> allianceBlockers = new HashMap<>();
	private final Map<UUID, String> control = new HashMap<>();
	private final List<AiState.Unit> units = new ArrayList<>();
	private final List<AiState.War> wars = new ArrayList<>();
	private final List<AiState.Alliance> alliances = new ArrayList<>();
	private final List<AiState.Call> calls = new ArrayList<>();
	private final List<AiState.Conference> conferences = new ArrayList<>();
	private final Map<String, Integer> forts = new HashMap<>();
	private final List<AiState.Siege> sieges = new ArrayList<>();
	private final List<AiState.City> cities = new ArrayList<>();
	private final List<AiState.RecruitCity> recruitment = new ArrayList<>();
	private final List<AiState.TechOption> techs = new ArrayList<>();
	private final List<AiState.DoctrineOption> doctrines = new ArrayList<>();
	private int round = 5;
	private int maxRounds = 30;
	private long treasury = 100_000;
	private long gross = 40_000;
	private long upkeep = 5_000;
	private boolean arrears;
	private String meKey = "aurelia";
	private int nextUnit = 100;
	private final Map<UUID, Integer> truces = new HashMap<>();
	private boolean v8Features;

	Board() {
		// key → owner, controller, terrain, population
		provinces.put("a1", new String[] { "A", "A", "PLAINS", "12000" });
		provinces.put("a2", new String[] { "A", "A", "PLAINS", "6000" });
		provinces.put("a3", new String[] { "A", "A", "PLAINS", "6000" });
		provinces.put("b1", new String[] { "B", "B", "PLAINS", "10000" });
		provinces.put("b2", new String[] { "B", "B", "MOUNTAINS", "5000" });
		provinces.put("c1", new String[] { "C", "C", "PLAINS", "9000" });
		provinces.put("c2", new String[] { "C", "C", "PLAINS", "5000" });
		keys.put(A, "aurelia");
		keys.put(B, "frisia");
		keys.put(C, "helvaria");
		for (UUID n : List.of(A, B, C)) {
			status.put(n, n.equals(A) ? "SELF" : "PEACE");
			access.put(n, n.equals(A));
			control.put(n, "AI");
		}
	}

	private static final Map<String, List<String>> NEIGHBOURS = Map.of("a1", List.of("a2", "a3"), "a2",
			List.of("a1", "b1"), "a3", List.of("a1", "c1"), "b1", List.of("a2", "b2"), "b2", List.of("b1"), "c1",
			List.of("a3", "c2"), "c2", List.of("c1"));

	private static final Map<String, Set<String>> RIVERS = Map.of("a2", Set.of("b1"), "b1", Set.of("a2"));

	private static UUID nation(String letter) {
		return switch (letter) {
			case "A" -> A;
			case "B" -> B;
			default -> C;
		};
	}

	Board owner(String province, UUID owner, UUID controller) {
		String[] p = provinces.get(province);
		p[0] = letter(owner);
		p[1] = letter(controller);
		return this;
	}

	private static String letter(UUID n) {
		return n.equals(A) ? "A" : n.equals(B) ? "B" : "C";
	}

	Board me(String key) {
		meKey = key;
		keys.put(A, key);
		return this;
	}

	Board round(int round, int maxRounds) {
		this.round = round;
		this.maxRounds = maxRounds;
		return this;
	}

	Board economy(long treasuryMg, long grossMg, long upkeepMg) {
		treasury = treasuryMg;
		gross = grossMg;
		upkeep = upkeepMg;
		return this;
	}

	Board arrears() {
		arrears = true;
		return this;
	}

	Board human(UUID nation) {
		control.put(nation, "HUMAN");
		return this;
	}

	/** War between A's side and others; also gives access and WAR status. */
	Board war(UUID id, UUID attackerLeader, UUID defenderLeader, Map<UUID, String> participants, int declaredRound, int net) {
		wars.add(new AiState.War(id, attackerLeader, defenderLeader, participants, declaredRound, net));
		participants.forEach((n, side) -> {
			if (!n.equals(A) && participants.containsKey(A) && !participants.get(A).equals(side)) {
				status.put(n, "WAR");
				access.put(n, true);
			}
		});
		return this;
	}

	Board allied(UUID nation, UUID allianceId) {
		status.put(nation, "ALLIED");
		alliances.add(new AiState.Alliance(allianceId, "DEFENSIVE", A, nation, A, "ACTIVE", false, true, 1));
		return this;
	}

	Board alliance(AiState.Alliance alliance) {
		alliances.add(alliance);
		return this;
	}

	Board noAllianceWith(UUID nation, String blocker) {
		allianceBlockers.put(nation, List.of(blocker));
		return this;
	}

	Board call(AiState.Call call) {
		calls.add(call);
		return this;
	}

	Board conference(AiState.Conference conference) {
		conferences.add(conference);
		return this;
	}

	Board siege(String province, UUID besieger, UUID defender) {
		sieges.add(new AiState.Siege(province, besieger, defender, 2));
		return this;
	}

	/** A truce between A and {@code nation} until {@code untilRound}. */
	Board truce(UUID nation, int untilRound) {
		truces.put(nation, untilRound);
		return this;
	}

	/** The ruleset v8 switches (offensive calls to arms, truces do not block calls). */
	Board v8() {
		v8Features = true;
		return this;
	}

	Board fort(String province, int level) {
		forts.put(province, level);
		return this;
	}

	/** A ready battalion of {@code nation} in {@code province}; returns its id via {@link #lastUnit()}. */
	Board unit(UUID nation, String type, int manpower, String province) {
		return unit(nation, type, manpower, province, nation.equals(A));
	}

	Board unit(UUID nation, String type, int manpower, String province, boolean ready) {
		units.add(new AiState.Unit(new UUID(1, nextUnit++), nation, null, type, manpower, 1000, province, ready));
		return this;
	}

	UUID lastUnit() {
		return units.getLast().id();
	}

	Board city(String province, int agricultureWorkers, int workshops, long buildCost, boolean allowed) {
		cities.add(new AiState.City(cityId(province), province, Integer.parseInt(provinces.get(province)[3]), true,
				agricultureWorkers, workshops, new AiState.Option(buildCost, allowed), List.of()));
		return this;
	}

	Board recruitIn(String province) {
		recruitment.add(new AiState.RecruitCity(cityId(province), province, 10_000,
				List.of(new AiState.RecruitOption("INFANTRY", 10_000, 500, true),
						new AiState.RecruitOption("CAVALRY", 25_000, 1500, true),
						new AiState.RecruitOption("ARTILLERY", 40_000, 2000, true))));
		return this;
	}

	Board tech(String track, int level, boolean allowed) {
		techs.add(new AiState.TechOption(track, level, level + 1, 40, allowed));
		return this;
	}

	Board doctrine(String key, int river, int mountain, int siege, int upkeep) {
		doctrines.add(new AiState.DoctrineOption(key, 30, river, mountain, siege, upkeep, true));
		return this;
	}

	static UUID cityId(String province) {
		return UUID.nameUUIDFromBytes(("city-" + province).getBytes());
	}

	AiState build() {
		Map<String, AiState.Province> map = new HashMap<>();
		provinces.forEach((key, p) -> map.put(key, new AiState.Province(key, p[2], nation(p[0]), nation(p[1]),
				Integer.parseInt(p[3]), cityId(key), NEIGHBOURS.get(key), RIVERS.getOrDefault(key, Set.of()))));
		Map<UUID, AiState.Nation> nations = new HashMap<>();
		Map<UUID, String> capitals = Map.of(A, "a1", B, "b1", C, "c1");
		for (UUID n : List.of(A, B, C)) {
			String s = status.get(n);
			List<String> warBlockers = "PEACE".equals(s) ? List.of() : List.of("NOT_AT_PEACE");
			nations.put(n, new AiState.Nation(n, keys.get(n), keys.get(n), control.get(n), false, capitals.get(n), s,
					"ALLIED".equals(s) ? "DEFENSIVE" : null, null, truces.get(n), access.get(n), warBlockers,
					allianceBlockers.getOrDefault(n, "PEACE".equals(s) ? List.of() : List.of("NOT_AT_PEACE"))));
		}
		AiState.Economy economy = new AiState.Economy(treasury, arrears, gross, upkeep, gross - upkeep, 6, 2, 4000, cities);
		AiState.Research research = new AiState.Research(true, Map.of("MILITARY", 50, "CIVILIAN", 50, "DOCTRINE", 50),
				techs, doctrines, List.of(), List.of(), List.of());
		AiState.Diplomacy diplomacy = new AiState.Diplomacy(true,
				new AiState.Features(true, true, true, true, true, true, v8Features, v8Features), wars, alliances, calls, conferences, List.of(),
				500_000, 12, 1_000_000);
		return new AiState(UUID.nameUUIDFromBytes("game".getBytes()), round, round * 3, maxRounds, A, meKey, nations, map,
				units, economy, recruitment, research, diplomacy, forts, sieges,
				new AiState.Combat(1, 8000, 6500), List.of());
	}

	static Set<String> none() {
		return new HashSet<>();
	}

}
