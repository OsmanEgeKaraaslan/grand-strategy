package com.reach.strategy.diplomacy.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

import com.reach.strategy.diplomacy.rules.PeaceTerms.Code;
import com.reach.strategy.diplomacy.rules.PeaceTerms.Facts;
import com.reach.strategy.diplomacy.rules.PeaceTerms.ProvinceCession;
import com.reach.strategy.diplomacy.rules.PeaceTerms.ProvinceFact;
import com.reach.strategy.diplomacy.rules.PeaceTerms.Reparations;
import com.reach.strategy.diplomacy.rules.PeaceTerms.Term;
import com.reach.strategy.diplomacy.rules.PeaceTerms.Tribute;
import com.reach.strategy.diplomacy.rules.Victory.NationFacts;
import com.reach.strategy.siege.rules.ControlDecision;
import org.junit.jupiter.api.Test;

/**
 * Phase 10 coalition rules: peace terms between the sides of a coalition war, the approvals a draft
 * needs, the NPC placeholder, cedable capitals, coalition occupation, and the v6 score with gold,
 * domination and eliminated nations (docs/DIPLOMACY.md §13–15).
 */
class CoalitionPeaceRulesTest {

	static final UUID A1 = new UUID(0, 1);
	static final UUID A2 = new UUID(0, 2);
	static final UUID D1 = new UUID(0, 3);
	static final UUID D2 = new UUID(0, 4);
	static final UUID OUTSIDER = new UUID(0, 5);

	static final Map<UUID, String> SIDES = Map.of(A1, "ATTACKER", A2, "ATTACKER", D1, "DEFENDER", D2, "DEFENDER");

	static final List<ProvinceFact> PROVINCES = List.of(new ProvinceFact("a2-capital", A2, new UUID(1, 1), 5000, true),
			new ProvinceFact("a2-farm", A2, new UUID(1, 2), 3000, false),
			new ProvinceFact("d1-capital", D1, new UUID(1, 3), 5000, true),
			new ProvinceFact("d2-farm", D2, new UUID(1, 4), 3000, false));

	static Facts facts(boolean capitalsCedable) {
		return new Facts(A1, D1, PROVINCES, Map.of(A1, 50_000L, A2, 50_000L, D1, 50_000L, D2, 50_000L), SIDES,
				capitalsCedable);
	}

	static List<Code> codes(List<Term> terms, Facts facts) {
		return PeaceTerms.validate(terms, facts, DiplomacyRulesTest.V5).stream().map(PeaceTerms.Violation::code).toList();
	}

	// --- terms between coalitions --------------------------------------------------------------

	@Test
	void anyNationOfOneSideMayGiveToAnyNationOfTheOther() {
		assertThat(codes(List.of(new ProvinceCession(A2, D2, "a2-farm"), new Reparations(D2, A1, 10_000),
				new Tribute(A2, D1)), facts(false))).isEmpty();
	}

	@Test
	void termsWithinOneSideOrWithOutsidersAreRefused() {
		assertThat(codes(List.of(new Reparations(A1, A2, 1_000)), facts(false))).containsExactly(Code.NOT_A_PARTY);
		assertThat(codes(List.of(new Reparations(OUTSIDER, D1, 1_000)), facts(false))).containsExactly(Code.NOT_A_PARTY);
	}

	@Test
	void capitalsAreCedableOnlyWhenTheRulesetSaysSo() {
		List<Term> capital = List.of(new ProvinceCession(D1, A1, "d1-capital"));
		assertThat(codes(capital, facts(false))).containsExactly(Code.CAPITAL_NOT_CEDABLE);
		assertThat(codes(capital, facts(true))).isEmpty();
	}

