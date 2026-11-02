package com.reach.strategy.ai.rules;

import static com.reach.strategy.ai.rules.Board.A;
import static com.reach.strategy.ai.rules.Board.B;
import static com.reach.strategy.ai.rules.Board.C;
import static com.reach.strategy.ai.rules.Board.RULES;
import static com.reach.strategy.ai.rules.Board.RULES_V8;
import static com.reach.strategy.ai.rules.Board.none;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Ruleset v8 peace decisions of computer nations (docs/AI.md §10a): a winner holding enemy land wants
 * a useful share of it instead of a white peace, but its demand shrinks as the war drags on, so wars
 * still end; a nation that can still take land does not settle for nothing while the war is young;
 * a loser and a stalemate behave as before. v7 values are compared where the behaviour differs.
 */
class PeaceEvaluationV8Test {

	private static final UUID WAR = new UUID(9, 1);
	private static final UUID CONF = new UUID(9, 2);

	private static AiState.Term province(UUID from, UUID to, String key) {
		return new AiState.Term("PROVINCE", from, to, key, null, null, null, null);
	}

	/** B (requester) and A negotiate; revision {@code revision} with {@code terms} awaits A's decision. */
	private static AiState.Conference awaitingA(int revision, List<AiState.Term> terms) {
		Map<UUID, String> approvals = new HashMap<>();
		approvals.put(A, null);
		approvals.put(B, "APPROVED");
		return new AiState.Conference(CONF, WAR, "GENERAL", B, A, "ACTIVE", revision, terms, approvals, List.of(), false,
				true, true, true, 0, B, 2);
	}

	/** A attacks B, holds both of B's provinces and is far stronger; the war started in round 2. */
	private static Board decisiveVictory(int round) {
		return new Board().me("aurelia")
			.round(round, 30)
			.war(WAR, A, B, Map.of(A, "ATTACKER", B, "DEFENDER"), 2, 20)
			.owner("b1", B, A)
			.owner("b2", B, A)
			.unit(A, "INFANTRY", 1000, "b1")
			.unit(A, "INFANTRY", 1000, "b1")
			.unit(A, "INFANTRY", 1000, "b2")
			.unit(B, "INFANTRY", 300, "b2");
	}

	private static Statecraft.Decision decide(AiState state, AiRules rules) {
		return Statecraft.evaluate(state, rules, state.diplomacy().conferences().getFirst(), state.diplomacy().wars().getFirst());
	}

	@Test
	void aDecisiveWinnerNoLongerSettlesForAWhitePeaceAndCountersWithTheLandItHolds() {
		AiState state = decisiveVictory(12).conference(awaitingA(1, List.of())).build();

		// v7: ten rounds in, the winner was "tired" and took the white peace.
		assertThat(decide(state, RULES).approve()).isTrue();

		Statecraft.Decision v8 = decide(state, RULES_V8);
		assertThat(v8.approve()).isFalse();
		assertThat(v8.why()).contains("we hold 110 gold-equivalent of their land");
		List<AiAction.SubmitDraft> drafts = AiBrain.planNegotiation(state, RULES_V8, none())
			.stream()
			.filter(AiAction.SubmitDraft.class::isInstance)
			.map(AiAction.SubmitDraft.class::cast)
			.toList();
		assertThat(drafts).singleElement()
			.satisfies(d -> assertThat(d.terms()).extracting(AiState.Term::provinceKey).containsExactlyInAnyOrder("b1", "b2"));
	}

	@Test
	void theWinnerAcceptsAUsefulShareAndAsksForLessTheLongerTheWarLasts() {
		// Round 10, eight rounds of war: it wants 70% of the 110,000 it holds.
		AiState early = decisiveVictory(10).conference(awaitingA(2, List.of(province(B, A, "b1")))).build();
		assertThat(decide(early, RULES_V8).approve()).isFalse();
		AiState both = decisiveVictory(10).conference(awaitingA(2, List.of(province(B, A, "b1"), province(B, A, "b2"))))
			.build();
		assertThat(decide(both, RULES_V8).approve()).isTrue();

		// Four rounds later only 30% is required: one province is enough.
		AiState later = decisiveVictory(14).conference(awaitingA(2, List.of(province(B, A, "b2")))).build();
		assertThat(decide(later, RULES_V8).approve()).isTrue();
	}

