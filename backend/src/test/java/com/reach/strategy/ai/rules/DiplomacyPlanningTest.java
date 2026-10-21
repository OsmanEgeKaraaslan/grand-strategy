package com.reach.strategy.ai.rules;

import static com.reach.strategy.ai.rules.Board.A;
import static com.reach.strategy.ai.rules.Board.B;
import static com.reach.strategy.ai.rules.Board.C;
import static com.reach.strategy.ai.rules.Board.RULES;
import static com.reach.strategy.ai.rules.Board.none;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Phase 11C diplomacy planners: war declarations, alliances, calls to arms, peace requests, separate
 * peace, terms and the evaluation of drafts — including that a computer never offers an ally's assets,
 * never agrees to its own elimination while it holds land, and moderates after a rejection.
 */
class DiplomacyPlanningTest {

	private static final UUID WAR = new UUID(9, 1);
	private static final UUID CONF = new UUID(9, 2);

	private static <T extends AiAction> List<T> of(List<AiAction> actions, Class<T> type) {
		return actions.stream().filter(type::isInstance).map(type::cast).toList();
	}

	private static List<AiAction> plan(AiState state) {
		return AiBrain.planTurn(state, RULES, none()).actions();
	}

	private static AiState.Conference conference(UUID requester, UUID counterpart, String kind, int revision,
			List<AiState.Term> terms, Map<UUID, String> approvals, boolean mustDecide, UUID lastAuthor, int byMe) {
		return new AiState.Conference(CONF, WAR, kind, requester, counterpart, "ACTIVE", revision, terms, approvals,
				List.of(), false, requester.equals(A) || counterpart.equals(A), mustDecide, true, byMe, lastAuthor, 5);
	}

	private static AiState.Term province(UUID from, UUID to, String key) {
		return new AiState.Term("PROVINCE", from, to, key, null, null, null, null);
	}

	// --- war ----------------------------------------------------------------------------------------

	@Test
	void aMuchStrongerNationDeclaresWarOnceItsArmyStandsAtTheBorder() {
		Board base = new Board().me("aurelia")
			.round(5, 30)
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "CAVALRY", 1000, "a2")
			.unit(B, "INFANTRY", 500, "b2")
			.unit(C, "INFANTRY", 1000, "c2")
			.unit(C, "INFANTRY", 1000, "c2")
			.unit(C, "INFANTRY", 1000, "c2");
		List<AiAction.DeclareWar> wars = of(plan(base.build()), AiAction.DeclareWar.class);
		assertThat(wars).extracting(AiAction.DeclareWar::target).containsExactly(B);

