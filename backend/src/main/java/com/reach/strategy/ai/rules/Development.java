package com.reach.strategy.ai.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Economy, fortification and research decisions (docs/AI.md §5). Investments are ranked by payback
 * — rounds until the extra income repays the cost — and bought from the treasury above the reserve
 * while the payback still fits in the rounds left. Integer milligold throughout; the server's own
 * prices and "allowed" flags are used, never re-derived.
 */
public final class Development {

	private Development() {
	}

	/** Gold the turn may still spend; shared by the planners in turn order. */
	static final class Budget {

		private long remainingMg;

		Budget(long remainingMg) {
			this.remainingMg = Math.max(0, remainingMg);
		}

		boolean afford(long costMg) {
			return costMg <= remainingMg;
		}

		void spend(long costMg) {
			remainingMg -= costMg;
		}

		long remaining() {
			return remainingMg;
		}

	}

	private record Investment(AiAction action, long costMg, long paybackRounds, String key) {
	}

	/** Repairs, new workshops and upgrades, cheapest payback first. */
	static List<AiAction> economy(AiState state, AiRules rules, Strategy.Assessment assessment, Budget budget,
			Set<String> failed) {
		AiRules.Economy e = rules.economy();
		AiRules.Personality p = rules.personalityOf(state.meKey());
		int roundsLeft = state.roundsLeft();
		if (roundsLeft < e.minRoundsLeftToInvest() || Strategy.RECOVER.equals(assessment.goal()) && budget.remaining() == 0) {
			return List.of();
		}
		// Economic personalities accept longer paybacks; war goals keep gold for the army.
		int tolerance = 10_000 + p.economyBps() / 2 - (Strategy.DEFEND.equals(assessment.goal()) ? 3000 : 0);
		List<Investment> candidates = new ArrayList<>();
		for (AiState.City city : state.economy().cities()) {
			if (!city.controlled()) {
				continue;
			}
			for (AiState.WorkshopOption w : city.workshops()) {
				if (!w.allowed() || w.gainMg() <= 0) {
					continue;
				}
				long payback = ceilDiv(w.costMg(), w.gainMg());
				int limit = "REPAIR".equals(w.kind()) ? e.repairPaybackRounds() : e.upgradePaybackRounds();
				if (payback * 10_000 <= (long) limit * tolerance && payback < roundsLeft) {
					AiAction action = "REPAIR".equals(w.kind())
							? new AiAction.RepairWorkshop(city.cityId(), w.workshopId(),
									"repair a damaged workshop in " + city.province() + " (pays back in " + payback + " rounds)")
							: new AiAction.UpgradeWorkshop(city.cityId(), w.workshopId(),
									"upgrade a workshop in " + city.province() + " to Tier 2 (pays back in " + payback + " rounds)");
					candidates.add(new Investment(action, w.costMg(), payback, city.province()));
				}
			}
			AiState.Option build = city.build();
			if (build != null && build.allowed() && city.workshopCount() < state.economy().maxWorkshopsPerCity()) {
				long workers = Math.min(state.economy().workshopCapacity(), city.agricultureWorkers());
				long gain = workers * state.economy().workshopGainPerWorkerMg();
				// A workshop operates from the round after it is built.
				long payback = gain <= 0 ? Long.MAX_VALUE : ceilDiv(build.costMg(), gain) + 1;
				if (gain > 0 && payback * 10_000 <= (long) e.workshopPaybackRounds() * tolerance && payback < roundsLeft) {
					candidates.add(new Investment(new AiAction.BuildWorkshop(city.cityId(), 1,
							"build a workshop in " + city.province() + " for " + workers + " farmers (pays back in " + payback
									+ " rounds)"),
							build.costMg(), payback, city.province()));
				}
			}
		}
		candidates.sort(Comparator.comparingLong(Investment::paybackRounds).thenComparing(Investment::key));
		List<AiAction> actions = new ArrayList<>();
		for (Investment investment : candidates) {
			if (actions.size() >= e.maxConstructionsPerTurn()) {
				break;
			}
			if (failed.contains(investment.action().signature()) || !budget.afford(investment.costMg())) {
				continue;
			}
			budget.spend(investment.costMg());
			actions.add(investment.action());
		}
		return actions;
	}

