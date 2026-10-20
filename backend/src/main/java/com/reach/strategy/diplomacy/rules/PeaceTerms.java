package com.reach.strategy.diplomacy.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Peace terms and their validation (docs/DIPLOMACY.md §5, APPROVED term types GAME_DESIGN §11.3).
 * The same validation runs when a proposal is made and again, against the state at that moment,
 * when it is accepted: a proposal that is no longer valid cannot be settled, and nothing of it is
 * applied. War score never restricts what two leaders may agree voluntarily (Q-D3).
 */
public final class PeaceTerms {

	private PeaceTerms() {
	}

	/** One obligation of {@code from} in favour of {@code to}; both are the war's two leaders. */
	public sealed interface Term permits ProvinceCession, Reparations, Tribute, PopulationTransfer {

		UUID from();

		UUID to();

	}

	/** Legal ownership (and control) of a province passes from {@code from} to {@code to}. */
	public record ProvinceCession(UUID from, UUID to, String provinceKey) implements Term {
	}

	/** An immediate payment in milligold. */
	public record Reparations(UUID from, UUID to, long amountMg) implements Term {
	}

	/** {@code from} pays a share of its gross income to {@code to} for a number of global rounds (ruleset). */
	public record Tribute(UUID from, UUID to) implements Term {
	}

	/** Civilians move from a city of {@code from} to a city of {@code to}; the total is conserved. */
	public record PopulationTransfer(UUID from, UUID to, UUID sourceCityId, UUID destinationCityId, int people)
			implements Term {
	}

	/**
	 * The world as the validation sees it.
	 *
	 * @param capital whether the province is its owner's starting capital (never cedable, so no
	 * nation can be dismantled by treaty)
	 */
	public record ProvinceFact(String provinceKey, UUID ownerNationId, UUID cityId, int population, boolean capital) {
	}

	/**
	 * @param partyA one war leader (the proposer)
	 * @param partyB the other war leader (the recipient)
	 * @param treasuryMg current treasury per party
	 */
	/**
	 * @param sideOf Phase 10 coalition peace: every nation that may give or receive, with its war side; a
	 * term must go from one side to the other. Null for a two-leader treaty (Phase 9).
	 * @param capitalsCedable ruleset v6: starting capitals may be ceded
	 */
	public record Facts(UUID partyA, UUID partyB, List<ProvinceFact> provinces, Map<UUID, Long> treasuryMg,
			Map<UUID, String> sideOf, boolean capitalsCedable) {

		public Facts(UUID partyA, UUID partyB, List<ProvinceFact> provinces, Map<UUID, Long> treasuryMg) {
			this(partyA, partyB, provinces, treasuryMg, null, false);
		}

		public Facts {
			provinces = List.copyOf(provinces);
			treasuryMg = Map.copyOf(treasuryMg);
			sideOf = sideOf == null ? null : Map.copyOf(sideOf);
		}

		Optional<ProvinceFact> province(String key) {
			return provinces.stream().filter(p -> p.provinceKey().equals(key)).findFirst();
		}

		Optional<ProvinceFact> city(UUID cityId) {
			return provinces.stream().filter(p -> p.cityId().equals(cityId)).findFirst();
		}

	}

	public enum Code {

		TOO_MANY_TERMS, NOT_A_PARTY, SAME_NATION, PROVINCE_NOT_FOUND, PROVINCE_NOT_OWNED,
		PROVINCE_DUPLICATE, CAPITAL_NOT_CEDABLE, AMOUNT_NOT_POSITIVE, AMOUNT_TOO_LARGE, INSUFFICIENT_GOLD,
		TRIBUTE_DUPLICATE, CITY_NOT_FOUND, SOURCE_NOT_OWNED, SOURCE_CEDED, DESTINATION_NOT_OWNED, SAME_CITY,
		PEOPLE_OUT_OF_RANGE, SHARE_EXCEEDED, RESIDUAL_TOO_LOW

	}

	/** @param term index of the offending term, or −1 for the proposal as a whole */
	public record Violation(int term, Code code) {
	}

