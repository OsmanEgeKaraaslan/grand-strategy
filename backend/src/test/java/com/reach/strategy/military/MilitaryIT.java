package com.reach.strategy.military;

import static com.reach.strategy.support.ApiDriver.newPlayer;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.military.application.MilitaryRulesets;
import com.reach.strategy.military.rules.Battle;
import com.reach.strategy.military.rules.UnitType;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import com.reach.strategy.support.Concurrently;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 5 against PostgreSQL through the real API: starting forces, recruitment, movement,
 * battles, retreat, occupation, upkeep, races, idempotency, security and isolation between games.
 * Map facts used: Varnhold–Elmsford and Varnhold–Harrowmere are plain borders, Elmsford–Sablewater
 * crosses a river; Harrowmere's only neighbours (Greyspire, Varnhold) are Varn's.
 */
@PostgresIntegrationTest
// Phase 5/6 rules: ruleset v2 has no fortresses, so every province is occupied the simple way.
@TestPropertySource(properties = "strategy.ruleset.current-version=v2")
class MilitaryIT {

	static final long START_TREASURY = 50_000;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MilitaryRulesets rulesets;

	ApiDriver api;

	MilitaryScenario world;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		world = new MilitaryScenario(api, jdbc);
	}

	// --- starting forces ----------------------------------------------------------------------

	@Test
	void eachNationStartsWithThreeInfantryAndOneCavalryRaisedFromItsCapital() throws Exception {
		Lobby game = world.startedGame();

		MvcResult overview = api.military(game.host(), game.gameId());
		assertThat(status(overview)).isEqualTo(200);
		assertThat((List<?>) read(overview, "$.battalions")).hasSize(8);
		String varn = world.nationId(game, "varn");
		List<String> mine = read(overview, "$.battalions[?(@.nationId == '" + varn + "')].unitType");
		assertThat(mine).containsExactlyInAnyOrder("INFANTRY", "INFANTRY", "INFANTRY", "CAVALRY");
		assertThat(ApiDriver.<List<String>>read(overview, "$.battalions[?(@.nationId == '" + varn + "')].provinceKey"))
			.containsOnly("varnhold");
		assertThat(ApiDriver.<List<String>>read(overview, "$.battalions[?(@.nationId == '" + varn + "')].eligibility"))
			.as("starting forces may move in the first turn").containsOnly("READY");
		assertThat(ApiDriver.<List<Object>>read(overview, "$.battalions[?(@.nationId != '" + varn + "')].eligibility"))
			.as("eligibility is private").containsOnlyNulls();
		assertThat(longAt(overview, "$.upkeep.totalMg")).as("3 × 0.5 + 1.5 gold").isEqualTo(3_000);
		assertThat((Integer) read(overview, "$.upkeep.soldiers")).isEqualTo(4_000);

		assertThat(world.population(game, "varnhold")).isEqualTo(8_000);
		assertThat(world.population(game, "ostrava")).isEqualTo(8_000);
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedUpkeepMg")).isEqualTo(3_000);
	}

	// --- recruitment --------------------------------------------------------------------------

	@Test
	void recruitingTakesGoldAndExactlyOneThousandCiviliansAndReducesIncome() throws Exception {
		Lobby game = world.startedGame();
		world.setPopulation(game, "varnhold", 12_000);
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		assertThat(longAt(api.cityEconomy(game.host(), game.gameId(), varnhold), "$.potentialIncome.totalMg"))
			.as("12,000 people with one Tier 1 workshop").isEqualTo(20_000);

		MvcResult recruited = api.recruit(game.host(), game.gameId(), varnhold, "INFANTRY", UUID.randomUUID());

		assertThat(status(recruited)).isEqualTo(201);
		assertThat(longAt(recruited, "$.treasuryMg")).isEqualTo(START_TREASURY - 10_000);
		assertThat((Integer) read(recruited, "$.cityPopulation")).isEqualTo(11_000);
		assertThat((String) read(recruited, "$.battalion.unitType")).isEqualTo("INFANTRY");
		assertThat((Integer) read(recruited, "$.battalion.manpower")).isEqualTo(1_000);
		assertThat((Integer) read(recruited, "$.battalion.maxManpower")).isEqualTo(1_000);
		assertThat((String) read(recruited, "$.battalion.provinceKey")).isEqualTo("varnhold");
		assertThat((String) read(recruited, "$.battalion.originCityId")).isEqualTo(varnhold);
		assertThat((String) read(recruited, "$.battalion.eligibility")).isEqualTo("RECRUITED_THIS_TURN");

		MvcResult city = api.cityEconomy(game.host(), game.gameId(), varnhold);
		assertThat((Integer) read(city, "$.population")).isEqualTo(11_000);
		assertThat((Integer) read(city, "$.employment.tier1Workers")).isEqualTo(4_000);
		assertThat((Integer) read(city, "$.employment.agricultureWorkers")).isEqualTo(7_000);
		assertThat(longAt(city, "$.potentialIncome.totalMg")).as("the soldiers no longer farm").isEqualTo(19_000);
		assertThat(world.treasury(game, "varn")).isEqualTo(START_TREASURY - 10_000);
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedUpkeepMg")).isEqualTo(3_500);
	}

	@Test
	void eachUnitTypeCostsItsRulesetPriceAndUnaffordableRecruitsChangeNothing() throws Exception {
		Lobby game = world.startedGame();
		String elmsford = api.cityId(game.host(), game.gameId(), "elmsford");

		MvcResult cavalry = api.recruit(game.host(), game.gameId(), elmsford, "CAVALRY", UUID.randomUUID());
		assertThat(status(cavalry)).isEqualTo(201);
		assertThat(longAt(cavalry, "$.treasuryMg")).isEqualTo(START_TREASURY - 25_000);
		assertThat(longAt(cavalry, "$.battalion.upkeepMg")).isEqualTo(1_500);

		MvcResult artillery = api.recruit(game.host(), game.gameId(), elmsford, "ARTILLERY", UUID.randomUUID());
		assertThat(status(artillery)).isEqualTo(422);
		assertThat((String) read(artillery, "$.code")).isEqualTo("INSUFFICIENT_GOLD");
		assertThat(world.treasury(game, "varn")).isEqualTo(START_TREASURY - 25_000);
		assertThat(world.population(game, "elmsford")).isEqualTo(7_000);
		assertThat(world.count("game.battalion", game)).isEqualTo(9);
		assertThat(world.commands(game, "RECRUIT")).isEqualTo(1);

		MvcResult overview = api.military(game.host(), game.gameId());
		assertThat(ApiDriver.<List<List<String>>>read(overview,
				"$.recruitment[?(@.provinceKey == 'elmsford')].options[?(@.unitType == 'ARTILLERY')].blockers"))
			.containsExactly(List.of("INSUFFICIENT_GOLD"));
		assertThat(ApiDriver.<List<Integer>>read(overview,
				"$.recruitment[?(@.provinceKey == 'elmsford')].options[?(@.unitType == 'INFANTRY')].populationAfter"))
			.containsExactly(6_000);
		assertThat(ApiDriver.<List<String>>read(overview, "$.recruitment[*].provinceKey"))
			.as("only own cities").containsExactly("brindle", "elmsford", "fenwick", "greyspire", "harrowmere", "varnhold");

		world.setTreasury(game, "varn", 1_000_000);
		world.setPopulation(game, "fenwick", 1_999);
		MvcResult fewPeople = api.recruit(game.host(), game.gameId(), api.cityId(game.host(), game.gameId(), "fenwick"),
				"INFANTRY", UUID.randomUUID());
		assertThat(status(fewPeople)).isEqualTo(422);
		assertThat((String) read(fewPeople, "$.code")).isEqualTo("INSUFFICIENT_POPULATION");
		assertThat(world.population(game, "fenwick")).isEqualTo(1_999);
	}

	@Test
	void onlyTheActiveOwnerWhoControlsTheCityRecruits() throws Exception {
		Lobby game = world.startedGame();
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		String sablewater = api.cityId(game.host(), game.gameId(), "sablewater");
		String ostrava = api.cityId(game.guest(), game.gameId(), "ostrava");

		assertThat(code(api.recruit(game.host(), game.gameId(), sablewater, "INFANTRY", UUID.randomUUID())))
			.isEqualTo("403 NOT_OWNER");
		assertThat(code(api.recruit(game.guest(), game.gameId(), ostrava, "INFANTRY", UUID.randomUUID())))
			.isEqualTo("409 NOT_ACTIVE_PLAYER");
		assertThat(code(api.recruit(newPlayer(), game.gameId(), varnhold, "INFANTRY", UUID.randomUUID())))
			.isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.recruit(game.host(), game.gameId(), UUID.randomUUID().toString(), "INFANTRY",
				UUID.randomUUID()))).isEqualTo("404 CITY_NOT_FOUND");
		assertThat(status(api.recruit(game.host(), game.gameId(), varnhold, "INFANTRY", null))).isEqualTo(400);
		assertThat(status(api.recruit(game.host(), game.gameId(), varnhold, "DRAGON", UUID.randomUUID()))).isEqualTo(400);
		assertThat(status(mockMvc.perform(get("/api/v1/games/" + game.gameId() + "/military")).andReturn()))
			.isEqualTo(401);
		assertThat(status(api.military(newPlayer(), game.gameId()))).isEqualTo(404);

		world.occupy(game, "varnhold", "ostra");
		assertThat(code(api.recruit(game.host(), game.gameId(), varnhold, "INFANTRY", UUID.randomUUID())))
			.isEqualTo("409 PROVINCE_OCCUPIED");

		assertThat(world.treasury(game, "varn")).isEqualTo(START_TREASURY);
		assertThat(world.population(game, "varnhold")).isEqualTo(8_000);
		assertThat(world.count("game.battalion", game)).isEqualTo(8);
		assertThat(world.commands(game, "RECRUIT")).isZero();
	}

	@Test
	void simultaneousRecruitmentsNeverOverspendAndARetriedRecruitRunsOnce() throws Exception {
		Lobby game = world.startedGame();
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		world.setTreasury(game, "varn", 25_000);

		List<MvcResult> results = Concurrently.run(6,
				i -> api.recruit(game.host(), game.gameId(), varnhold, "INFANTRY", UUID.randomUUID()));

		assertThat(results.stream().filter(r -> status(r) == 201)).hasSize(2);
		assertThat(results.stream().filter(r -> status(r) == 422)).hasSize(4);
		assertThat(world.treasury(game, "varn")).isEqualTo(5_000);
		assertThat(world.population(game, "varnhold")).isEqualTo(6_000);
		assertThat(world.battalions(game, "varn", "varnhold")).hasSize(6);

		world.setTreasury(game, "varn", START_TREASURY);
		UUID key = UUID.randomUUID();
		List<MvcResult> retries = Concurrently.run(4, i -> api.recruit(game.host(), game.gameId(), varnhold, "CAVALRY", key));
		assertThat(retries).allSatisfy(r -> assertThat(status(r)).isEqualTo(201));
		List<String> ids = new ArrayList<>();
		for (MvcResult retry : retries) {
			ids.add(read(retry, "$.battalion.id"));
		}
		assertThat(ids).as("every retry reports the same battalion").containsOnly(ids.getFirst());
		assertThat(world.treasury(game, "varn")).isEqualTo(START_TREASURY - 25_000);
		assertThat(world.battalions(game, "varn", "varnhold")).hasSize(7);
	}

	// --- movement -----------------------------------------------------------------------------

	@Test
	void battalionsMoveOneAdjacentProvincePerTurnAndTheWholeSelectionIsValidated() throws Exception {
		Lobby game = world.startedGame();
		List<String> start = world.battalions(game, "varn", "varnhold");
		String a = start.get(0);
		String b = start.get(1);
		String c = start.get(2);
		String guestBattalion = world.battalions(game, "ostra", "ostrava").getFirst();

		MvcResult moved = api.move(game.host(), game.gameId(), List.of(a, b), "elmsford", UUID.randomUUID());
		assertThat(status(moved)).isEqualTo(200);
		assertThat((String) read(moved, "$.outcome")).isEqualTo("MOVED");
		assertThat(ApiDriver.<List<String>>read(moved, "$.battalions[*].eligibility")).containsOnly("ALREADY_MOVED");
		assertThat(world.provinceOf(a)).isEqualTo("elmsford");
		assertThat(world.provinceOf(c)).as("the rest of the stack stays").isEqualTo("varnhold");

		assertThat(code(api.move(game.host(), game.gameId(), List.of(a), "fenwick", UUID.randomUUID())))
			.isEqualTo("422 BATTALION_ALREADY_MOVED");
		String ready = world.place(game, "varn", "elmsford", "INFANTRY", 1_000);
		assertThat(code(api.move(game.host(), game.gameId(), List.of(ready, a), "fenwick", UUID.randomUUID())))
			.as("one ineligible battalion rejects the whole selection").isEqualTo("422 BATTALION_ALREADY_MOVED");
		assertThat(world.provinceOf(ready)).isEqualTo("elmsford");
		assertThat(world.battalion(ready).get("last_moved_turn_number")).isNull();

		assertThat(code(api.move(game.host(), game.gameId(), List.of(a, c), "fenwick", UUID.randomUUID())))
			.isEqualTo("422 BATTALIONS_NOT_COLOCATED");
		assertThat(code(api.move(game.host(), game.gameId(), List.of(c), "sablewater", UUID.randomUUID())))
			.isEqualTo("422 NOT_ADJACENT");
		assertThat(code(api.move(game.host(), game.gameId(), List.of(c), "varnhold", UUID.randomUUID())))
			.isEqualTo("422 NOT_ADJACENT");
		assertThat(code(api.move(game.host(), game.gameId(), List.of(c), "atlantis", UUID.randomUUID())))
			.isEqualTo("404 PROVINCE_NOT_FOUND");
		assertThat(code(api.move(game.host(), game.gameId(), List.of(c, guestBattalion), "harrowmere",
				UUID.randomUUID()))).isEqualTo("403 NOT_YOUR_BATTALION");
		assertThat(code(api.move(game.host(), game.gameId(), List.of(UUID.randomUUID().toString()), "harrowmere",
				UUID.randomUUID()))).isEqualTo("404 BATTALION_NOT_FOUND");
		assertThat(code(api.move(game.guest(), game.gameId(), List.of(guestBattalion), "sablewater",
				UUID.randomUUID()))).isEqualTo("409 NOT_ACTIVE_PLAYER");
		assertThat(status(api.move(game.host(), game.gameId(), List.of(), "harrowmere", UUID.randomUUID())))
			.isEqualTo(400);
		assertThat(status(api.move(game.host(), game.gameId(), List.of(c), "harrowmere", null))).isEqualTo(400);
		assertThat(world.provinceOf(c)).isEqualTo("varnhold");
		assertThat(world.commands(game, "MOVE")).isEqualTo(1);

		world.completeRound(game, 1);
		assertThat(status(api.move(game.host(), game.gameId(), List.of(a), "fenwick", UUID.randomUUID())))
			.as("eligible again in the next own turn").isEqualTo(200);
	}

	@Test
	void newRecruitsCannotMoveOrAttackInTheirFirstTurnButDefendImmediately() throws Exception {
		Lobby game = world.startedGame();
		String elmsford = api.cityId(game.host(), game.gameId(), "elmsford");
		String recruit = read(api.recruit(game.host(), game.gameId(), elmsford, "INFANTRY", UUID.randomUUID()),
				"$.battalion.id");
		String veteran = world.place(game, "varn", "elmsford", "INFANTRY", 1_000);

		assertThat(code(api.move(game.host(), game.gameId(), List.of(recruit), "sablewater", UUID.randomUUID())))
			.isEqualTo("422 BATTALION_RECRUITED_THIS_TURN");
		assertThat(code(api.move(game.host(), game.gameId(), List.of(veteran, recruit), "fenwick", UUID.randomUUID())))
			.isEqualTo("422 BATTALION_RECRUITED_THIS_TURN");
		assertThat(world.provinceOf(veteran)).isEqualTo("elmsford");

		// Ostra attacks Elmsford during its own turn, before the recruit could ever act.
		world.endTurn(game, game.host(), 1);
		List<String> attackers = List.of(world.place(game, "ostra", "sablewater", "INFANTRY", 1_000));
		MvcResult attack = api.move(game.guest(), game.gameId(), attackers, "elmsford", UUID.randomUUID());
		assertThat(status(attack)).isEqualTo(200);
		assertThat(ApiDriver.<List<String>>read(attack, "$.battle.details.defenders[*].battalionId"))
			.containsExactlyInAnyOrder(recruit, veteran);
		assertThat((Boolean) read(attack, "$.battle.details.river")).as("Sablewater–Elmsford is a river").isTrue();
		assertThat((String) read(attack, "$.outcome")).as("1 infantry across a river vs 2").isEqualTo("ATTACK_REPELLED");
		assertThat(world.manpower(recruit)).isLessThan(1_000);
	}

	@Test
	void splittingAndRegroupingNeverResetMovement() throws Exception {
		Lobby game = world.startedGame();
		List<String> start = world.battalions(game, "varn", "varnhold");

		MvcResult army = api.regroup(game.host(), game.gameId(), "First Army", start, UUID.randomUUID());
		assertThat(status(army)).isEqualTo(201);
		String firstArmy = read(army, "$.army.id");
		assertThat((List<?>) read(army, "$.army.battalionIds")).hasSize(4);

		List<String> detached = start.subList(0, 2);
		List<String> remaining = start.subList(2, 4);
		assertThat(status(api.move(game.host(), game.gameId(), detached, "elmsford", UUID.randomUUID()))).isEqualTo(200);
		assertThat(world.battalion(detached.get(0)).get("army_id")).as("moved without its army: leaves it").isNull();
		assertThat(world.battalion(remaining.get(0)).get("army_id")).isEqualTo(firstArmy);

		MvcResult vanguard = api.regroup(game.host(), game.gameId(), "Vanguard", detached, UUID.randomUUID());
		assertThat(status(vanguard)).isEqualTo(201);
		assertThat(code(api.move(game.host(), game.gameId(), detached, "fenwick", UUID.randomUUID())))
			.as("a new army does not refresh movement").isEqualTo("422 BATTALION_ALREADY_MOVED");
		assertThat(code(api.regroup(game.host(), game.gameId(), "Mixed", List.of(detached.get(0), remaining.get(0)),
				UUID.randomUUID()))).isEqualTo("422 BATTALIONS_NOT_COLOCATED");

		assertThat(status(api.move(game.host(), game.gameId(), remaining, "harrowmere", UUID.randomUUID())))
			.isEqualTo(200);
		assertThat(world.battalion(remaining.get(0)).get("army_id")).as("a whole army moves as one").isEqualTo(firstArmy);
		assertThat(status(api.regroup(game.host(), game.gameId(), "Renamed", remaining, UUID.randomUUID())))
			.isEqualTo(201);
		MvcResult overview = api.military(game.host(), game.gameId());
		assertThat(ApiDriver.<List<String>>read(overview, "$.armies[*].name"))
			.as("the emptied army is removed").containsExactlyInAnyOrder("Vanguard", "Renamed");
		assertThat(ApiDriver.<List<String>>read(overview, "$.battalions[?(@.provinceKey == 'harrowmere')].eligibility"))
			.containsOnly("ALREADY_MOVED");
	}

	// --- battles ------------------------------------------------------------------------------

	@Test
	void aWinningAttackEntersTheProvinceAndTheDefenderRetreatsToFriendlyTerritory() throws Exception {
		Lobby game = world.startedGame();
		List<String> attackers = world.battalions(game, "varn", "varnhold");
		String defender = world.place(game, "ostra", "elmsford", "INFANTRY", 1_000);
		long soldiersBefore = world.totalManpower(game);

		MvcResult attack = api.move(game.host(), game.gameId(), attackers, "elmsford", UUID.randomUUID());

		assertThat(status(attack)).isEqualTo(200);
		assertThat((String) read(attack, "$.outcome")).isEqualTo("ATTACK_WON");
		assertThat((String) read(attack, "$.battle.winner")).isEqualTo("ATTACKER");
		assertThat((String) read(attack, "$.battle.details.retreatProvinceKey"))
			.as("Sablewater is the only Ostran neighbour").isEqualTo("sablewater");
		int attackerLosses = read(attack, "$.battle.details.attackerLosses");
		int defenderLosses = read(attack, "$.battle.details.defenderLosses");

		// The server's numbers are exactly the documented engine with the game's ruleset.
		Battle.Result expected = Battle.resolve(new Battle.Input(
				attackers.stream()
					.map(id -> new Battle.Fighter(UUID.fromString(id), UnitType.valueOf((String) world.battalion(id)
						.get("unit_type")), 1_000))
					.toList(),
				List.of(new Battle.Fighter(UUID.fromString(defender), UnitType.INFANTRY, 1_000)), false, false),
				rulesets.rules("v2"));
		assertThat(attackerLosses).isEqualTo(expected.attackerLosses());
		assertThat(defenderLosses).isEqualTo(expected.defenderLosses());
		assertThat(attackerLosses).isPositive();

		assertThat(world.provinceOf(defender)).isEqualTo("sablewater");
		assertThat(world.manpower(defender)).isEqualTo(1_000 - defenderLosses);
		assertThat(attackers).allSatisfy(id -> {
			assertThat(world.provinceOf(id)).isEqualTo("elmsford");
			assertThat(world.battalion(id).get("last_moved_turn_number")).isEqualTo(1);
		});
		assertThat(world.totalManpower(game)).as("the dead are gone, nobody is created")
			.isEqualTo(soldiersBefore - attackerLosses - defenderLosses);
		assertThat(world.population(game, "elmsford")).as("battles never kill civilians").isEqualTo(8_000);
		assertThat(world.ownerAndController(game, "elmsford")).containsExactly("varn", "varn");

		// The partial battalion persists and costs proportionally less upkeep.
		MvcResult guestView = api.military(game.guest(), game.gameId());
		assertThat(ApiDriver.<List<Integer>>read(guestView, "$.battalions[?(@.id == '" + defender + "')].upkeepMg"))
			.containsExactly(500 * (1_000 - defenderLosses) / 1_000);
		assertThat(ApiDriver.<List<String>>read(guestView, "$.recentBattles[*].provinceKey")).containsExactly("elmsford");

		String battleId = read(attack, "$.battle.id");
		MvcResult report = api.battle(game.guest(), game.gameId(), battleId);
		assertThat(status(report)).isEqualTo(200);
		assertThat((List<?>) read(report, "$.details.phases")).isNotEmpty();
		assertThat((String) read(report, "$.rulesetVersion")).isEqualTo("v2");
		assertThat((List<?>) read(api.battles(game.host(), game.gameId()), "$")).hasSize(1);
		assertThat(status(api.battle(newPlayer(), game.gameId(), battleId))).isEqualTo(404);
		assertThat(code(api.battle(game.host(), game.gameId(), UUID.randomUUID().toString())))
			.isEqualTo("404 BATTLE_NOT_FOUND");
	}

	@Test
	void aDefeatedDefenderWithNowhereToRetreatIsDestroyedAndCostsNoMoreUpkeep() throws Exception {
		Lobby game = world.startedGame();
		String defender = world.place(game, "ostra", "harrowmere", "INFANTRY", 600);
		assertThat(longAt(api.economy(game.guest(), game.gameId()), "$.nation.projectedUpkeepMg")).isEqualTo(3_300);

		MvcResult attack = api.move(game.host(), game.gameId(), world.battalions(game, "varn", "varnhold"), "harrowmere",
				UUID.randomUUID());

		assertThat((String) read(attack, "$.outcome")).isEqualTo("ATTACK_WON");
		assertThat((Boolean) read(attack, "$.battle.details.defendersDestroyed")).isTrue();
		assertThat(ApiDriver.<List<String>>read(attack, "$.battle.details.defenders[*].fate")).containsExactly("DESTROYED");
		assertThat(world.battalion(defender)).isNull();
		assertThat(longAt(api.economy(game.guest(), game.gameId()), "$.nation.projectedUpkeepMg")).isEqualTo(3_000);

		world.completeRound(game, 1);
		MvcResult ostra = api.economy(game.guest(), game.gameId());
		assertThat(longAt(ostra, "$.nation.lastRound.upkeepMg")).as("destroyed battalions are not charged")
			.isEqualTo(3_000);
	}

	@Test
	void aRepelledAttackerStaysInItsOriginWithItsMovementSpent() throws Exception {
		Lobby game = world.startedGame();
		List<String> defenders = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			defenders.add(world.place(game, "ostra", "elmsford", "INFANTRY", 1_000));
		}
		String cavalry = jdbc.queryForObject(
				"SELECT id::text FROM game.battalion WHERE game_id = ?::uuid AND nation_id = ?::uuid AND unit_type = 'CAVALRY'",
				String.class, game.gameId(), world.nationId(game, "varn"));

		MvcResult attack = api.move(game.host(), game.gameId(), List.of(cavalry), "elmsford", UUID.randomUUID());

		assertThat((String) read(attack, "$.outcome")).isEqualTo("ATTACK_REPELLED");
		assertThat((String) read(attack, "$.battle.details.broken")).isEqualTo("ATTACKER");
		assertThat(world.provinceOf(cavalry)).isEqualTo("varnhold");
		assertThat(world.manpower(cavalry)).isBetween(1, 999);
		assertThat(code(api.move(game.host(), game.gameId(), List.of(cavalry), "harrowmere", UUID.randomUUID())))
			.isEqualTo("422 BATTALION_ALREADY_MOVED");
		assertThat(defenders).allSatisfy(id -> assertThat(world.provinceOf(id)).isEqualTo("elmsford"));
	}

	@Test
	void aBattleIsFoughtOnceWhetherRetriedOrRaced() throws Exception {
		Lobby game = world.startedGame();
		String defender = world.place(game, "ostra", "elmsford", "INFANTRY", 1_000);
		List<String> attackers = world.battalions(game, "varn", "varnhold");
		UUID key = UUID.randomUUID();

		MvcResult first = api.move(game.host(), game.gameId(), attackers, "elmsford", key);
		MvcResult retry = api.move(game.host(), game.gameId(), attackers, "elmsford", key);
		assertThat(status(retry)).isEqualTo(200);
		assertThat((String) read(retry, "$.battle.id")).isEqualTo(read(first, "$.battle.id"));
		assertThat(world.count("game.battle_report", game)).isEqualTo(1);
		int defenderAfter = world.manpower(defender);

		Lobby race = world.startedGame();
		world.place(race, "ostra", "elmsford", "INFANTRY", 1_000);
		List<String> racers = world.battalions(race, "varn", "varnhold");
		List<MvcResult> results = Concurrently.run(4,
				i -> api.move(race.host(), race.gameId(), racers, "elmsford", UUID.randomUUID()));
		assertThat(results.stream().filter(r -> status(r) == 200)).hasSize(1);
		assertThat(results.stream().filter(r -> status(r) == 422)).hasSize(3);
		assertThat(world.count("game.battle_report", race)).isEqualTo(1);
		assertThat(world.manpower(defender)).as("the first game is untouched by the second").isEqualTo(defenderAfter);
	}

	// --- occupation and upkeep ----------------------------------------------------------------

	@Test
	void anUndefendedEnemyProvinceIsOccupiedAtRoundEndControlOnlyAndPaysNobody() throws Exception {
		Lobby game = world.startedGame();
		List<String> army = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			army.add(world.place(game, "varn", "elmsford", "INFANTRY", 1_000));
		}
		String sablewater = api.cityId(game.host(), game.gameId(), "sablewater");
		long ostraIncome = longAt(api.economy(game.guest(), game.gameId()), "$.nation.projectedGrossIncomeMg");
		long varnIncome = longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedGrossIncomeMg");
		long sablewaterIncome = longAt(api.cityEconomy(game.guest(), game.gameId(), sablewater),
				"$.potentialIncome.totalMg");

		MvcResult moved = api.move(game.host(), game.gameId(), army, "sablewater", UUID.randomUUID());
		assertThat((String) read(moved, "$.outcome")).isEqualTo("MOVED");
		assertThat(world.ownerAndController(game, "sablewater")).as("control changes at round end")
			.containsExactly("ostra", "ostra");

		world.completeRound(game, 1);

		assertThat(world.ownerAndController(game, "sablewater")).as("ownership never changes")
			.containsExactly("ostra", "varn");
		assertThat(world.population(game, "sablewater")).isEqualTo(8_000);
		MvcResult city = api.cityEconomy(game.guest(), game.gameId(), sablewater);
		assertThat((String) read(city, "$.incomeStatus")).isEqualTo("OCCUPIED");
		assertThat(longAt(city, "$.collectibleIncomeMg")).isZero();
		assertThat(longAt(api.economy(game.guest(), game.gameId()), "$.nation.projectedGrossIncomeMg"))
			.isEqualTo(ostraIncome - sablewaterIncome);
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedGrossIncomeMg"))
			.as("the occupier gains nothing").isEqualTo(varnIncome);
		assertThat((Boolean) read(api.provinceMilitary(game.host(), game.gameId(), "sablewater"), "$.occupied")).isTrue();
		assertThat(code(api.recruit(game.host(), game.gameId(), sablewater, "INFANTRY", UUID.randomUUID())))
			.isEqualTo("403 NOT_OWNER");

		world.completeRound(game, 2);
		assertThat(longAt(api.economy(game.guest(), game.gameId()), "$.nation.lastRound.grossIncomeMg"))
			.isEqualTo(ostraIncome - sablewaterIncome);
		assertThat(world.ownerAndController(game, "elmsford")).containsExactly("varn", "varn");
	}

	@Test
	void upkeepIsChargedOnceInTheRoundSettlement() throws Exception {
		Lobby game = world.startedGame();
		long gross = longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedGrossIncomeMg");

		world.endTurn(game, game.host(), 1);
		UUID key = UUID.randomUUID();
		assertThat(status(api.endTurn(game.guest(), game.gameId(), 2, key))).isEqualTo(200);
		MvcResult retry = api.endTurn(game.guest(), game.gameId(), 2, key);
		assertThat((Boolean) read(retry, "$.replayed")).isTrue();

		MvcResult economy = api.economy(game.host(), game.gameId());
		assertThat(longAt(economy, "$.nation.lastRound.upkeepMg")).isEqualTo(3_000);
		assertThat(world.treasury(game, "varn")).isEqualTo(START_TREASURY + gross - 3_000);
		assertThat(world.count("game.economy_round_report", game)).isEqualTo(2);
	}

	// --- isolation ----------------------------------------------------------------------------

	@Test
	void gamesNeverShareBattalions() throws Exception {
		Lobby first = world.startedGame();
		Lobby second = world.startedGame();
		List<String> firstArmy = world.battalions(first, "varn", "varnhold");

		assertThat(code(api.move(second.host(), second.gameId(), firstArmy, "elmsford", UUID.randomUUID())))
			.isEqualTo("404 BATTALION_NOT_FOUND");
		String firstCity = api.cityId(first.host(), first.gameId(), "varnhold");
		assertThat(code(api.recruit(second.host(), second.gameId(), firstCity, "INFANTRY", UUID.randomUUID())))
			.isEqualTo("404 CITY_NOT_FOUND");
		assertThat(status(api.recruit(first.host(), first.gameId(), firstCity, "INFANTRY", UUID.randomUUID())))
			.isEqualTo(201);
		assertThat(world.count("game.battalion", second)).isEqualTo(8);
		assertThat(world.treasury(second, "varn")).isEqualTo(START_TREASURY);

		// The composite keys make a cross-game reference impossible even for direct SQL.
		Map<String, Object> foreignProvince = jdbc.queryForMap(
				"SELECT id FROM game.province_state WHERE game_id = ?::uuid LIMIT 1", first.gameId());
		assertThatThrownBy(() -> jdbc.update("UPDATE game.battalion SET province_state_id = ? WHERE game_id = ?::uuid",
				foreignProvince.get("id"), second.gameId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// --- helpers ------------------------------------------------------------------------------

	private static String code(MvcResult result) throws Exception {
		return status(result) + " " + read(result, "$.code");
	}

	private static long longAt(MvcResult result, String path) throws Exception {
		return ((Number) read(result, path)).longValue();
	}

}