	/** Capital fortress when the nation feels threatened and can afford it. */
	static List<AiAction> fortress(AiState state, AiRules rules, Strategy.Assessment assessment, Budget budget,
			Set<String> failed) {
		boolean threatened = Strategy.DEFEND.equals(assessment.goal()) || assessment.capitalThreatened()
				|| Strategy.BUILDUP.equals(assessment.goal()) && rules.personalityOf(state.meKey()).defenseBps() > 0;
		if (!threatened || state.economy().treasuryMg() < rules.economy().fortressMinTreasuryMg()) {
			return List.of();
		}
		for (AiState.FortressOption f : state.fortresses()) {
			AiAction action = new AiAction.BuildFortress(f.cityId(), f.targetLevel(),
					"fortify " + f.province() + " to level " + f.targetLevel() + " (" + assessment.goal().toLowerCase() + ")");
			if (f.allowed() && budget.afford(f.costMg()) && !failed.contains(action.signature())) {
				budget.spend(f.costMg());
				return List.of(action);
			}
		}
		return List.of();
	}

	/** Research institutions, one per turn, in the personality's order of preference. */
	static List<AiAction> institutions(AiState state, AiRules rules, Budget budget, Set<String> failed) {
		AiState.Research r = state.research();
		AiRules.Economy e = rules.economy();
		if (!r.available() || state.round() < e.institutionFirstRound()
				|| state.economy().netIncomeMg() < e.institutionMinNetIncomeMg()
				|| r.institutionTypes().size() >= e.maxInstitutions() || state.roundsLeft() < 8) {
			return List.of();
		}
		for (String type : Strategy.institutionPreference(rules, state.meKey())) {
			if (r.institutionTypes().contains(type)) {
				continue;
			}
			for (AiState.InstitutionOption o : r.institutionOptions()) {
				AiAction action = new AiAction.BuildInstitution(o.cityId(), type,
						"build a " + type.toLowerCase().replace('_', ' ') + " in " + o.province());
				if (o.type().equals(type) && o.allowed() && budget.afford(o.costMg()) && !failed.contains(action.signature())) {
					budget.spend(o.costMg());
					return List.of(action);
				}
			}
		}
		return List.of();
	}

	/** Spend accumulated research points: technologies, then one doctrine fitting the situation. */
	static List<AiAction> research(AiState state, AiRules rules, Strategy.Assessment assessment, Set<String> failed) {
		AiState.Research r = state.research();
		if (!r.available()) {
			return List.of();
		}
		List<AiAction> actions = new ArrayList<>();
		for (AiState.TechOption t : r.technologies().stream().sorted(Comparator.comparing(AiState.TechOption::track)).toList()) {
			AiAction action = new AiAction.UnlockTechnology(t.track(), t.targetLevel(),
					t.track().toLowerCase() + " technology level " + t.targetLevel());
			if (t.allowed() && !failed.contains(action.signature())) {
				actions.add(action);
			}
		}
		AiState.DoctrineOption best = null;
		long bestScore = 0;
		for (AiState.DoctrineOption d : r.doctrines().stream().sorted(Comparator.comparing(AiState.DoctrineOption::key)).toList()) {
			if (!d.allowed()) {
				continue;
			}
			long score = doctrineValue(state, rules, d, assessment);
			if (score > bestScore) {
				bestScore = score;
				best = d;
			}
		}
		if (best != null) {
			AiAction action = new AiAction.UnlockDoctrine(best.key(), "doctrine " + best.key() + " suits the frontier");
			if (!failed.contains(action.signature())) {
				actions.add(action);
			}
		}
		return actions;
	}

	/**
	 * How much a doctrine would help now: river and mountain reductions weigh the crossings and
	 * mountains on the frontier, siege bonuses the enemy fortresses within reach, upkeep reduction
	 * the share of income the army costs.
	 */
	static long doctrineValue(AiState state, AiRules rules, AiState.DoctrineOption d, Strategy.Assessment assessment) {
		int rivers = 0;
		int mountains = 0;
		int forts = 0;
		for (AiState.Province own : state.controlledBy(state.me())) {
			for (String key : own.neighbours()) {
				AiState.Province n = state.provinces().get(key);
				if (n == null || n.controller().equals(state.me())) {
					continue;
				}
				if (own.riverTo().contains(key)) {
					rivers++;
				}
				if (n.mountains()) {
					mountains++;
				}
				if (state.fortLevels().getOrDefault(key, 0) > 0) {
					forts++;
				}
			}
		}
		long upkeepShare = state.economy().grossIncomeMg() <= 0 ? 0
				: state.economy().upkeepMg() * 10_000 / state.economy().grossIncomeMg();
		return (long) d.riverBps() * rivers + (long) d.mountainBps() * mountains + (long) d.siegeBps() * forts * 2
				+ (long) d.upkeepBps() * upkeepShare / 1000;
	}

	static long ceilDiv(long a, long b) {
		return (a + b - 1) / b;
	}

}
