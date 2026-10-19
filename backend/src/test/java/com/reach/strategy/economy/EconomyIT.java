package com.reach.strategy.economy;

import static com.reach.strategy.support.ApiDriver.newPlayer;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import com.reach.strategy.support.Concurrently;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 4 economy against PostgreSQL through the real API: starting state, construction rules and
 * races, round-end income exactly once, and occupation. The host plays the Kingdom of Varn
 * (6 provinces, 42,000 people, capital Varnhold with one Tier 1 workshop): 42 gold of farming plus
 * 8 gold extra from the 4,000 workshop workers = 50 gold per round. Ostra is symmetric. Pinned to
 * ruleset v1 (no starting battalions, no upkeep) so it isolates the Phase 4 economy; MilitaryIT covers
 * the economy with armies.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = "strategy.ruleset.current-version=v1")
class EconomyIT {

	static final long START_TREASURY = 50_000;
	static final long VARN_INCOME = 50_000;
	static final long WORKSHOP_COST = 40_000;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiDriver api;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
	}

	// --- starting state and projections -----------------------------------------------------

	@Test
	void aNewGameStartsWithTreasuriesAndACapitalWorkshop() throws Exception {
		Lobby game = startedGame();

		MvcResult economy = api.economy(game.host(), game.gameId());
		assertThat(status(economy)).isEqualTo(200);
		assertThat(longAt(economy, "$.nation.treasuryMg")).isEqualTo(START_TREASURY);
		assertThat(longAt(economy, "$.nation.projectedGrossIncomeMg")).isEqualTo(VARN_INCOME);
		assertThat(longAt(economy, "$.nation.projectedUpkeepMg")).isZero();
		assertThat(longAt(economy, "$.nation.projectedNetMg")).isEqualTo(VARN_INCOME);
		assertThat((Object) read(economy, "$.nation.lastRound")).isNull();

		List<Integer> capitalWorkers = read(economy, "$.cities[?(@.provinceKey == 'varnhold')].employment.tier1Workers");
		assertThat(capitalWorkers).containsExactly(4_000);
		List<Integer> farmers = read(economy, "$.cities[?(@.provinceKey == 'varnhold')].employment.agricultureWorkers");
		assertThat(farmers).containsExactly(8_000);
		List<Integer> income = read(economy, "$.cities[?(@.provinceKey == 'varnhold')].collectibleIncomeMg");
		assertThat(income).containsExactly(20_000);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.building WHERE game_id = ?::uuid", Integer.class,
				game.gameId())).as("one starting workshop per capital").isEqualTo(2);
	}

	@Test
	void treasuriesArePrivateButCityEconomiesArePublic() throws Exception {
		Lobby game = startedGame();
		String ostraCity = api.cityId(game.host(), game.gameId(), "ostrava");

		MvcResult foreignCity = api.cityEconomy(game.host(), game.gameId(), ostraCity);
		assertThat(status(foreignCity)).isEqualTo(200);
		assertThat((Integer) read(foreignCity, "$.population")).isEqualTo(12_000);
		assertThat((Boolean) read(foreignCity, "$.construction.allowed")).isFalse();
		assertThat(ApiDriver.<List<String>>read(foreignCity, "$.construction.blockers")).contains("NOT_OWNER");

		assertThat(status(api.economy(newPlayer(), game.gameId()))).isEqualTo(404);
		assertThat(status(api.cityEconomy(game.host(), game.gameId(), UUID.randomUUID().toString()))).isEqualTo(404);
	}

	// --- construction -----------------------------------------------------------------------

	@Test
	void theActivePlayerBuildsAWorkshopInOwnTerritory() throws Exception {
		Lobby game = startedGame();
		String elmsford = api.cityId(game.host(), game.gameId(), "elmsford");

		MvcResult built = api.buildWorkshop(game.host(), game.gameId(), elmsford, 1, UUID.randomUUID());

		assertThat(status(built)).isEqualTo(201);
		assertThat(longAt(built, "$.treasuryMg")).isEqualTo(START_TREASURY - WORKSHOP_COST);
		assertThat((String) read(built, "$.workshop.status")).isEqualTo("UNDER_CONSTRUCTION");
		assertThat((Integer) read(built, "$.workshop.completesAfterRound")).isEqualTo(1);
		assertThat((Integer) read(built, "$.workshop.operationalFromRound")).isEqualTo(2);
		assertThat((Integer) read(built, "$.city.workshopCount")).isEqualTo(1);
		assertThat((Integer) read(built, "$.city.employment.tier1Workers")).as("a project employs nobody").isZero();
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedGrossIncomeMg"))
			.as("no income before completion").isEqualTo(VARN_INCOME);
	}

	@Test
	void constructionIsRejectedForOtherPlayersTurnsForeignCitiesAndOutsiders() throws Exception {
		Lobby game = startedGame();
		String elmsford = api.cityId(game.host(), game.gameId(), "elmsford");
		String ostrava = api.cityId(game.host(), game.gameId(), "ostrava");

		MvcResult notYourTurn = api.buildWorkshop(game.guest(), game.gameId(), ostrava, 1, UUID.randomUUID());
		assertThat(status(notYourTurn)).isEqualTo(409);
		assertThat((String) read(notYourTurn, "$.code")).isEqualTo("NOT_ACTIVE_PLAYER");

		MvcResult foreign = api.buildWorkshop(game.host(), game.gameId(), ostrava, 1, UUID.randomUUID());
		assertThat(status(foreign)).isEqualTo(403);
		assertThat((String) read(foreign, "$.code")).isEqualTo("NOT_OWNER");

		assertThat(status(api.buildWorkshop(newPlayer(), game.gameId(), elmsford, 1, UUID.randomUUID()))).isEqualTo(404);
		assertThat((String) read(api.buildWorkshop(game.host(), game.gameId(), elmsford, 2, UUID.randomUUID()), "$.code"))
			.isEqualTo("TIER_LOCKED");
		assertThat((String) read(api.buildWorkshop(game.host(), game.gameId(), elmsford, 1, null), "$.code"))
			.isEqualTo("IDEMPOTENCY_KEY_MISSING");
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.treasuryMg")).isEqualTo(START_TREASURY);
	}

	@Test
	void insufficientTreasuryIsRejectedWithoutSideEffects() throws Exception {
		Lobby game = startedGame();
		String elmsford = api.cityId(game.host(), game.gameId(), "elmsford");
		api.buildWorkshop(game.host(), game.gameId(), elmsford, 1, UUID.randomUUID());

		MvcResult poor = api.buildWorkshop(game.host(), game.gameId(), elmsford, 1, UUID.randomUUID());

		assertThat(status(poor)).isEqualTo(422);
		assertThat((String) read(poor, "$.code")).isEqualTo("INSUFFICIENT_GOLD");
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.treasuryMg"))
			.isEqualTo(START_TREASURY - WORKSHOP_COST);
		assertThat(buildingsIn(game, elmsford)).isEqualTo(1);
	}

	@Test
	void concurrentOrdersCannotOverspendTheTreasury() throws Exception {
		Lobby game = startedGame();
		String elmsford = api.cityId(game.host(), game.gameId(), "elmsford");

		List<Integer> statuses = Concurrently.run(6,
				i -> status(api.buildWorkshop(game.host(), game.gameId(), elmsford, 1, UUID.randomUUID())));

		assertThat(statuses).containsOnlyOnce(201).containsOnly(201, 422);
		assertThat(treasury(game, "varn")).isEqualTo(START_TREASURY - WORKSHOP_COST);
		assertThat(buildingsIn(game, elmsford)).isEqualTo(1);
	}

	@Test
	void concurrentOrdersCannotExceedTheCityLimit() throws Exception {
		Lobby game = startedGame();
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		setTreasury(game, "varn", 1_000_000);

		List<Integer> statuses = Concurrently.run(10,
				i -> status(api.buildWorkshop(game.host(), game.gameId(), varnhold, 1, UUID.randomUUID())));

		// The capital starts with one workshop; the limit is 6.
		assertThat(statuses.stream().filter(s -> s == 201)).hasSize(5);
		assertThat(statuses).containsOnly(201, 422);
		assertThat(buildingsIn(game, varnhold)).isEqualTo(6);
		assertThat(treasury(game, "varn")).isEqualTo(1_000_000 - 5 * WORKSHOP_COST);
		assertThat((String) read(api.buildWorkshop(game.host(), game.gameId(), varnhold, 1, UUID.randomUUID()), "$.code"))
			.isEqualTo("WORKSHOP_LIMIT_REACHED");
	}

	@Test
	void aRetriedOrderIsReplayedAndPaidOnce() throws Exception {
		Lobby game = startedGame();
		String elmsford = api.cityId(game.host(), game.gameId(), "elmsford");
		setTreasury(game, "varn", 500_000);
		UUID key = UUID.randomUUID();

		MvcResult first = api.buildWorkshop(game.host(), game.gameId(), elmsford, 1, key);
		List<MvcResult> retries = Concurrently.run(5, i -> api.buildWorkshop(game.host(), game.gameId(), elmsford, 1, key));

		assertThat(retries).allSatisfy(retry -> {
			assertThat(status(retry)).isEqualTo(201);
			assertThat(readUnchecked(retry, "$.workshop.id")).isEqualTo(readUnchecked(first, "$.workshop.id"));
		});
		assertThat(buildingsIn(game, elmsford)).isEqualTo(1);
		assertThat(treasury(game, "varn")).isEqualTo(500_000 - WORKSHOP_COST);
	}

	@Test
	void economiesOfDifferentGamesAreIndependent() throws Exception {
		Lobby first = startedGame();
		Lobby second = startedGame();

		api.buildWorkshop(first.host(), first.gameId(), api.cityId(first.host(), first.gameId(), "elmsford"), 1,
				UUID.randomUUID());

		assertThat(treasury(first, "varn")).isEqualTo(START_TREASURY - WORKSHOP_COST);
		assertThat(treasury(second, "varn")).isEqualTo(START_TREASURY);
		String foreignCity = api.cityId(second.host(), second.gameId(), "elmsford");
		assertThat(status(api.buildWorkshop(first.host(), first.gameId(), foreignCity, 1, UUID.randomUUID())))
			.as("a city of another game does not exist in this one")
			.isEqualTo(404);
	}

	// --- round end ----------------------------------------------------------------------------

	@Test
	void roundEndPaysIncomeOnceThenCompletesTheProjectWhichEarnsFromTheNextRound() throws Exception {
		Lobby game = startedGame();
		String elmsford = api.cityId(game.host(), game.gameId(), "elmsford");
		api.buildWorkshop(game.host(), game.gameId(), elmsford, 1, UUID.randomUUID());
		api.endTurn(game.host(), game.gameId(), 1, UUID.randomUUID());
		UUID guestKey = UUID.randomUUID();

		MvcResult roundEnd = api.endTurn(game.guest(), game.gameId(), 2, guestKey);
		assertThat((Boolean) read(roundEnd, "$.roundCompleted")).isTrue();

		// Round 1 income used round-1 buildings: the project did not count yet.
		assertThat(treasury(game, "varn")).isEqualTo(START_TREASURY - WORKSHOP_COST + VARN_INCOME);
		assertThat(treasury(game, "ostra")).isEqualTo(START_TREASURY + VARN_INCOME);
		MvcResult economy = api.economy(game.host(), game.gameId());
		assertThat((Integer) read(economy, "$.nation.lastRound.round")).isEqualTo(1);
		assertThat(longAt(economy, "$.nation.lastRound.grossIncomeMg")).isEqualTo(VARN_INCOME);
		List<String> statusAfter = read(economy, "$.cities[?(@.provinceKey == 'elmsford')].workshops[0].status");
		assertThat(statusAfter).containsExactly("ACTIVE");
		// Elmsford: 8,000 people, now 4,000 in the workshop: +8 gold from round 2.
		assertThat(longAt(economy, "$.nation.projectedGrossIncomeMg")).isEqualTo(VARN_INCOME + 8_000);

		// Retrying the round-ending request changes nothing.
		MvcResult retry = api.endTurn(game.guest(), game.gameId(), 2, guestKey);
		assertThat((Boolean) read(retry, "$.replayed")).isTrue();
		assertThat(treasury(game, "varn")).isEqualTo(START_TREASURY - WORKSHOP_COST + VARN_INCOME);
		assertThat(reports(game)).isEqualTo(2);

		// Round 2 pays the larger income.
		api.endTurn(game.host(), game.gameId(), 3, UUID.randomUUID());
		api.endTurn(game.guest(), game.gameId(), 4, UUID.randomUUID());
		assertThat(treasury(game, "varn")).isEqualTo(START_TREASURY - WORKSHOP_COST + VARN_INCOME + VARN_INCOME + 8_000);
		assertThat(reports(game)).isEqualTo(4);
	}

	@Test
	void anOccupiedCityPaysNobodyUntilItIsLiberated() throws Exception {
		Lobby game = startedGame();
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		// Occupation mechanics arrive with the army phases; simulate one by changing the controller.
		occupy(game, "varnhold", "ostra");

		MvcResult city = api.cityEconomy(game.host(), game.gameId(), varnhold);
		assertThat((String) read(city, "$.incomeStatus")).isEqualTo("OCCUPIED");
		assertThat(longAt(city, "$.potentialIncome.totalMg")).isEqualTo(20_000);
		assertThat(longAt(city, "$.collectibleIncomeMg")).isZero();
		assertThat(ApiDriver.<List<String>>read(city, "$.construction.blockers")).contains("OCCUPIED");
		MvcResult occupiedBuild = api.buildWorkshop(game.host(), game.gameId(), varnhold, 1, UUID.randomUUID());
		assertThat((String) read(occupiedBuild, "$.code")).isEqualTo("PROVINCE_OCCUPIED");
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedGrossIncomeMg"))
			.isEqualTo(VARN_INCOME - 20_000);
		assertThat(longAt(api.economy(game.guest(), game.gameId()), "$.nation.projectedGrossIncomeMg"))
			.as("the occupier gains nothing").isEqualTo(VARN_INCOME);

		completeRound(game, 1);
		assertThat(treasury(game, "varn")).isEqualTo(START_TREASURY + VARN_INCOME - 20_000);
		assertThat(treasury(game, "ostra")).isEqualTo(START_TREASURY + VARN_INCOME);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.building WHERE game_id = ?::uuid AND status = 'ACTIVE'",
				Integer.class, game.gameId())).as("occupation damages nothing").isEqualTo(2);

		occupy(game, "varnhold", "varn");
		completeRound(game, 2);
		assertThat(treasury(game, "varn")).isEqualTo(START_TREASURY + VARN_INCOME - 20_000 + VARN_INCOME);
	}

	// --- helpers ------------------------------------------------------------------------------

	/** Host plays Varn (seat 0, moves first), guest plays Ostra. */
	private Lobby startedGame() throws Exception {
		Lobby lobby = api.readyLobby("Economy " + UUID.randomUUID().toString().substring(0, 6));
		MvcResult view = api.lobby(lobby.host(), lobby.gameId());
		String varn = ApiDriver.<List<String>>read(view, "$.nations[?(@.key == 'varn')].id").getFirst();
		String ostra = ApiDriver.<List<String>>read(view, "$.nations[?(@.key == 'ostra')].id").getFirst();
		api.chooseNation(lobby.host(), lobby.gameId(), varn);
		api.chooseNation(lobby.guest(), lobby.gameId(), ostra);
		assertThat(status(api.start(lobby.host(), lobby.gameId()))).isEqualTo(200);
		return lobby;
	}

	private void completeRound(Lobby game, int round) throws Exception {
		int turn = 2 * round - 1;
		assertThat(status(api.endTurn(game.host(), game.gameId(), turn, UUID.randomUUID()))).isEqualTo(200);
		assertThat(status(api.endTurn(game.guest(), game.gameId(), turn + 1, UUID.randomUUID()))).isEqualTo(200);
	}

	private long treasury(Lobby game, String nationKey) {
		return jdbc.queryForObject("""
				SELECT ne.treasury_mg FROM game.nation_economy ne
				JOIN game.nation n ON n.id = ne.nation_id
				JOIN game.nation_template nt ON nt.id = n.nation_template_id
				WHERE ne.game_id = ?::uuid AND nt.key = ?
				""", Long.class, game.gameId(), nationKey);
	}

	private void setTreasury(Lobby game, String nationKey, long treasuryMg) {
		jdbc.update("""
				UPDATE game.nation_economy ne SET treasury_mg = ?
				FROM game.nation n JOIN game.nation_template nt ON nt.id = n.nation_template_id
				WHERE ne.nation_id = n.id AND ne.game_id = ?::uuid AND nt.key = ?
				""", treasuryMg, game.gameId(), nationKey);
	}

	private void occupy(Lobby game, String provinceKey, String controllerKey) {
		jdbc.update("""
				UPDATE game.province_state ps SET controller_nation_id = n.id
				FROM game.province_template pt, game.nation n, game.nation_template nt
				WHERE ps.province_template_id = pt.id AND pt.key = ? AND ps.game_id = ?::uuid
				  AND n.game_id = ps.game_id AND nt.id = n.nation_template_id AND nt.key = ?
				""", provinceKey, game.gameId(), controllerKey);
	}

	private Integer buildingsIn(Lobby game, String cityId) {
		return jdbc.queryForObject("SELECT count(*) FROM game.building WHERE game_id = ?::uuid AND city_id = ?::uuid",
				Integer.class, game.gameId(), cityId);
	}

	private Integer reports(Lobby game) {
		return jdbc.queryForObject("SELECT count(*) FROM game.economy_round_report WHERE game_id = ?::uuid",
				Integer.class, game.gameId());
	}

	private static long longAt(MvcResult result, String path) throws Exception {
		return ((Number) read(result, path)).longValue();
	}

	private static Object readUnchecked(MvcResult result, String path) {
		try {
			return read(result, path);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
