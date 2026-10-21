package com.reach.strategy.ai.rules;

import static com.reach.strategy.ai.rules.Board.A;
import static com.reach.strategy.ai.rules.Board.B;
import static com.reach.strategy.ai.rules.Board.C;
import static com.reach.strategy.ai.rules.Board.RULES;
import static com.reach.strategy.ai.rules.Board.none;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Phase 11B planners on a seven-province board: strategic goals, economic investments within the
 * budget, research and doctrines, recruitment, pathfinding with real access, attacks judged by
 * estimated odds, captures, sieges and defence of the capital.
 */
class EconomyAndMilitaryPlanningTest {

	private static List<AiAction> plan(AiState state) {
		return AiBrain.planTurn(state, RULES, none()).actions();
	}

	private static <T extends AiAction> List<T> of(List<AiAction> actions, Class<T> type) {
		return actions.stream().filter(type::isInstance).map(type::cast).toList();
	}

	private static final UUID WAR = new UUID(9, 1);

	/** A at war with B (A attacked, net score 0). */
	private static Board atWarWithB() {
		return new Board().war(WAR, A, B, Map.of(A, "ATTACKER", B, "DEFENDER"), 3, 0);
	}

	// --- goals --------------------------------------------------------------------------------------

	@Test
	void goalsFollowTheSituation() {
		assertThat(Strategy.assess(new Board().me("serenna").arrears().economy(1000, 20_000, 30_000).build(), RULES).goal())
			.isEqualTo(Strategy.RECOVER);
		assertThat(Strategy.assess(new Board().me("serenna").unit(A, "INFANTRY", 1000, "a1").build(), RULES).goal())
			.isEqualTo(Strategy.DEVELOP);
		// A stronger neighbour makes even an economic nation build up.
		AiState threatened = new Board().me("serenna")
			.unit(A, "INFANTRY", 1000, "a1")
			.unit(B, "INFANTRY", 1000, "b1")
			.unit(B, "INFANTRY", 1000, "b1")
			.build();
		assertThat(Strategy.assess(threatened, RULES).goal()).isEqualTo(Strategy.BUILDUP);
		// At war with a stronger enemy next door: defend.
		AiState invaded = atWarWithB().unit(A, "INFANTRY", 500, "a2")
			.unit(B, "INFANTRY", 1000, "b1")
			.unit(B, "CAVALRY", 1000, "b1")
			.build();
		assertThat(Strategy.assess(invaded, RULES).goal()).isEqualTo(Strategy.DEFEND);
	}

	// --- economy ------------------------------------------------------------------------------------

	@Test
	void profitableWorkshopsAreBuiltWithinTheBudgetCheapestPaybackFirst() {
		AiState state = new Board().me("serenna")
			.economy(100_000, 40_000, 5_000)
			.city("a1", 8000, 1, 40_000, true)
			.city("a2", 1500, 0, 40_000, true)
			.city("a3", 4000, 6, 40_000, true)
			.build();
		List<AiAction.BuildWorkshop> builds = of(plan(state), AiAction.BuildWorkshop.class);
		// a1 employs 4,000 farmers (+8 gold/round), a2 only 1,500; a3 has no free slot.
		assertThat(builds).extracting(AiAction.BuildWorkshop::cityId).first().isEqualTo(Board.cityId("a1"));
		assertThat(builds).extracting(AiAction.BuildWorkshop::cityId).doesNotContain(Board.cityId("a3"));
		long spent = builds.size() * 40_000L;
		assertThat(spent).isLessThanOrEqualTo(100_000 - Strategy.reserve(RULES, RULES.personalityOf("serenna")));
	}