	@Test
	void theTwoPrincipalsAndEveryGiverMustApproveButNoBeneficiary() {
		List<Term> terms = List.of(new ProvinceCession(D2, A1, "d2-farm"), new Reparations(A1, D1, 5_000),
				new Reparations(D1, A2, 1_000));
		assertThat(PeaceTerms.requiredApprovers(Set.of(A1, D1), terms)).containsExactly(A1, D1, D2);
		assertThat(PeaceTerms.requiredApprovers(Set.of(A1, D1), List.of())).containsExactly(A1, D1);
		// A2 only receives: never asked.
		assertThat(PeaceTerms.requiredApprovers(Set.of(A1, D1), terms)).doesNotContain(A2);
	}

	@Test
	void theNpcPlaceholderGivesUpOnlyProvincesTheOtherSideAlreadyHolds() {
		Map<String, UUID> controllers = Map.of("d2-farm", A2, "d1-capital", D1, "a2-farm", A2);
		assertThat(PeaceTerms.npcApproves(D2, List.of(new ProvinceCession(D2, A1, "d2-farm")), controllers, SIDES))
			.isTrue();
		// Its own province it still holds, gold, or a tribute: refused.
		assertThat(PeaceTerms.npcApproves(D1, List.of(new ProvinceCession(D1, A1, "d1-capital")), controllers, SIDES))
			.isFalse();
		assertThat(PeaceTerms.npcApproves(D2, List.of(new Reparations(D2, A1, 1)), controllers, SIDES)).isFalse();
		assertThat(PeaceTerms.npcApproves(D2, List.of(new Tribute(D2, A1)), controllers, SIDES)).isFalse();
		// Receiving costs it nothing.
		assertThat(PeaceTerms.npcApproves(D2, List.of(new Reparations(A1, D2, 1)), controllers, SIDES)).isTrue();
	}

	// --- coalition occupation ------------------------------------------------------------------

	@Test
	void alliedInvadersOccupyTogetherUnderTheStrongestPresent() {
		BiPredicate<UUID, UUID> atWar = (x, y) -> SIDES.containsKey(x) && SIDES.containsKey(y)
				&& !SIDES.get(x).equals(SIDES.get(y));
		assertThat(ControlDecision.decide(D1, Map.of(A1, 300, A2, 500), 0, null, atWar, true))
			.isEqualTo(new ControlDecision.Occupy(A2));
		assertThat(ControlDecision.decide(D1, Map.of(A1, 400, A2, 400), 1, null, atWar, true))
			.isEqualTo(new ControlDecision.StartSiege(A1));
		// A defender present still contests the province.
		assertThat(ControlDecision.decide(D1, Map.of(A1, 400, D2, 100), 0, null, atWar, true))
			.isEqualTo(new ControlDecision.None());
	}

	// --- victory -------------------------------------------------------------------------------

	@Test
	void v6ScoresGoldAndRanksEliminatedNationsLast() {
		DiplomacyRules.VictoryRules v6 = new DiplomacyRules.VictoryRules(30, 20, 1, 1, true);
		// 2 provinces × 20 + 4 (thousand people) + 3 (hundred gold, rounded down)
		assertThat(Victory.score(new NationFacts(A1, 2, 4_999, 399_999, false), v6)).isEqualTo(47);
		Victory.Outcome outcome = Victory.rank(List.of(new NationFacts(A1, 0, 0, 0, true),
				new NationFacts(A2, 1, 0, 0, false), new NationFacts(D1, 0, 0, 0, true)), v6);
		assertThat(outcome.winnerNationId()).isEqualTo(A2);
		assertThat(outcome.standings().get(1).rank()).isEqualTo(2);
		// Older rulesets ignore the treasury.
		assertThat(Victory.score(new NationFacts(A1, 2, 4_999, 399_999, false), new DiplomacyRules.VictoryRules(30, 20, 1)))
			.isEqualTo(44);
	}

	@Test
	void theOnlyNationLeftDominates() {
		assertThat(Victory.dominator(List.of(new NationFacts(A1, 9, 0, 0, false), new NationFacts(A2, 0, 0, 0, true))))
			.contains(A1);
		assertThat(Victory.dominator(List.of(new NationFacts(A1, 9, 0, 0, false), new NationFacts(A2, 1, 0, 0, false))))
			.isEmpty();
	}

}
