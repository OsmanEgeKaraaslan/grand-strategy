package com.reach.strategy.ai.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Recruitment, reinforcement and movement (docs/AI.md §6–7). Every decision uses the battalions'
 * real eligibility and the nation's real access; the battle engine decides the battles.
 */
public final class Military {

	private Military() {
	}

	// --- recruitment -------------------------------------------------------------------------------

	/**
	 * Recruits while the army's upkeep stays under the goal's share of income and the treasury above
	 * the reserve, filling the composition the situation calls for (artillery when fortresses are
	 * within reach, some cavalry for flanking, infantry otherwise), in the city nearest the front.
	 */
	static List<AiAction> recruit(AiState state, AiRules rules, Strategy.Assessment assessment,
			Development.Budget budget, Set<String> failed) {
		AiRules.Military m = rules.military();
		AiRules.Personality p = rules.personalityOf(state.meKey());
		List<AiState.Unit> mine = state.unitsOf(state.me());
		long gross = Math.max(0, state.economy().grossIncomeMg());
		long room = gross * Strategy.upkeepShare(rules, p, assessment.goal()) / 10_000 - state.economy().upkeepMg();
		boolean belowMinimum = mine.size() < m.minimumBattalions();
		if ((room <= 0 && !belowMinimum) || state.recruitment().isEmpty()) {
			return List.of();
		}
		Map<String, Integer> counts = new HashMap<>();
		mine.forEach(u -> counts.merge(u.type(), 1, Integer::sum));
		int artilleryShare = fortifiedTargetsInReach(state) ? m.artilleryShareWhenFortifiedBps() : 0;
		Map<String, Integer> shares = Map.of("ARTILLERY", artilleryShare, "CAVALRY", m.cavalryShareBps(), "INFANTRY",
				10_000 - artilleryShare - m.cavalryShareBps());
		List<AiState.RecruitCity> cities = citiesByFront(state);
		List<AiAction> actions = new ArrayList<>();
		Map<String, Integer> index = new HashMap<>();
		int total = mine.size();
		for (int i = 0; i < m.maxRecruitsPerTurn(); i++) {
			String type = mostNeeded(shares, counts, total);
			boolean recruited = false;
			for (AiState.RecruitCity city : cities) {
				for (String candidate : List.of(type, "INFANTRY")) {
					Optional<AiState.RecruitOption> option = city.options()
						.stream()
						.filter(o -> o.unitType().equals(candidate) && o.allowed())
						.findFirst();
					if (option.isEmpty() || !budget.afford(option.get().costMg())
							|| (room < option.get().upkeepMg() && !(belowMinimum && total < m.minimumBattalions()))) {
						continue;
					}
					String key = city.cityId() + candidate;
					int n = index.merge(key, 1, Integer::sum);
					// The same city cannot pay twice the population of a single offer; re-observed next pass.
					if (n > 1) {
						continue;
					}
					AiAction action = new AiAction.Recruit(city.cityId(), candidate, n,
							"recruit " + candidate.toLowerCase() + " in " + city.province() + " (" + assessment.goal().toLowerCase()
									+ ", " + total + " battalions)");
					if (failed.contains(action.signature())) {
						continue;
					}
					actions.add(action);
					budget.spend(option.get().costMg());
					room -= option.get().upkeepMg();
					counts.merge(candidate, 1, Integer::sum);
					total++;
					recruited = true;
					break;
				}
				if (recruited) {
					break;
				}
			}
			if (!recruited) {
				break;
			}
		}
		return actions;
	}

	private static String mostNeeded(Map<String, Integer> shares, Map<String, Integer> counts, int total) {
		String best = "INFANTRY";
		long bestDeficit = Long.MIN_VALUE;
		for (String type : List.of("INFANTRY", "CAVALRY", "ARTILLERY")) {
			long deficit = (long) shares.get(type) * (total + 1) - 10_000L * counts.getOrDefault(type, 0);
			if (deficit > bestDeficit) {
				bestDeficit = deficit;
				best = type;
			}
		}
		return best;
	}