	@Test
	void anEndlessWarEventuallyEndsEvenWithAWhitePeace() {
		AiState stalemate = decisiveVictory(17).conference(awaitingA(1, List.of())).build();
		assertThat(decide(stalemate, RULES_V8).approve()).isTrue();
	}

	@Test
	void gainsAtRiskAreTradedForLess() {
		// A holds b2 but B's army is stronger than A's: half the usual share is enough.
		Board board = new Board().me("aurelia")
			.round(10, 30)
			.war(WAR, A, B, Map.of(A, "ATTACKER", B, "DEFENDER"), 2, 20)
			.owner("b2", B, A)
			.unit(A, "INFANTRY", 1000, "b2")
			.unit(B, "INFANTRY", 1000, "b1")
			.unit(B, "INFANTRY", 200, "b1");
		AiState state = board.conference(awaitingA(1, List.of())).build();
		AiState.War war = state.diplomacy().wars().getFirst();
		Statecraft.Standing s = Statecraft.standing(state, RULES_V8, war);
		Statecraft.Outlook o = Statecraft.outlook(state, RULES_V8, war, s);
		assertThat(s.losing()).isFalse();
		assertThat(o.gainsAtRisk()).isTrue();
		assertThat(o.heldValueMg()).isEqualTo(50_000);
		assertThat(Statecraft.requiredShareBps(state, RULES_V8, s, o)).isEqualTo(3500);
	}

	@Test
	void aLosingNationStillAcceptsAWhitePeaceOrReasonableConcessions() {
		// B attacks A and holds two of A's provinces: A is losing.
		Board board = new Board().me("frisia")
			.round(10, 30)
			.war(WAR, B, A, Map.of(B, "ATTACKER", A, "DEFENDER"), 2, 25)
			.owner("a2", A, B)
			.owner("a3", A, B)
			.unit(B, "INFANTRY", 1000, "a2")
			.unit(B, "INFANTRY", 1000, "a3")
			.unit(A, "INFANTRY", 500, "a1");
		AiState white = board.conference(awaitingA(1, List.of())).build();
		assertThat(decide(white, RULES_V8).approve()).isTrue();
		AiState ceding = new Board().me("frisia")
			.round(10, 30)
			.war(WAR, B, A, Map.of(B, "ATTACKER", A, "DEFENDER"), 2, 25)
			.owner("a2", A, B)
			.owner("a3", A, B)
			.unit(B, "INFANTRY", 1000, "a2")
			.unit(B, "INFANTRY", 1000, "a3")
			.unit(A, "INFANTRY", 500, "a1")
			.conference(awaitingA(2, List.of(province(A, B, "a2"))))
			.build();
		assertThat(decide(ceding, RULES_V8).approve()).isTrue();
		assertThat(decide(ceding, RULES_V8).approve()).isEqualTo(decide(ceding, RULES).approve());
	}

	@Test
	void aBalancedWarWithoutGainsEndsInAWhitePeace() {
		AiState state = new Board().me("aurelia")
			.round(12, 30)
			.war(WAR, A, B, Map.of(A, "ATTACKER", B, "DEFENDER"), 2, 0)
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(B, "INFANTRY", 1000, "b1")
			.conference(awaitingA(1, List.of()))
			.build();
		Statecraft.Decision decision = decide(state, RULES_V8);
		assertThat(decision.approve()).isTrue();
		assertThat(decision.why()).contains("stalemate");
	}

	@Test
	void aFavouredNationThatCanStillTakeLandRefusesAnEmptyPeaceUntilTheWarStalls() {
		Board young = new Board().me("aurelia")
			.round(6, 30)
			.war(WAR, A, B, Map.of(A, "ATTACKER", B, "DEFENDER"), 2, 5)
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(B, "INFANTRY", 600, "b1");
		Statecraft.Decision refused = decide(young.conference(awaitingA(1, List.of())).build(), RULES_V8);
		assertThat(refused.approve()).isFalse();
		assertThat(refused.why()).contains("we can still take their land");

		Board sieging = new Board().me("aurelia")
			.round(6, 30)
			.war(WAR, A, B, Map.of(A, "ATTACKER", B, "DEFENDER"), 2, 5)
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "INFANTRY", 1000, "b1")
			.unit(B, "INFANTRY", 600, "b1")
			.siege("b1", A, B);
		assertThat(decide(sieging.conference(awaitingA(1, List.of())).build(), RULES_V8).why())
			.contains("our siege is about to succeed");