	@Test
	void nothingIsBoughtBelowTheReserveOrTooLateToPayBack() {
		AiState poor = new Board().me("serenna").economy(20_000, 40_000, 5_000).city("a1", 8000, 1, 40_000, true).build();
		assertThat(of(plan(poor), AiAction.BuildWorkshop.class)).isEmpty();
		AiState late = new Board().me("serenna").round(28, 30).economy(200_000, 40_000, 5_000)
			.city("a1", 8000, 1, 40_000, true)
			.build();
		assertThat(of(plan(late), AiAction.BuildWorkshop.class)).isEmpty();
		// A construction the server refused this turn is not planned again.
		AiState rich = new Board().me("serenna").economy(200_000, 40_000, 5_000).city("a1", 8000, 1, 40_000, true).build();
		Set<String> failed = none();
		failed.add("BUILD_WORKSHOP:" + Board.cityId("a1"));
		assertThat(of(AiBrain.planTurn(rich, RULES, failed).actions(), AiAction.BuildWorkshop.class)).isEmpty();
	}

	@Test
	void researchPointsAreSpentAndTheDoctrineFitsTheFrontier() {
		// b2 is mountainous and borders nothing of A; the river a2~b1 is on A's frontier.
		AiState state = new Board().tech("MILITARY", 1, true)
			.tech("CIVILIAN", 1, false)
			.doctrine("MOUNTAIN_WARFARE", 0, 5000, 0, 0)
			.doctrine("RIVER_CROSSING", 5000, 0, 0, 0)
			.build();
		List<AiAction> actions = plan(state);
		assertThat(of(actions, AiAction.UnlockTechnology.class)).extracting(AiAction.UnlockTechnology::track)
			.containsExactly("MILITARY");
		assertThat(of(actions, AiAction.UnlockDoctrine.class)).extracting(AiAction.UnlockDoctrine::doctrine)
			.containsExactly("RIVER_CROSSING");
		// Facing fortresses, siege engineering wins.
		AiState forts = atWarWithB().fort("b1", 2).doctrine("SIEGE_ENGINEERING", 0, 0, 3000, 0)
			.doctrine("MOUNTAIN_WARFARE", 0, 5000, 0, 0)
			.build();
		assertThat(of(plan(forts), AiAction.UnlockDoctrine.class)).extracting(AiAction.UnlockDoctrine::doctrine)
			.containsExactly("SIEGE_ENGINEERING");
	}

	// --- recruitment --------------------------------------------------------------------------------

	@Test
	void recruitmentRespectsUpkeepRoomTreasuryAndWantsArtilleryAgainstFortresses() {
		AiState noRoom = new Board().economy(200_000, 10_000, 9_000)
			.recruitIn("a1")
			.unit(A, "INFANTRY", 1000, "a1")
			.unit(A, "INFANTRY", 1000, "a1")
			.unit(A, "INFANTRY", 1000, "a1")
			.build();
		assertThat(of(plan(noRoom), AiAction.Recruit.class)).isEmpty();

		AiState fortifiedEnemy = atWarWithB().economy(200_000, 60_000, 5_000)
			.fort("b1", 2)
			.recruitIn("a2")
			.recruitIn("a3")
			.unit(A, "INFANTRY", 1000, "a1")
			.unit(A, "INFANTRY", 1000, "a1")
			.unit(A, "INFANTRY", 1000, "a1")
			.unit(A, "INFANTRY", 1000, "a1")
			.build();
		List<AiAction.Recruit> recruits = of(plan(fortifiedEnemy), AiAction.Recruit.class);
		assertThat(recruits).isNotEmpty();
		assertThat(recruits).extracting(AiAction.Recruit::unitType).contains("ARTILLERY");
		// Raised in the city nearest the front (a2 borders b1).
		assertThat(recruits).extracting(AiAction.Recruit::cityId).first().isEqualTo(Board.cityId("a2"));
	}

	// --- pathfinding and access ---------------------------------------------------------------------

	@Test
	void pathsObeyMilitaryAccess() {
		AiState peace = new Board().build();
		// At peace B's and C's land is closed: no route from a1 to b2.
		assertThat(Paths.route(peace, "a1", List.of("b2"), peace::mayEnter)).isEmpty();
		AiState war = atWarWithB().build();
		Paths.Route route = Paths.route(war, "a1", List.of("b2"), war::mayEnter).orElseThrow();
		assertThat(route.next()).isEqualTo("a2");
		assertThat(Paths.steps(war, "a1", war::mayEnter)).containsEntry("b2", 3).doesNotContainKey("c1");
		// The river crossing and the mountains make the path costlier, never longer in moves.
		assertThat(route.cost()).isEqualTo(3 * Paths.STEP + 2);
	}

