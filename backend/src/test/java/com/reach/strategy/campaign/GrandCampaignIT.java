package com.reach.strategy.campaign;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.Concurrently;
import com.reach.strategy.world.application.WorldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 10A against PostgreSQL through the real API, ruleset v6 on the Grand Campaign map: 72
 * irregular provinces and 10 factions, 2–4 human players with every other nation NPC-controlled
 * and passive, starting values per faction tier, sequential turns that NPC nations never block,
 * and direct gold gifts.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v6", "strategy.world.default-map=grand-campaign" })
class GrandCampaignIT {

	static final List<String> POWERS = List.of("castavar", "nordmark", "vostrava", "dravonia");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	WorldService worldService;

	ApiDriver api;

	CampaignScenario c;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		c = new CampaignScenario(api, jdbc);
	}

	private static String code(MvcResult result) throws Exception {
		return status(result) + " " + read(result, "$.code");
	}

	// --- the map ----------------------------------------------------------------------------------

	@Test
	void theGrandCampaignMapIsLargeIrregularAndConsistent() throws Exception {
		String token = ApiDriver.newPlayer();
		MvcResult maps = api.maps(token);
		assertThat(ApiDriver.<List<Integer>>read(maps, "$[?(@.key == 'grand-campaign')].maxPlayers")).containsExactly(4);
		assertThat(ApiDriver.<List<Integer>>read(maps, "$[?(@.key == 'grand-campaign')].nations")).containsExactly(10);
		assertThat(ApiDriver.<List<Integer>>read(maps, "$[?(@.key == 'grand-campaign')].playableNations"))
			.containsExactly(4);
		String mapId = ApiDriver.<List<String>>read(maps, "$[?(@.key == 'grand-campaign')].id").getFirst();

		MvcResult map = api.map(token, mapId);
		List<Map<String, Object>> provinces = read(map, "$.provinces");
		assertThat(provinces).hasSize(72);
		assertThat(ApiDriver.<List<Object>>read(map, "$.nations")).hasSize(10);
		assertThat(ApiDriver.<List<Object>>read(map, "$.rivers")).isNotEmpty();
		Map<String, Set<String>> neighbours = new HashMap<>();
		for (Map<String, Object> p : provinces) {
			Set<String> keys = new HashSet<>();
			for (Object n : (List<?>) p.get("neighbours")) {
				keys.add((String) ((Map<?, ?>) n).get("provinceKey"));
			}
			neighbours.put((String) p.get("key"), keys);
			// Irregular outlines: far more corners than a square or a hexagon.
			assertThat(((String) p.get("svgPath")).split("L").length).as((String) p.get("key")).isGreaterThan(12);
			assertThat(p.get("cityX")).isNotNull();
		}
		neighbours.forEach((key, set) -> {
			assertThat(set).as("no self-adjacency").doesNotContain(key);
			set.forEach(other -> assertThat(neighbours.get(other)).as("symmetric " + key + "-" + other).contains(key));
		});
		assertThat(neighbours.values().stream().mapToInt(Set::size).max().orElse(0)).as("more than four neighbours")
			.isGreaterThan(4);
		assertThat(ApiDriver.<List<String>>read(map, "$.provinces[?(@.terrain == 'MOUNTAINS')].key")).contains("sierravel",
				"bernalp", "tatrany");
		// Every border is checked against the outlines at start-up as well; nothing to report.
		assertThat(worldService.validateAllMaps()).isEmpty();
	}

	// --- N humans, NPC nations, turns -----------------------------------------------------------------

	@Test
	void twoThreeAndFourHumansPlayWithEveryOtherNationNpcControlled() throws Exception {
		for (int humans = 2; humans <= 4; humans++) {
			CampaignScenario.Game game = c.start(null, POWERS.subList(0, humans));
			MvcResult state = api.state(game.tokens().getFirst(), game.gameId());
			assertThat(ApiDriver.<List<Object>>read(state, "$.nations")).hasSize(10);
			assertThat(ApiDriver.<List<Object>>read(state, "$.nations[?(@.control == 'HUMAN')]")).hasSize(humans);
			assertThat(ApiDriver.<List<Object>>read(state, "$.nations[?(@.control == 'NPC')]")).hasSize(10 - humans);
			assertThat(ApiDriver.<List<Object>>read(state, "$.nations[?(@.control == 'NPC')].seat")).containsOnlyNulls();
			assertThat(ApiDriver.<List<Object>>read(state, "$.players")).hasSize(humans);
			assertThat(ApiDriver.<List<Object>>read(state, "$.provinces")).hasSize(72);

			// Only humans take turns: the round completes after exactly `humans` end-of-turns.
			for (int seat = 0; seat < humans; seat++) {
				assertThat(c.round(game)).isEqualTo(1);
				assertThat(status(c.endTurn(game))).isEqualTo(200);
			}
			assertThat(c.round(game)).isEqualTo(2);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM game.economy_round_report WHERE game_id = ?::uuid",
					Integer.class, game.gameId())).as("every nation, NPC ones included, is settled").isEqualTo(10);
			assertThat(jdbc.queryForObject("""
					SELECT count(*) FROM game.battalion b JOIN game.nation n ON n.id = b.nation_id
					WHERE b.game_id = ?::uuid AND n.control = 'NPC' AND b.last_moved_turn_number IS NOT NULL
					""", Integer.class, game.gameId())).as("NPC nations are passive").isZero();
		}
	}

	@Test
	void everyProvinceStartsWithItsTemplateOwnerAndStartingValuesFollowTheFactionTier() throws Exception {
		CampaignScenario.Game game = c.start(null, List.of("castavar", "dravonia"));
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM game.province_state ps
				JOIN game.nation n ON n.id = ps.owner_nation_id
				JOIN game.nation_start_province nsp ON nsp.province_template_id = ps.province_template_id
				WHERE ps.game_id = ?::uuid AND nsp.nation_template_id = n.nation_template_id
				  AND ps.controller_nation_id = ps.owner_nation_id
				""", Integer.class, game.gameId())).isEqualTo(72);

		Map<String, Long> treasuryByTier = Map.of("castavar", 50_000L, "aurelia", 80_000L, "serenna", 50_000L, "frisia",
				30_000L);
		treasuryByTier.forEach((nation, mg) -> assertThat(c.treasury(game, nation)).as(nation).isEqualTo(mg));
		Map<String, Integer> soldiers = Map.of("castavar", 4000, "aurelia", 7000, "karpathia", 4000, "aestia", 2000);
		soldiers.forEach((nation, men) -> assertThat(jdbc.queryForObject(
				"SELECT coalesce(sum(manpower), 0) FROM game.battalion WHERE nation_id = ?::uuid", Integer.class,
				c.nationId(game, nation))).as(nation).isEqualTo(men));
		// Raised from the capital's own civilians: Aurel 14,000 − 7,000.
		assertThat(c.world.population(game.lobby(), "aurel")).isEqualTo(7_000);
		assertThat(c.world.fortress(game.lobby(), "meseta")).containsEntry("level", 2);
		assertThat(c.world.fortress(game.lobby(), "sierravel")).containsEntry("level", 1);
		long people = c.world.civilians(game.lobby()) + c.world.totalManpower(game.lobby());
		assertThat(people).as("no population is created at the start").isEqualTo(jdbc.queryForObject("""
				SELECT sum(start_population) FROM game.province_template pt JOIN game.map_template m ON m.id = pt.map_template_id
				WHERE m.key = 'grand-campaign'
				""", Long.class));
	}

	@Test
	void lobbyRulesForTheLargerMap() throws Exception {
		String host = ApiDriver.newPlayer();
		assertThat(code(api.createGame(host, "Too many", 5, "grand-campaign"))).isEqualTo("422 INVALID_PLAYER_COUNT");
		assertThat(code(api.createGame(host, "Nowhere", 2, "atlantis"))).isEqualTo("422 MAP_NOT_FOUND");
		MvcResult created = api.createGame(host, "Lobby rules", 3, null);
		assertThat(status(created)).isEqualTo(201);
		assertThat((Integer) read(created, "$.maxPlayers")).isEqualTo(3);
		assertThat(ApiDriver.<List<Object>>read(created, "$.nations[?(@.playable == true)]")).hasSize(4);
		String gameId = read(created, "$.id");
		String aurelia = ApiDriver.<List<String>>read(created, "$.nations[?(@.key == 'aurelia')].id").getFirst();
		String castavar = ApiDriver.<List<String>>read(created, "$.nations[?(@.key == 'castavar')].id").getFirst();
		assertThat(code(api.chooseNation(host, gameId, aurelia))).isEqualTo("422 NATION_NOT_PLAYABLE");
		assertThat(status(api.chooseNation(host, gameId, castavar))).isEqualTo(200);
		String guest = ApiDriver.newPlayer();
		api.join(guest, read(created, "$.inviteCode"));
		assertThat(code(api.chooseNation(guest, gameId, castavar))).isEqualTo("409 NATION_TAKEN");
		assertThat(code(api.start(host, gameId))).isEqualTo("409 GAME_NOT_READY");
	}

	// --- gold gifts -----------------------------------------------------------------------------------

	@Test
	void aGiftMovesExactlyItsAmountOnceAndIsPublicHistory() throws Exception {
		CampaignScenario.Game game = c.start(null, List.of("castavar", "nordmark"));
		String nordmark = c.nationId(game, "nordmark");
		UUID key = UUID.randomUUID();

		MvcResult gift = api.sendGold(game.token("castavar"), game.gameId(), nordmark, 12_500, key);

		assertThat(status(gift)).as(body(gift)).isEqualTo(201);
		assertThat(((Number) read(gift, "$.treasuryMg")).longValue()).isEqualTo(37_500);
		assertThat(c.treasury(game, "castavar")).isEqualTo(37_500);
		assertThat(c.treasury(game, "nordmark")).isEqualTo(62_500);
		MvcResult retry = api.sendGold(game.token("castavar"), game.gameId(), nordmark, 12_500, key);
		assertThat((String) read(retry, "$.gift.id")).isEqualTo(read(gift, "$.gift.id"));
		assertThat(c.treasury(game, "castavar")).as("not paid twice").isEqualTo(37_500);

		MvcResult overview = api.diplomacy(game.token("nordmark"), game.gameId());
		assertThat(ApiDriver.<List<Integer>>read(overview, "$.gifts[*].amountMg")).containsExactly(12_500);
		assertThat(ApiDriver.<List<String>>read(overview, "$.events[*].kind")).containsExactly("GOLD_GIFT");
		assertThat(((Number) read(overview, "$.rules.giftMaxMg")).longValue()).isEqualTo(500_000);
		assertThat(c.world.commands(game.lobby(), "SEND_GOLD")).isEqualTo(1);
	}

	@Test
	void invalidGiftsAreRefusedAndChangeNothing() throws Exception {
		CampaignScenario.Game game = c.start(null, List.of("castavar", "nordmark"));
		String castavar = c.nationId(game, "castavar");
		String nordmark = c.nationId(game, "nordmark");
		String aurelia = c.nationId(game, "aurelia");
		String host = game.token("castavar");

		assertThat(code(api.sendGold(host, game.gameId(), castavar, 1000, UUID.randomUUID())))
			.isEqualTo("422 CANNOT_TARGET_SELF");
		assertThat(code(api.sendGold(host, game.gameId(), nordmark, 0, UUID.randomUUID())))
			.isEqualTo("422 INVALID_GIFT_AMOUNT");
		assertThat(code(api.sendGold(host, game.gameId(), nordmark, 500_001, UUID.randomUUID())))
			.isEqualTo("422 INVALID_GIFT_AMOUNT");
		assertThat(code(api.sendGold(host, game.gameId(), nordmark, 50_001, UUID.randomUUID())))
			.isEqualTo("422 INSUFFICIENT_GOLD");
		assertThat(code(api.sendGold(host, game.gameId(), UUID.randomUUID().toString(), 1000, UUID.randomUUID())))
			.isEqualTo("404 NATION_NOT_FOUND");
		assertThat(code(api.sendGold(game.token("nordmark"), game.gameId(), castavar, 1000, UUID.randomUUID())))
			.isEqualTo("409 NOT_ACTIVE_PLAYER");
		assertThat(code(api.sendGold(ApiDriver.newPlayer(), game.gameId(), castavar, 1000, UUID.randomUUID())))
			.isEqualTo("404 GAME_NOT_FOUND");
		assertThat(status(api.declareWar(host, game.gameId(), aurelia, UUID.randomUUID()))).isEqualTo(201);
		assertThat(code(api.sendGold(host, game.gameId(), aurelia, 1000, UUID.randomUUID()))).isEqualTo("409 GIFT_TO_ENEMY");

		assertThat(c.treasury(game, "castavar")).isEqualTo(50_000);
		assertThat(c.treasury(game, "nordmark")).isEqualTo(50_000);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.gold_gift WHERE game_id = ?::uuid", Integer.class,
				game.gameId())).isZero();
	}

	@Test
	void simultaneousGiftsNeverOverdrawOrCreateGold() throws Exception {
		CampaignScenario.Game game = c.start(null, List.of("castavar", "nordmark"));
		String nordmark = c.nationId(game, "nordmark");
		long before = jdbc.queryForObject("SELECT sum(treasury_mg) FROM game.nation_economy WHERE game_id = ?::uuid",
				Long.class, game.gameId());

		List<Integer> statuses = Concurrently.run(4,
				i -> status(api.sendGold(game.token("castavar"), game.gameId(), nordmark, 20_000, UUID.randomUUID())));

		assertThat(statuses).filteredOn(s -> s == 201).hasSize(2);
		assertThat(statuses).filteredOn(s -> s != 201).containsOnly(422);
		assertThat(c.treasury(game, "castavar")).isEqualTo(10_000);
		assertThat(c.treasury(game, "nordmark")).isEqualTo(90_000);
		assertThat(jdbc.queryForObject("SELECT sum(treasury_mg) FROM game.nation_economy WHERE game_id = ?::uuid",
				Long.class, game.gameId())).isEqualTo(before);
	}

}