		Board stalled = new Board().me("aurelia")
			.round(16, 30)
			.war(WAR, A, B, Map.of(A, "ATTACKER", B, "DEFENDER"), 2, 5)
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(B, "INFANTRY", 600, "b1");
		assertThat(decide(stalled.conference(awaitingA(1, List.of())).build(), RULES_V8).approve()).isTrue();
	}

	@Test
	void aFavouredNationWithoutGainsDoesNotAskForPeaceWhileItCanStillAdvance() {
		// Twelve rounds of war (an expansionist's patience), nothing held, land within reach.
		AiState state = new Board().me("aurelia")
			.round(14, 30)
			.war(WAR, A, B, Map.of(A, "ATTACKER", B, "DEFENDER"), 2, 5)
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(B, "INFANTRY", 600, "b1")
			.build();
		assertThat(Statecraft.peace(state, RULES, none())).hasSize(1);
		assertThat(Statecraft.peace(state, RULES_V8, none())).isEmpty();
	}

	@Test
	void aHolderAsksToTurnItsGainsIntoATreaty() {
		AiState state = decisiveVictory(8).build();
		assertThat(Statecraft.peace(state, RULES_V8, none())).singleElement()
			.isInstanceOf(AiAction.RequestConference.class);
	}

	@Test
	void itsOwnDraftIsApprovedAsSubmitted() {
		Map<UUID, String> approvals = new HashMap<>();
		approvals.put(A, null);
		approvals.put(B, null);
		AiState.Conference own = new AiState.Conference(CONF, WAR, "GENERAL", B, A, "ACTIVE", 2, List.of(province(B, A, "b2")),
				approvals, List.of(), false, true, true, true, 1, A, 2);
		assertThat(decide(decisiveVictory(10).conference(own).build(), RULES_V8).approve()).isTrue();
	}

	// --- calls to arms --------------------------------------------------------------------------------

	private static AiState.Call invitation(boolean offensive) {
		return new AiState.Call(new UUID(7, 1), WAR, B, A, "PENDING", true, offensive ? "ATTACKER" : "DEFENDER");
	}

	/** B attacks C; A is invited by B to join the attack. */
	private static Board invitedToAttack() {
		return new Board().me("aurelia")
			.war(WAR, B, C, Map.of(B, "ATTACKER", C, "DEFENDER"), 4, 0)
			.unit(A, "INFANTRY", 1000, "a1")
			.unit(A, "INFANTRY", 1000, "a1")
			.unit(B, "INFANTRY", 1000, "b1")
			.unit(C, "INFANTRY", 1000, "c1")
			.call(invitation(true));
	}

	private static List<AiAction.AnswerCall> answers(AiState state, AiRules rules) {
		return Statecraft.calls(state, rules, Strategy.assess(state, rules), none())
			.stream()
			.filter(AiAction.AnswerCall.class::isInstance)
			.map(AiAction.AnswerCall.class::cast)
			.toList();
	}

	@Test
	void anInvitationToAttackIsJudgedFromTheAttackersSide() {
		AiState strong = invitedToAttack().v8().build();
		assertThat(answers(strong, RULES_V8)).singleElement().satisfies(a -> {
			assertThat(a.honour()).isTrue();
			assertThat(a.reason()).contains("invitation to attack");
		});

		AiState weak = new Board().me("aurelia")
			.war(WAR, B, C, Map.of(B, "ATTACKER", C, "DEFENDER"), 4, 0)
			.unit(A, "INFANTRY", 200, "a1")
			.unit(B, "INFANTRY", 200, "b1")
			.unit(C, "INFANTRY", 1000, "c1")
			.unit(C, "INFANTRY", 1000, "c1")
			.call(invitation(true))
			.v8()
			.build();
		assertThat(answers(weak, RULES_V8)).singleElement().satisfies(a -> assertThat(a.honour()).isFalse());
	}

	@Test
	void aTruceNoLongerStopsTheComputerFromAnsweringACallInV8() {
		AiState v8 = invitedToAttack().truce(C, 20).v8().build();
		assertThat(answers(v8, RULES_V8)).singleElement().satisfies(a -> assertThat(a.honour()).isTrue());
		AiState v7 = invitedToAttack().truce(C, 20).build();
		assertThat(answers(v7, RULES)).singleElement().satisfies(a -> assertThat(a.honour()).isFalse());
	}

}