	/** Recruitment cities, nearest to an enemy (or the strongest neighbour) first, then by key. */
	static List<AiState.RecruitCity> citiesByFront(AiState state) {
		Set<String> front = new HashSet<>();
		Set<UUID> rivals = new HashSet<>(state.enemies());
		for (AiState.Province p : state.provinces().values()) {
			if (rivals.contains(p.controller())) {
				front.add(p.key());
			}
		}
		Map<String, Integer> distance = new HashMap<>();
		for (AiState.RecruitCity city : state.recruitment()) {
			Map<String, Integer> steps = Paths.steps(state, city.province(), k -> true);
			int nearest = front.stream().mapToInt(k -> steps.getOrDefault(k, 99)).min().orElse(50);
			distance.put(city.province(), nearest);
		}
		return state.recruitment()
			.stream()
			.sorted(Comparator.comparing((AiState.RecruitCity c) -> distance.get(c.province()))
				.thenComparing(c -> -c.population())
				.thenComparing(AiState.RecruitCity::province))
			.toList();
	}

	static boolean fortifiedTargetsInReach(AiState state) {
		for (AiState.Province p : state.provinces().values()) {
			if (state.atWarWith(p.controller()) && state.fortLevels().getOrDefault(p.key(), 0) > 0) {
				return true;
			}
		}
		return false;
	}

	// --- reinforcement -----------------------------------------------------------------------------

	/** Refills worn battalions that stay where they are, in provinces whose city the nation holds. */
	static List<AiAction> reinforce(AiState state, AiRules rules, Set<UUID> moving, Development.Budget budget,
			Set<String> failed) {
		List<AiAction> actions = new ArrayList<>();
		Map<String, Long> recruitCost = new HashMap<>();
		for (AiState.RecruitCity c : state.recruitment()) {
			c.options().forEach(o -> recruitCost.merge(o.unitType(), o.costMg(), Math::max));
		}
		List<AiState.Unit> worn = state.unitsOf(state.me())
			.stream()
			.filter(u -> u.commander() == null && u.ready() && !moving.contains(u.id()))
			.filter(u -> u.maxManpower() - u.manpower() >= rules.military().reinforceMinMissing())
			.filter(u -> {
				AiState.Province p = state.provinces().get(u.province());
				return p != null && p.owner().equals(state.me()) && p.controller().equals(state.me());
			})
			.sorted(Comparator.comparing((AiState.Unit u) -> u.manpower() - u.maxManpower()).thenComparing(u -> u.id().toString()))
			.toList();
		for (AiState.Unit u : worn) {
			if (actions.size() >= 2) {
				break;
			}
			int soldiers = Math.min(500, u.maxManpower() - u.manpower());
			long cost = recruitCost.getOrDefault(u.type(), 0L) * soldiers / Math.max(1, u.maxManpower());
			AiAction action = new AiAction.Reinforce(u.id(), soldiers,
					"reinforce a worn " + u.type().toLowerCase() + " battalion in " + u.province());
			if (budget.afford(cost) && !failed.contains(action.signature())) {
				budget.spend(cost);
				actions.add(action);
			}
		}
		return actions;
	}

	// --- movement ----------------------------------------------------------------------------------

	/** Planned moves: quiet moves first (captures, marches), attacks last (each is re-observed). */
	public record Moves(List<AiAction> quiet, List<AiAction> attacks, Set<UUID> moving) {
	}