	// --- attacks, captures, defence ------------------------------------------------------------------

	@Test
	void anArmyAttacksAWeakerEnemyButNotAMuchStrongerOne() {
		AiState favourable = atWarWithB().unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "CAVALRY", 1000, "a2")
			.unit(B, "INFANTRY", 600, "b1")
			.build();
		List<AiAction.Move> attacks = of(plan(favourable), AiAction.Move.class).stream().filter(AiAction.Move::attack).toList();
		assertThat(attacks).singleElement().satisfies(m -> {
			assertThat(m.to()).isEqualTo("b1");
			assertThat(m.battalionIds()).hasSize(3);
		});

		AiState hopeless = atWarWithB().unit(A, "INFANTRY", 1000, "a2")
			.unit(B, "INFANTRY", 1000, "b1")
			.unit(B, "INFANTRY", 1000, "b1")
			.build();
		assertThat(of(plan(hopeless), AiAction.Move.class)).noneMatch(AiAction.Move::attack);
	}

	@Test
	void undefendedEnemyLandIsOccupiedAndFortressesAreBesieged() {
		AiState state = atWarWithB().fort("b1", 1).unit(A, "INFANTRY", 1000, "a2").unit(A, "ARTILLERY", 1000, "a2").build();
		List<AiAction.Move> moves = of(plan(state), AiAction.Move.class);
		assertThat(moves).singleElement().satisfies(m -> {
			assertThat(m.to()).isEqualTo("b1");
			assertThat(m.attack()).isFalse();
			assertThat(m.reason()).contains("siege");
		});
	}

	@Test
	void aSiegeIsKeptGoingAndTheCapitalKeepsAGarrison() {
		AiState besieging = atWarWithB().fort("b1", 1).owner("b1", B, B).unit(A, "INFANTRY", 1000, "b1").siege("b1", A, B).build();
		assertThat(of(plan(besieging), AiAction.Move.class)).isEmpty();

		AiState capital = atWarWithB().unit(A, "INFANTRY", 1000, "a1").unit(A, "INFANTRY", 900, "a1").build();
		List<AiAction.Move> moves = of(plan(capital), AiAction.Move.class);
		assertThat(moves).allSatisfy(m -> assertThat(m.battalionIds()).hasSize(1));
	}

	@Test
	void battalionsThatMayNotMoveAndNeutralLandAreLeftAlone() {
		AiState state = new Board().war(WAR, A, B, Map.of(A, "ATTACKER", B, "DEFENDER"), 3, 0)
			.unit(A, "INFANTRY", 1000, "a3", false)
			.unit(A, "INFANTRY", 1000, "a3")
			.build();
		for (AiAction.Move move : of(plan(state), AiAction.Move.class)) {
			assertThat(move.to()).isNotIn("c1", "c2");
			assertThat(move.battalionIds()).doesNotContain(state.units().getFirst().id());
		}
	}

	@Test
	void coalitionPartnersAreNeverAttacked() {
		AiState state = new Board().war(WAR, A, B, Map.of(A, "ATTACKER", C, "ATTACKER", B, "DEFENDER"), 3, 0)
			.unit(A, "INFANTRY", 1000, "a3")
			.unit(C, "INFANTRY", 300, "c1")
			.build();
		assertThat(of(plan(state), AiAction.Move.class)).noneMatch(m -> m.to().equals("c1"));
	}

	@Test
	void plansAreReproducible() {
		AiState state = atWarWithB().economy(150_000, 50_000, 5_000)
			.city("a1", 8000, 1, 40_000, true)
			.recruitIn("a2")
			.unit(A, "INFANTRY", 1000, "a2")
			.unit(A, "CAVALRY", 800, "a2")
			.unit(B, "INFANTRY", 500, "b1")
			.build();
		assertThat(AiBrain.planTurn(state, RULES, none())).isEqualTo(AiBrain.planTurn(state, RULES, none()));
	}

}
