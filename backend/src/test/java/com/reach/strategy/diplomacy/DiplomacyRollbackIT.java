package com.reach.strategy.diplomacy;

import static com.reach.strategy.diplomacy.DiplomacyScenario.term;
import static com.reach.strategy.diplomacy.DiplomacyScenario.terms;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.diplomacy.domain.RelationsRepository;
import com.reach.strategy.diplomacy.domain.TreatyRepository;
import com.reach.strategy.game.domain.GameResultRepository;
import com.reach.strategy.military.MilitaryScenario;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A failure at the very end of a peace settlement, a tribute charge or the match completion rolls
 * back everything that step had already done: no gold, ownership, population, war, treaty, tribute
 * or result change survives, and the retried request succeeds.
 */
@PostgresIntegrationTest
class DiplomacyRollbackIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoSpyBean
	RelationsRepository relations;

	@MockitoSpyBean
	TreatyRepository treaties;

	@MockitoSpyBean
	GameResultRepository results;

	ApiDriver api;

	MilitaryScenario world;

	DiplomacyScenario d;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		d = new DiplomacyScenario(api, jdbc);
		world = d.world;
	}

	@AfterEach
	void restore() {
		reset(spyBehind(relations), spyBehind(treaties), spyBehind(results));
	}

	@Test
	void aSettlementThatFailsAfterApplyingItsTermsLeavesNothingBehind() throws Exception {
		Lobby game = world.startedGame();
		String warId = d.declareWar(game, game.host(), d.ostra(game));
		world.place(game, "varn", "marrowfield", "INFANTRY", 1000);
		String saltreach = api.cityId(game.host(), game.gameId(), "saltreach");
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		String proposal = d.proposePeace(game, game.host(), warId,
				terms(term("PROVINCE", d.ostra(game), d.varn(game), "\"provinceKey\":\"marrowfield\""),
						term("GOLD", d.ostra(game), d.varn(game), "\"amountMg\":20000"),
						term("TRIBUTE", d.ostra(game), d.varn(game), ""),
						term("POPULATION", d.ostra(game), d.varn(game), "\"sourceCityId\":\"" + saltreach
								+ "\",\"destinationCityId\":\"" + varnhold + "\",\"people\":1000")));
		world.endTurn(game, game.host(), 1);
		long varnGold = world.treasury(game, "varn");
		long ostraGold = world.treasury(game, "ostra");
		int salt = world.population(game, "saltreach");
		String marrowBattalion = world.battalions(game, "varn", "marrowfield").getFirst();
		// Fails after the war was ended — i.e. after every term had been applied.
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("simulated failure at the end of the settlement");
		}).when(spyBehind(relations)).endWar(any(), any(), anyInt(), anyInt(), anyString());
		UUID key = UUID.randomUUID();

		assertThat(status(api.peace(game.guest(), game.gameId(), proposal, "accept", key))).isEqualTo(500);

		assertThat(world.ownerAndController(game, "marrowfield")).containsExactly("ostra", "ostra");
		assertThat(world.treasury(game, "varn")).isEqualTo(varnGold);
		assertThat(world.treasury(game, "ostra")).isEqualTo(ostraGold);
		assertThat(world.population(game, "saltreach")).isEqualTo(salt);
		assertThat(world.provinceOf(marrowBattalion)).isEqualTo("marrowfield");
		assertThat(d.proposalStatus(proposal)).isEqualTo("PENDING");
		assertThat(d.wars(game, "ACTIVE")).isEqualTo(1);
		assertThat(world.count("game.peace_treaty", game)).isZero();
		assertThat(world.count("game.tribute", game)).isZero();
		assertThat(world.count("game.truce", game)).isZero();
		assertThat(d.events(game, "PROVINCE_CEDED") + d.events(game, "REPARATIONS_PAID")).isZero();
		assertThat(world.commands(game, "ACCEPT_PEACE")).isZero();

		reset(spyBehind(relations));
		assertThat(status(api.peace(game.guest(), game.gameId(), proposal, "accept", key))).isEqualTo(200);
		assertThat(world.ownerAndController(game, "marrowfield")).containsExactly("varn", "varn");
		assertThat(world.treasury(game, "ostra")).isEqualTo(ostraGold - 20_000);
	}

	@Test
	void aFailedTributeChargeRollsBackTheWholeRoundEnd() throws Exception {
		Lobby game = world.startedGame();
		String warId = d.declareWar(game, game.host(), d.ostra(game));
		String proposal = d.proposePeace(game, game.host(), warId, terms(term("TRIBUTE", d.ostra(game), d.varn(game), "")));
		world.endTurn(game, game.host(), 1);
		assertThat(status(d.acceptPeace(game, game.guest(), proposal))).isEqualTo(200);
		long varnGold = world.treasury(game, "varn");
		long ostraGold = world.treasury(game, "ostra");
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("simulated failure after the tribute was paid");
		}).when(spyBehind(treaties)).recordPayment(any(), any(), anyBoolean(), any());
		UUID key = UUID.randomUUID();

		assertThat(status(api.endTurn(game.guest(), game.gameId(), 2, key))).isEqualTo(500);

		assertThat(world.treasury(game, "varn")).isEqualTo(varnGold);
		assertThat(world.treasury(game, "ostra")).isEqualTo(ostraGold);
		assertThat(world.count("game.tribute_payment", game)).isZero();
		assertThat(world.count("game.economy_round_report", game)).isZero();
		assertThat(d.tribute(game)).containsEntry("rounds_charged", 0);

		reset(spyBehind(treaties));
		assertThat(status(api.endTurn(game.guest(), game.gameId(), 2, key))).isEqualTo(200);
		assertThat(d.tributePayments(game)).hasSize(1);
	}

	@Test
	void aFailedResultLeavesTheGameRunningInItsLastRound() throws Exception {
		Lobby game = world.startedGame();
		d.jumpToRound(game, 30);
		world.endTurn(game, game.host(), 59);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("simulated failure while storing the result");
		}).when(spyBehind(results)).insert(any(), anyList());
		UUID key = UUID.randomUUID();

		assertThat(status(api.endTurn(game.guest(), game.gameId(), 60, key))).isEqualTo(500);

		assertThat(d.gameStatus(game)).isEqualTo("IN_PROGRESS");
		assertThat(world.count("game.game_result", game)).isZero();
		assertThat(world.count("game.round_resolution", game)).isZero();

		reset(spyBehind(results));
		assertThat(status(api.endTurn(game.guest(), game.gameId(), 60, key))).isEqualTo(200);
		assertThat(d.gameStatus(game)).isEqualTo("FINISHED");
	}

	private static <T> T spyBehind(T proxy) {
		return AopTestUtils.getUltimateTargetObject(proxy);
	}

}