	/**
	 * @param warTarget the nation the computer prepares to attack (staging), or null
	 */
	static Moves move(AiState state, AiRules rules, Strategy.Assessment assessment, UUID warTarget, Set<String> failed) {
		AiRules.Military m = rules.military();
		AiRules.Personality p = rules.personalityOf(state.meKey());
		int attackRatio = Strategy.attackRatio(rules, p);
		Set<UUID> enemies = state.enemies();
		String capital = state.self().capitalKey();
		Map<String, Long> objectives = objectives(state, rules, assessment, warTarget);
		Set<String> besieging = new HashSet<>();
		state.sieges().stream().filter(s -> s.besieger().equals(state.me())).forEach(s -> besieging.add(s.province()));

		List<AiAction> quiet = new ArrayList<>();
		List<AiAction> attacks = new ArrayList<>();
		Set<UUID> moving = new HashSet<>();
		for (Map.Entry<String, List<AiState.Unit>> stack : state.readyStacks().entrySet()) {
			String from = stack.getKey();
			List<AiState.Unit> movable = new ArrayList<>(stack.getValue());
			if (besieging.contains(from)) {
				continue; // keep the siege going
			}
			// Keep a garrison in the capital while anybody could threaten it.
			if (from.equals(capital)
					&& (!enemies.isEmpty() || assessment.localThreat() > 0 || Strategy.BUILDUP.equals(assessment.goal()))) {
				long hostileNear = Strength.threatAround(state, capital, m.threatRadius(), rules);
				movable.sort(Comparator.comparing((AiState.Unit u) -> -Strength.of(u, rules)).thenComparing(u -> u.id().toString()));
				long kept = 0;
				List<AiState.Unit> garrison = new ArrayList<>();
				for (AiState.Unit u : List.copyOf(movable)) {
					if (garrison.isEmpty() || kept * 10_000 < hostileNear * (10_000L + p.defenseBps())) {
						garrison.add(u);
						kept += Strength.of(u, rules);
						movable.remove(u);
					}
				}
			}
			if (movable.isEmpty()) {
				continue;
			}
			List<UUID> ids = movable.stream().map(AiState.Unit::id).sorted().toList();

			// a) the best attack on an adjacent enemy force
			Optional<AiAction> attack = bestAttack(state, rules, from, movable, ids, objectives, attackRatio, failed);
			if (attack.isPresent()) {
				attacks.add(attack.get());
				moving.addAll(ids);
				continue;
			}
			// b) take an undefended enemy-held province next door
			String capture = null;
			long captureValue = 0;
			for (String q : state.provinces().get(from).neighbours()) {
				AiState.Province target = state.provinces().get(q);
				if (target == null || !enemies.contains(target.controller()) || !Strength.hostileIn(state, q).isEmpty()
						|| !state.mayEnter(q)) {
					continue;
				}
				long value = objectives.getOrDefault(q, 1L);
				if (value > captureValue) {
					captureValue = value;
					capture = q;
				}
			}
			if (capture != null) {
				AiAction action = new AiAction.Move(ids, from, capture, false,
						"occupy undefended " + capture + (state.fortLevels().getOrDefault(capture, 0) > 0 ? " (siege)" : ""));
				if (!failed.contains(action.signature())) {
					quiet.add(action);
					moving.addAll(ids);
					continue;
				}
			}
			// c) a weak stack next to a much stronger enemy falls back towards friendly forces
			long mine = Strength.of(movable, rules);
			long adjacentThreat = 0;
			for (String q : state.provinces().get(from).neighbours()) {
				adjacentThreat = Math.max(adjacentThreat, Strength.of(Strength.hostileIn(state, q), rules));
			}
			boolean ownCity = state.provinces().get(from).owner().equals(state.me());
			if (!ownCity && adjacentThreat * 10_000 > mine * (long) (attackRatio + 5000)) {
				Optional<Paths.Route> back = Paths.route(state, from, friendlyProvinces(state),
						k -> state.mayEnter(k) && Strength.hostileIn(state, k).isEmpty());
				if (back.isPresent()) {
					AiAction action = new AiAction.Move(ids, from, back.get().next(), false,
							"fall back from " + from + " before a stronger enemy");
					if (!failed.contains(action.signature())) {
						quiet.add(action);
						moving.addAll(ids);
					}
				}
				continue;
			}
			// d) march towards the most valuable objective
			if (objectives.isEmpty() || objectives.containsKey(from) && !enemies.isEmpty()
					&& Strength.hostileIn(state, from).isEmpty() && state.provinces().get(from).controller().equals(state.me())
					&& objectives.get(from) > 1) {
				continue;
			}
			Optional<Paths.Route> route = bestRoute(state, from, objectives);
			if (route.isPresent() && !route.get().next().equals(from)) {
				String next = route.get().next();
				if (!Strength.hostileIn(state, next).isEmpty()) {
					continue; // that would be an attack, judged in a)
				}
				AiAction action = new AiAction.Move(ids, from, next, false, "march towards " + route.get().goal());
				if (!failed.contains(action.signature())) {
					quiet.add(action);
					moving.addAll(ids);
				}
			}
		}
		return new Moves(quiet, attacks, moving);
	}