	public static List<Violation> validate(List<Term> terms, Facts facts, DiplomacyRules rules) {
		List<Violation> violations = new ArrayList<>();
		if (terms.size() > rules.maxPeaceTerms()) {
			violations.add(new Violation(-1, Code.TOO_MANY_TERMS));
			return violations;
		}
		Set<String> ceded = new HashSet<>();
		Map<String, UUID> newOwner = new HashMap<>();
		for (Term term : terms) {
			if (term instanceof ProvinceCession c) {
				newOwner.putIfAbsent(c.provinceKey(), c.to());
			}
		}
		Map<UUID, Long> goldOwed = new HashMap<>();
		Set<UUID> tributePayers = new HashSet<>();
		Map<UUID, Integer> peopleBySource = new HashMap<>();
		for (int i = 0; i < terms.size(); i++) {
			Term term = terms.get(i);
			if (!party(term.from(), facts) || !party(term.to(), facts) || !opposite(term, facts)) {
				violations.add(new Violation(i, Code.NOT_A_PARTY));
				continue;
			}
			if (term.from().equals(term.to())) {
				violations.add(new Violation(i, Code.SAME_NATION));
				continue;
			}
			switch (term) {
				case ProvinceCession c -> {
					Optional<ProvinceFact> province = facts.province(c.provinceKey());
					if (province.isEmpty()) {
						violations.add(new Violation(i, Code.PROVINCE_NOT_FOUND));
					}
					else if (!ceded.add(c.provinceKey())) {
						violations.add(new Violation(i, Code.PROVINCE_DUPLICATE));
					}
					else if (!province.get().ownerNationId().equals(c.from())) {
						violations.add(new Violation(i, Code.PROVINCE_NOT_OWNED));
					}
					else if (province.get().capital() && !facts.capitalsCedable()) {
						violations.add(new Violation(i, Code.CAPITAL_NOT_CEDABLE));
					}
				}
				case Reparations r -> {
					if (r.amountMg() <= 0) {
						violations.add(new Violation(i, Code.AMOUNT_NOT_POSITIVE));
					}
					else if (r.amountMg() > rules.reparations().maxMg()) {
						violations.add(new Violation(i, Code.AMOUNT_TOO_LARGE));
					}
					else {
						long owed = Math.addExact(goldOwed.getOrDefault(r.from(), 0L), r.amountMg());
						goldOwed.put(r.from(), owed);
						if (owed > facts.treasuryMg().getOrDefault(r.from(), 0L)) {
							violations.add(new Violation(i, Code.INSUFFICIENT_GOLD));
						}
					}
				}
				case Tribute t -> {
					// One tribute per treaty: two opposite tributes would only cancel out.
					if (!tributePayers.isEmpty() || !tributePayers.add(t.from())) {
						violations.add(new Violation(i, Code.TRIBUTE_DUPLICATE));
					}
				}
				case PopulationTransfer p -> validatePopulation(i, p, facts, rules, newOwner, peopleBySource, violations);
			}
		}
		return violations;
	}

	private static void validatePopulation(int i, PopulationTransfer p, Facts facts, DiplomacyRules rules,
			Map<String, UUID> newOwner, Map<UUID, Integer> peopleBySource, List<Violation> violations) {
		DiplomacyRules.PopulationTransferRules limits = rules.populationTransfer();
		Optional<ProvinceFact> source = facts.city(p.sourceCityId());
		Optional<ProvinceFact> destination = facts.city(p.destinationCityId());
		if (source.isEmpty() || destination.isEmpty()) {
			violations.add(new Violation(i, Code.CITY_NOT_FOUND));
			return;
		}
		if (p.sourceCityId().equals(p.destinationCityId())) {
			violations.add(new Violation(i, Code.SAME_CITY));
			return;
		}
		if (!source.get().ownerNationId().equals(p.from())) {
			violations.add(new Violation(i, Code.SOURCE_NOT_OWNED));
			return;
		}
		if (newOwner.containsKey(source.get().provinceKey())) {
			violations.add(new Violation(i, Code.SOURCE_CEDED));
			return;
		}
		UUID destinationOwner = newOwner.getOrDefault(destination.get().provinceKey(),
				destination.get().ownerNationId());
		if (!destinationOwner.equals(p.to())) {
			violations.add(new Violation(i, Code.DESTINATION_NOT_OWNED));
			return;
		}
		if (p.people() < limits.minPeople() || p.people() > limits.maxPeople()) {
			violations.add(new Violation(i, Code.PEOPLE_OUT_OF_RANGE));
			return;
		}
		int population = source.get().population();
		int total = Math.addExact(peopleBySource.getOrDefault(p.sourceCityId(), 0), p.people());
		peopleBySource.put(p.sourceCityId(), total);
		// Q-D4: at most maxShareBps of the source city's current population, floored.
		long share = (long) population * limits.maxShareBps() / 10_000;
		if (total > share) {
			violations.add(new Violation(i, Code.SHARE_EXCEEDED));
		}
		else if (population - total < limits.minResidualPopulation()) {
			violations.add(new Violation(i, Code.RESIDUAL_TOO_LOW));
		}
	}

