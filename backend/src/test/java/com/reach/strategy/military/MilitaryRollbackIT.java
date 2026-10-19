package com.reach.strategy.military;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.military.domain.BattleReportRepository;
import com.reach.strategy.military.domain.ManpowerTransferRepository;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import com.reach.strategy.world.application.WorldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A failure late in a recruitment or a battle leaves no trace: the real work runs first (gold
 * spent, losses applied), then an injected exception forces the rollback.
 */
@PostgresIntegrationTest
// Phase 5/6 rules: ruleset v2 has no fortresses, so every province is occupied the simple way.
@TestPropertySource(properties = "strategy.ruleset.current-version=v2")
class MilitaryRollbackIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoSpyBean
	WorldService world;

	@MockitoSpyBean
	BattleReportRepository battleReports;

	@MockitoSpyBean
	ManpowerTransferRepository transfers;

	ApiDriver api;

	MilitaryScenario scenario;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		scenario = new MilitaryScenario(api, jdbc);
	}

	@AfterEach
	void restoreSpies() {
		reset(spyBehind(world), spyBehind(battleReports), spyBehind(transfers));
	}

	@Test
	void aFailedRecruitmentSpendsNothing() throws Exception {
		Lobby game = scenario.startedGame();
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		long version = scenario.stateVersion(game);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("simulated failure after the civilians were taken");
		}).when(spyBehind(world)).changePopulation(any(), any(), anyInt());
		UUID key = UUID.randomUUID();

		assertThat(status(api.recruit(game.host(), game.gameId(), varnhold, "INFANTRY", key))).isEqualTo(500);

		assertThat(scenario.treasury(game, "varn")).isEqualTo(50_000);
		assertThat(scenario.population(game, "varnhold")).isEqualTo(8_000);
		assertThat(scenario.count("game.battalion", game)).isEqualTo(8);
		assertThat(scenario.commands(game, "RECRUIT")).isZero();
		assertThat(scenario.stateVersion(game)).isEqualTo(version);

		reset(spyBehind(world));
		MvcResult retry = api.recruit(game.host(), game.gameId(), varnhold, "INFANTRY", key);
		assertThat(status(retry)).as("the key was never consumed").isEqualTo(201);
		assertThat(scenario.treasury(game, "varn")).isEqualTo(40_000);
	}

	@Test
	void aFailedBattleRollsBackLossesMovementAndRetreat() throws Exception {
		Lobby game = scenario.startedGame();
		String defender = scenario.place(game, "ostra", "elmsford", "INFANTRY", 1_000);
		List<String> attackers = scenario.battalions(game, "varn", "varnhold");
		long soldiers = scenario.totalManpower(game);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("simulated failure after the battle was recorded");
		}).when(spyBehind(battleReports)).insert(any(), any());
		UUID key = UUID.randomUUID();

		assertThat(status(api.move(game.host(), game.gameId(), attackers, "elmsford", key))).isEqualTo(500);

		assertThat(scenario.totalManpower(game)).isEqualTo(soldiers);
		assertThat(scenario.provinceOf(defender)).isEqualTo("elmsford");
		assertThat(attackers).allSatisfy(id -> {
			assertThat(scenario.provinceOf(id)).isEqualTo("varnhold");
			assertThat(scenario.battalion(id).get("last_moved_turn_number")).isNull();
		});
		assertThat(scenario.count("game.battle_report", game)).isZero();
		assertThat(scenario.commands(game, "MOVE")).isZero();

		reset(spyBehind(battleReports));
		MvcResult retry = api.move(game.host(), game.gameId(), attackers, "elmsford", key);
		assertThat(status(retry)).isEqualTo(200);
		assertThat((String) read(retry, "$.outcome")).isEqualTo("ATTACK_WON");
		assertThat(scenario.count("game.battle_report", game)).isEqualTo(1);
	}

	@Test
	void aFailedReinforcementOrDisbandLeavesPeopleGoldAndCohortsUntouched() throws Exception {
		Lobby game = scenario.startedGame();
		String battalion = scenario.battalions(game, "varn", "varnhold").getFirst();
		scenario.damage(battalion, 600);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("simulated failure after the transfer was recorded");
		}).when(spyBehind(transfers)).insert(any(), any());

		assertThat(status(api.reinforce(game.host(), game.gameId(), battalion, 400, UUID.randomUUID()))).isEqualTo(500);
		assertThat(status(api.disband(game.host(), game.gameId(), battalion, UUID.randomUUID()))).isEqualTo(500);

		assertThat(scenario.manpower(battalion)).isEqualTo(600);
		assertThat(scenario.cohorts(battalion)).containsExactly(java.util.Map.entry("varnhold", 600));
		assertThat(scenario.population(game, "varnhold")).isEqualTo(8_000);
		assertThat(scenario.treasury(game, "varn")).isEqualTo(50_000);
		assertThat(scenario.commands(game, "REINFORCE") + scenario.commands(game, "DISBAND")).isZero();
		assertThat(scenario.count("game.manpower_transfer", game)).isZero();

		reset(spyBehind(transfers));
		assertThat(status(api.reinforce(game.host(), game.gameId(), battalion, 400, UUID.randomUUID()))).isEqualTo(201);
		assertThat(scenario.manpower(battalion)).isEqualTo(1_000);
	}

	/** The beans are proxies; stubbing must address the Mockito spy behind them. */
	private static <T> T spyBehind(T proxy) {
		return AopTestUtils.getUltimateTargetObject(proxy);
	}

}