		// The same army far from the border prepares first instead of declaring.
		AiState unstaged = new Board().me("aurelia")
			.round(5, 30)
			.unit(A, "INFANTRY", 1000, "a3")
			.unit(A, "INFANTRY", 1000, "a3")
			.unit(A, "CAVALRY", 1000, "a3")
			.unit(B, "INFANTRY", 500, "b2")
			.build();
		assertThat(of(plan(unstaged), AiAction.DeclareWar.class)).noneMatch(w -> w.target().equals(B));
	}

	@Test
	void noWarTooEarlyTooLateAgainstTheStrongOrAgainstAProtectedNation() {
		Board strongArmy = new Board().me("aurelia")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "CAVALRY", 1000, "a2")
			.unit(B, "INFANTRY", 500, "b2");
		assertThat(of(plan(strongArmy.round(1, 30).build()), AiAction.DeclareWar.class)).isEmpty();
		assertThat(of(plan(new Board().me("aurelia")
			.round(27, 30)
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "CAVALRY", 1000, "a2")
			.unit(B, "INFANTRY", 500, "b2")
			.build()), AiAction.DeclareWar.class)).isEmpty();
		// B is as strong as A.
		assertThat(of(plan(new Board().me("aurelia")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(B, "INFANTRY", 1000, "b2")
			.build()), AiAction.DeclareWar.class)).isEmpty();
		// B's ally C would probably help: the odds are no longer good enough.
		AiState protectedB = new Board().me("aurelia")
			.alliance(new AiState.Alliance(new UUID(7, 7), "DEFENSIVE", B, C, B, "ACTIVE", false, false, 1))
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(B, "INFANTRY", 600, "b2")
			.unit(C, "INFANTRY", 1000, "c2")
			.unit(C, "INFANTRY", 1000, "c2")
			.build();
		assertThat(Statecraft.warTarget(protectedB, RULES, Strategy.assess(protectedB, RULES)))
			.map(Statecraft.Target::nation)
			.isNotEqualTo(java.util.Optional.of(B));
	}

	// --- alliances and calls ------------------------------------------------------------------------

	@Test
	void anAllianceAgainstASharedThreatIsAcceptedAndOneWithTheWarTargetRejected() {
		// C proposes; B is the strong neighbour both A and C border? Only A borders B: make C share the threat by
		// giving B the province c2.
		AiState shared = new Board().me("karpathia")
			.owner("c2", B, B)
			.alliance(new AiState.Alliance(new UUID(7, 1), "DEFENSIVE", A, C, C, "PROPOSED", true, false, 4))
			.unit(A, "INFANTRY", 1000, "a1")
			.unit(B, "INFANTRY", 1000, "b1")
			.unit(B, "INFANTRY", 1000, "b2")
			.unit(C, "INFANTRY", 1000, "c1")
			.build();
		assertThat(of(plan(shared), AiAction.AnswerAlliance.class)).singleElement()
			.satisfies(a -> assertThat(a.accept()).isTrue());

		AiState target = new Board().me("aurelia")
			.alliance(new AiState.Alliance(new UUID(7, 2), "DEFENSIVE", A, B, B, "PROPOSED", true, false, 4))
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "CAVALRY", 1000, "a2")
			.unit(B, "INFANTRY", 300, "b2")
			.build();
		assertThat(of(plan(target), AiAction.AnswerAlliance.class)).singleElement()
			.satisfies(a -> assertThat(a.accept()).isFalse());
	}

	@Test
	void aCallToArmsIsHonouredWhenTheCoalitionCanWinAndDeclinedOtherwise() {
		UUID call = new UUID(8, 1);
		// C attacked B; B (A's ally) calls A.
		Board base = new Board().me("aestia")
			.war(WAR, C, B, Map.of(C, "ATTACKER", B, "DEFENDER"), 4, 0)
			.call(new AiState.Call(call, WAR, B, A, "PENDING", true));
		AiState strong = base.unit(A, "INFANTRY", 1000, "a1").unit(B, "INFANTRY", 1000, "b1").unit(C, "INFANTRY", 1000, "c1").build();
		assertThat(of(plan(strong), AiAction.AnswerCall.class)).singleElement().satisfies(a -> assertThat(a.honour()).isTrue());

		AiState hopeless = new Board().me("aestia")
			.war(WAR, C, B, Map.of(C, "ATTACKER", B, "DEFENDER"), 4, 0)
			.call(new AiState.Call(call, WAR, B, A, "PENDING", true))
			.unit(A, "INFANTRY", 300, "a1")
			.unit(C, "INFANTRY", 1000, "c1")
			.unit(C, "INFANTRY", 1000, "c1")
			.unit(C, "CAVALRY", 1000, "c2")
			.build();
		assertThat(of(plan(hopeless), AiAction.AnswerCall.class)).singleElement().satisfies(a -> assertThat(a.honour()).isFalse());
	}

	// --- peace --------------------------------------------------------------------------------------

	@Test
	void aLosingLeaderAsksForPeaceAndALosingAllyForASeparatePeace() {
		AiState losingLeader = new Board().me("frisia")
			.war(WAR, B, A, Map.of(B, "ATTACKER", A, "DEFENDER"), 3, 30)
			.owner("a2", A, B)
			.owner("a3", A, B)
			.unit(B, "INFANTRY", 1000, "a2")
			.build();
		assertThat(of(plan(losingLeader), AiAction.RequestConference.class)).singleElement()
			.satisfies(r -> assertThat(r.kind()).isEqualTo("GENERAL"));

		AiState losingAlly = new Board().me("frisia")
			.war(WAR, B, C, Map.of(B, "ATTACKER", C, "DEFENDER", A, "DEFENDER"), 3, 30)
			.owner("a2", A, B)
			.owner("a3", A, B)
			.unit(B, "INFANTRY", 1000, "a2")
			.build();
		assertThat(of(plan(losingAlly), AiAction.RequestConference.class)).singleElement()
			.satisfies(r -> assertThat(r.kind()).isEqualTo("SEPARATE"));
	}

	@Test
	void theWinnerDemandsWhatItsSideHoldsAndNeverAnAllysAssets() {
		// A and C attack B; A occupies b1, C occupies b2. A's demand: b1 and b2 from B, to the occupiers.
		Board board = new Board().me("aurelia")
			.war(WAR, A, B, Map.of(A, "ATTACKER", C, "ATTACKER", B, "DEFENDER"), 2, 40)
			.owner("b1", B, A)
			.owner("b2", B, C)
			.unit(A, "INFANTRY", 1000, "b1");
		Map<UUID, String> approvals = new HashMap<>();
		approvals.put(A, null);
		approvals.put(B, "APPROVED");
		AiState state = board.conference(conference(B, A, "GENERAL", 1, List.of(), approvals, true, B, 0)).build();
		List<AiAction.SubmitDraft> drafts = of(AiBrain.planNegotiation(state, RULES, none()), AiAction.SubmitDraft.class);
		assertThat(drafts).singleElement().satisfies(d -> {
			assertThat(d.terms()).allSatisfy(t -> assertThat(t.from()).isEqualTo(B));
			assertThat(d.terms()).extracting(AiState.Term::provinceKey).contains("b2");
			assertThat(d.terms()).filteredOn(t -> "b2".equals(t.provinceKey())).allSatisfy(t -> assertThat(t.to()).isEqualTo(C));
			assertThat(d.expectedRevision()).isEqualTo(1);
		});
	}

	@Test
	void aLoserPaysWhatItHasLostButNotMoreAndNeverItsOwnElimination() {
		Board board = new Board().me("frisia")
			.war(WAR, B, A, Map.of(B, "ATTACKER", A, "DEFENDER"), 3, 30)
			.owner("a2", A, B)
			.unit(B, "INFANTRY", 1000, "a2")
			.unit(B, "INFANTRY", 1000, "b1");
		Map<UUID, String> pending = new HashMap<>();
		pending.put(A, null);
		pending.put(B, "APPROVED");
		AiState fair = board.conference(conference(B, A, "GENERAL", 2, List.of(province(A, B, "a2")), pending, true, B, 0)).build();
		assertThat(of(AiBrain.planNegotiation(fair, RULES, none()), AiAction.DecideDraft.class)).singleElement()
			.satisfies(d -> {
				assertThat(d.approve()).isTrue();
				assertThat(d.revision()).isEqualTo(2);
			});

		AiState greedy = new Board().me("frisia")
			.war(WAR, B, A, Map.of(B, "ATTACKER", A, "DEFENDER"), 3, 30)
			.owner("a2", A, B)
			.unit(B, "INFANTRY", 1000, "a2")
			.conference(conference(B, A, "GENERAL", 3,
					List.of(province(A, B, "a1"), province(A, B, "a2"), province(A, B, "a3")), pending, true, B, 0))
			.build();
		AiState.War war = greedy.diplomacy().wars().getFirst();
		Statecraft.Decision decision = Statecraft.evaluate(greedy, RULES, greedy.diplomacy().conferences().getFirst(), war);
		assertThat(decision.approve()).isFalse();
		assertThat(decision.why()).contains("no province");
	}

	@Test
	void aRejectedDraftIsModeratedAndEventuallyTheConferenceEnds() {
		List<AiState.Term> full = List.of(province(B, A, "b1"), province(B, A, "b2"));
		assertThat(Statecraft.moderate(full, 1)).hasSize(1);
		assertThat(Statecraft.moderate(full, 5)).isEmpty();

		Board board = new Board().me("aurelia")
			.war(WAR, A, B, Map.of(A, "ATTACKER", B, "DEFENDER"), 2, 40)
			.owner("b1", B, A)
			.owner("b2", B, A)
			.unit(A, "INFANTRY", 1000, "b1");
		Map<UUID, String> rejected = new HashMap<>();
		rejected.put(A, "APPROVED");
		rejected.put(B, "REJECTED");
		int max = RULES.budget().maxRevisionsPerConference();
		AiState exhausted = board.conference(conference(B, A, "GENERAL", 2 + max, List.of(), rejected, false, A, max)).build();
		assertThat(of(AiBrain.planNegotiation(exhausted, RULES, none()), AiAction.CancelConference.class)).hasSize(1);
	}

	@Test
	void aNationFarAheadRefusesToTalkWhileTheWarIsYoung() {
		AiState state = new Board().me("aurelia")
			.war(WAR, A, B, Map.of(A, "ATTACKER", B, "DEFENDER"), 4, 0)
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(B, "INFANTRY", 300, "b1")
			.conference(new AiState.Conference(CONF, WAR, "GENERAL", B, A, "REQUESTED", 0, List.of(), Map.of(), List.of(),
					true, false, false, false, 0, null, 5))
			.build();
		assertThat(of(AiBrain.planNegotiation(state, RULES, none()), AiAction.AnswerConference.class)).singleElement()
			.satisfies(a -> assertThat(a.accept()).isFalse());
	}

	@Test
	void giftsGoOnlyToAWeakerPartnerInACommonWar() {
		AiState state = new Board().me("dravonia")
			.economy(400_000, 40_000, 5_000)
			.war(WAR, B, C, Map.of(B, "ATTACKER", C, "DEFENDER", A, "DEFENDER"), 3, 0)
			.unit(A, "INFANTRY", 1000, "a1")
			.unit(A, "INFANTRY", 1000, "a1")
			.unit(C, "INFANTRY", 300, "c1")
			.build();
		List<AiAction.SendGold> gifts = of(plan(state), AiAction.SendGold.class);
		assertThat(gifts).singleElement().satisfies(g -> {
			assertThat(g.recipient()).isEqualTo(C);
			assertThat(g.amountMg()).isPositive().isLessThanOrEqualTo(500_000);
		});
		AiState peace = new Board().me("dravonia").economy(400_000, 40_000, 5_000).build();
		assertThat(of(plan(peace), AiAction.SendGold.class)).isEmpty();
	}

}