	private static boolean party(UUID nation, Facts facts) {
		if (nation == null) {
			return false;
		}
		return facts.sideOf() == null ? nation.equals(facts.partyA()) || nation.equals(facts.partyB())
				: facts.sideOf().containsKey(nation);
	}

	/** Coalition peace: a term moves assets from one side of the war to the other, never within a side. */
	private static boolean opposite(Term term, Facts facts) {
		return facts.sideOf() == null || term.from().equals(term.to())
				|| !facts.sideOf().get(term.from()).equals(facts.sideOf().get(term.to()));
	}

	/**
	 * Who must approve a treaty draft (Phase 10, APPROVED policy 2026-10-20): both negotiating
	 * principals always, and every other nation that gives up something of its own — a province it
	 * owns, gold, tribute or people from one of its cities. Receiving needs no approval, and ending a
	 * military occupation is not a loss of the occupier's assets. Sorted by nation id.
	 */
	public static List<UUID> requiredApprovers(Collection<UUID> principals, List<Term> terms) {
		Set<UUID> required = new TreeSet<>(principals);
		terms.forEach(t -> required.add(t.from()));
		return List.copyOf(required);
	}

	/**
	 * The PROVISIONAL placeholder for nations without a player until AI exists (Phase 11): an NPC
	 * approves a draft only if all it gives up are provinces the other side already holds militarily.
	 * It never pays gold or tribute and never gives up people. This is not strategy, only a
	 * deterministic rule so wars against NPC nations can end.
	 *
	 * @param controllerByProvince current military controller of each province
	 */
	public static boolean npcApproves(UUID npc, List<Term> terms, Map<String, UUID> controllerByProvince,
			Map<UUID, String> sideOf) {
		for (Term t : terms) {
			if (!t.from().equals(npc)) {
				continue;
			}
			if (!(t instanceof ProvinceCession c)) {
				return false;
			}
			UUID controller = controllerByProvince.get(c.provinceKey());
			String npcSide = sideOf.get(npc);
			if (controller == null || controller.equals(npc) || !sideOf.containsKey(controller)
					|| sideOf.get(controller).equals(npcSide)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The order in which an accepted treaty is applied (all in one transaction): reparations,
	 * cessions, population transfers, then tribute — so a population transfer may target a city ceded
	 * by the same treaty.
	 */
	public static List<Term> settlementOrder(List<Term> terms) {
		List<Term> ordered = new ArrayList<>();
		terms.stream().filter(Reparations.class::isInstance).forEach(ordered::add);
		terms.stream().filter(ProvinceCession.class::isInstance).forEach(ordered::add);
		terms.stream().filter(PopulationTransfer.class::isInstance).forEach(ordered::add);
		terms.stream().filter(Tribute.class::isInstance).forEach(ordered::add);
		return ordered;
	}

	/** Tribute due for one round: {@code floor(grossIncome × rateBps / 10,000)} (Q-D5, floored per ECONOMY §10a). */
	public static long tributeDue(long grossIncomeMg, DiplomacyRules.TributeRules rules) {
		if (grossIncomeMg <= 0) {
			return 0;
		}
		return Math.multiplyExact(grossIncomeMg, rules.rateBps()) / 10_000;
	}

	/** Shortfall policy (PROVISIONAL): the payer pays what its treasury holds, never more; no debt carries over. */
	public static long tributePaid(long dueMg, long treasuryMg) {
		return Math.max(0, Math.min(dueMg, treasuryMg));
	}

}