	private static Optional<AiAction> bestAttack(AiState state, AiRules rules, String from, List<AiState.Unit> movable,
			List<UUID> ids, Map<String, Long> objectives, int attackRatio, Set<String> failed) {
		AiAction best = null;
		long bestScore = 0;
		for (String q : state.provinces().get(from).neighbours()) {
			List<AiState.Unit> hostile = Strength.hostileIn(state, q);
			if (hostile.isEmpty() || !state.mayEnter(q)) {
				continue;
			}
			long attack = Strength.attack(state, movable, from, q, rules);
			long defence = Strength.of(hostile, rules);
			long ratio = Strength.ratioBps(attack, defence);
			long value = objectives.getOrDefault(q, 1L);
			// High-value objectives justify a calculated risk.
			int needed = value >= 3 ? rules.military().riskyAttackRatioBps() : attackRatio;
			if (ratio < needed) {
				continue;
			}
			long score = ratio * value;
			AiAction action = new AiAction.Move(ids, from, q, true,
					"attack " + q + " at odds " + ratio / 100 + "% (" + attack + " vs " + defence + ")");
			if (score > bestScore && !failed.contains(action.signature())) {
				bestScore = score;
				best = action;
			}
		}
		return Optional.ofNullable(best);
	}

	private static Optional<Paths.Route> bestRoute(AiState state, String from, Map<String, Long> objectives) {
		// Cheapest path to the highest-value objective group: try values from high to low.
		List<Long> values = objectives.values().stream().distinct().sorted(Comparator.reverseOrder()).toList();
		for (long value : values) {
			List<String> goals = objectives.entrySet()
				.stream()
				.filter(e -> e.getValue() == value)
				.map(Map.Entry::getKey)
				.sorted()
				.toList();
			Optional<Paths.Route> route = Paths.route(state, from, goals,
					k -> state.mayEnter(k) && (Strength.hostileIn(state, k).isEmpty() || goals.contains(k)));
			if (route.isPresent()) {
				return route;
			}
		}
		return Optional.empty();
	}

	/**
	 * Where the army wants to be, with a value: at war, enemy-held provinces (capitals and own occupied
	 * land worth more, populous ones more) and own threatened provinces when defending; at peace with a
	 * planned war, the own provinces bordering the target (staging); otherwise the border facing the
	 * strongest neighbour while building up.
	 */
	static Map<String, Long> objectives(AiState state, AiRules rules, Strategy.Assessment assessment, UUID warTarget) {
		AiRules.Military m = rules.military();
		Map<String, Long> objectives = new HashMap<>();
		Set<UUID> enemies = state.enemies();
		if (!enemies.isEmpty()) {
			for (AiState.Province p : state.provinces().values()) {
				if (!enemies.contains(p.controller())) {
					continue;
				}
				long value = 1 + p.population() / Math.max(1, m.objectivePopulationPerPoint());
				AiState.Nation controller = state.nations().get(p.controller());
				if (controller != null && p.key().equals(controller.capitalKey())) {
					value += m.objectiveCapitalBonus();
				}
				if (p.owner().equals(state.me())) {
					value += 3;
				}
				objectives.put(p.key(), value);
			}
			if (Strategy.DEFEND.equals(assessment.goal())) {
				for (AiState.Province own : state.ownedBy(state.me())) {
					if (own.controller().equals(state.me()) && Strength.threatAround(state, own.key(), 1, rules) > 0) {
						long value = own.key().equals(state.self().capitalKey()) ? 6 : 3;
						objectives.merge(own.key(), value, Math::max);
					}
				}
			}
			return objectives;
		}
		UUID facing = warTarget != null ? warTarget
				: Strategy.BUILDUP.equals(assessment.goal()) ? assessment.strongestNeighbour() : null;
		if (facing != null) {
			for (AiState.Province own : state.controlledBy(state.me())) {
				for (String key : own.neighbours()) {
					AiState.Province n = state.provinces().get(key);
					if (n != null && n.controller().equals(facing)) {
						objectives.put(own.key(), 2L);
					}
				}
			}
		}
		return objectives;
	}

	private static Set<String> friendlyProvinces(AiState state) {
		Set<String> result = new HashSet<>();
		for (AiState.Province p : state.provinces().values()) {
			if (p.controller().equals(state.me()) || state.friends().contains(p.controller())) {
				result.add(p.key());
			}
		}
		return result;
	}

}
