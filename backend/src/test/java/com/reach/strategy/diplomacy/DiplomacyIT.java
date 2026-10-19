package com.reach.strategy.diplomacy;

import static com.reach.strategy.diplomacy.DiplomacyScenario.term;
import static com.reach.strategy.diplomacy.DiplomacyScenario.terms;
import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.military.MilitaryScenario;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import com.reach.strategy.support.Concurrently;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 9 against PostgreSQL through the real API, ruleset v5: peace by default, war declarations
 * that gate movement, battles, occupation and sieges, alliances with and without military access,
 * war score, peace proposals with atomic settlement, withdrawal, truce and tribute. Host = Varn
 * (capital Varnhold), guest = Ostra (capital Ostrava); each capital starts with 4 battalions.
 */
@PostgresIntegrationTest
class DiplomacyIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiDriver api;

	MilitaryScenario world;

	DiplomacyScenario d;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		d = new DiplomacyScenario(api, jdbc);
		world = d.world;
	}

	private static String code(MvcResult result) throws Exception {
		return status(result) + " " + read(result, "$.code");
	}

	private MvcResult move(Lobby game, String token, String battalionId, String to) throws Exception {
		return api.move(token, game.gameId(), List.of(battalionId), to, UUID.randomUUID());
	}

	// --- peace by default and war declarations ---------------------------------------------------

	@Test
	void aNewGameStartsAtPeaceWithEveryNation() throws Exception {
		Lobby game = world.startedGame();

		MvcResult overview = api.diplomacy(game.host(), game.gameId());
		assertThat(status(overview)).isEqualTo(200);
		assertThat((Boolean) read(overview, "$.available")).isTrue();
		assertThat((String) read(overview, "$.rulesetVersion")).isEqualTo("v5");
		assertThat(ApiDriver.<List<String>>read(overview, "$.nations[?(@.you == false)].status")).containsExactly("PEACE");
		assertThat(ApiDriver.<List<List<String>>>read(overview, "$.nations[?(@.you == false)].warBlockers").getFirst())
			.isEmpty();
		assertThat(ApiDriver.<List<Boolean>>read(overview, "$.nations[?(@.you == false)].weMayEnter")).containsExactly(false);
		assertThat(ApiDriver.<List<Object>>read(overview, "$.wars")).isEmpty();
		assertThat((Integer) read(overview, "$.rules.victoryMaxRounds")).isEqualTo(30);
		assertThat((Integer) read(overview, "$.rules.tributeRateBps")).isEqualTo(1000);
	}

	@Test
	void neutralTerritoryIsClosedAndAPeacefulNeighbourIsNeverAttacked() throws Exception {
		Lobby game = world.startedGame();
		String scout = world.place(game, "varn", "elmsford", "INFANTRY", 1000);
		String ostran = world.place(game, "ostra", "sablewater", "INFANTRY", 1000);

		assertThat(code(move(game, game.host(), scout, "sablewater"))).isEqualTo("422 NO_MILITARY_ACCESS");
		assertThat(world.provinceOf(scout)).isEqualTo("elmsford");
		assertThat(world.manpower(ostran)).isEqualTo(1000);
		assertThat(world.commands(game, "MOVE")).isZero();
		assertThat(world.count("game.battle_report", game)).isZero();

		MvcResult target = api.provinceMilitary(game.host(), game.gameId(), "elmsford");
		assertThat(ApiDriver.<List<Boolean>>read(target, "$.moveTargets[?(@.provinceKey == 'sablewater')].accessible"))
			.containsExactly(false);
		assertThat(ApiDriver.<List<Boolean>>read(target, "$.moveTargets[?(@.provinceKey == 'varnhold')].accessible"))
			.containsExactly(true);
	}

	@Test
	void battalionsOfNationsAtPeaceShareAProvinceWithoutFighting() throws Exception {
		Lobby game = world.startedGame();
		// An Ostran battalion inside Varn (as if it had been allowed in earlier).
		String guest = world.place(game, "ostra", "fenwick", "INFANTRY", 1000);
		String varn = world.place(game, "varn", "brindle", "INFANTRY", 1000);

		MvcResult moved = move(game, game.host(), varn, "fenwick");

		assertThat(status(moved)).isEqualTo(200);
		assertThat((String) read(moved, "$.outcome")).isEqualTo("MOVED");
		assertThat(world.count("game.battle_report", game)).isZero();
		world.completeRound(game, 1);
		assertThat(world.ownerAndController(game, "fenwick")).containsExactly("varn", "varn");
		assertThat(world.manpower(guest)).isEqualTo(1000);
	}

	@Test
	void declaringWarOpensEnemyTerritoryWithoutResettingMovement() throws Exception {
		Lobby game = world.startedGame();
		String moved = world.battalions(game, "varn", "varnhold").getFirst();
		assertThat(status(move(game, game.host(), moved, "elmsford"))).isEqualTo(200);
		String ready = world.place(game, "varn", "elmsford", "INFANTRY", 1000);
		long version = world.stateVersion(game);

		String warId = d.declareWar(game, game.host(), d.ostra(game));

		assertThat(world.stateVersion(game)).isEqualTo(version + 1);
		assertThat(code(move(game, game.host(), moved, "sablewater"))).as("still spent")
			.isEqualTo("422 BATTALION_ALREADY_MOVED");
		assertThat(status(move(game, game.host(), ready, "sablewater"))).isEqualTo(200);
		assertThat(world.provinceOf(ready)).isEqualTo("sablewater");

		MvcResult overview = api.diplomacy(game.guest(), game.gameId());
		assertThat(ApiDriver.<List<String>>read(overview, "$.nations[?(@.you == false)].status")).containsExactly("WAR");
		assertThat(ApiDriver.<List<String>>read(overview, "$.wars[*].id")).containsExactly(warId);
		assertThat((String) read(overview, "$.wars[0].yourSide")).isEqualTo("DEFENDER");
		assertThat((Integer) read(overview, "$.wars[0].durationRounds")).isEqualTo(1);
		assertThat(ApiDriver.<List<String>>read(overview, "$.events[*].kind")).containsExactly("WAR_DECLARED");
		assertThat(world.commands(game, "DECLARE_WAR")).isEqualTo(1);
	}

	@Test
	void invalidDeclarationsAreRejectedWithStableCodes() throws Exception {
		Lobby game = world.startedGame();
		assertThat(code(api.declareWar(game.host(), game.gameId(), d.varn(game), UUID.randomUUID())))
			.isEqualTo("422 CANNOT_TARGET_SELF");
		assertThat(code(api.declareWar(game.host(), game.gameId(), UUID.randomUUID().toString(), UUID.randomUUID())))
			.isEqualTo("404 NATION_NOT_FOUND");
		assertThat(code(api.declareWar(game.guest(), game.gameId(), d.varn(game), UUID.randomUUID())))
			.isEqualTo("409 NOT_ACTIVE_PLAYER");
		assertThat(code(api.declareWar(game.host(), game.gameId(), d.ostra(game), null)))
			.isEqualTo("400 IDEMPOTENCY_KEY_MISSING");
		d.declareWar(game, game.host(), d.ostra(game));
		assertThat(code(api.declareWar(game.host(), game.gameId(), d.ostra(game), UUID.randomUUID())))
			.isEqualTo("409 ALREADY_AT_WAR");
		assertThat(d.wars(game, "ACTIVE")).isEqualTo(1);
	}

	@Test
	void aRetriedDeclarationReplaysAndSimultaneousOnesDeclareOnce() throws Exception {
		Lobby game = world.startedGame();
		UUID key = UUID.randomUUID();
		MvcResult first = api.declareWar(game.host(), game.gameId(), d.ostra(game), key);
		MvcResult retry = api.declareWar(game.host(), game.gameId(), d.ostra(game), key);
		assertThat((String) read(retry, "$.war.id")).isEqualTo(read(first, "$.war.id"));

		Lobby other = world.startedGame();
		List<Integer> statuses = Concurrently.run(4,
				i -> status(api.declareWar(other.host(), other.gameId(), d.ostra(other), UUID.randomUUID())));
		assertThat(statuses).containsOnlyOnce(201);
		assertThat(statuses).filteredOn(s -> s != 201).containsOnly(409);
		assertThat(d.wars(other, "ACTIVE")).isEqualTo(1);
		assertThat(d.events(other, "WAR_DECLARED")).isEqualTo(1);
		assertThat(world.commands(other, "DECLARE_WAR")).isEqualTo(1);
	}

	// --- fighting needs war ---------------------------------------------------------------------

	@Test
	void battlesNeedWarAndEachCountsTowardsWarScoreOnce() throws Exception {
		Lobby game = world.startedGame();
		world.place(game, "ostra", "marrowfield", "INFANTRY", 1000);
		List<String> attackers = List.of(world.place(game, "varn", "fenwick", "INFANTRY", 1000),
				world.place(game, "varn", "fenwick", "INFANTRY", 1000), world.place(game, "varn", "fenwick", "INFANTRY", 1000),
				world.place(game, "varn", "fenwick", "CAVALRY", 1000));
		assertThat(code(api.move(game.host(), game.gameId(), attackers, "marrowfield", UUID.randomUUID())))
			.isEqualTo("422 NO_MILITARY_ACCESS");
		String warId = d.declareWar(game, game.host(), d.ostra(game));

		UUID key = UUID.randomUUID();
		MvcResult attack = api.move(game.host(), game.gameId(), attackers, "marrowfield", key);
		assertThat(status(attack)).as(body(attack)).isEqualTo(200);
		assertThat((String) read(attack, "$.outcome")).isEqualTo("ATTACK_WON");
		int ostranLosses = read(attack, "$.battle.details.defenderLosses");
		assertThat(status(api.move(game.host(), game.gameId(), attackers, "marrowfield", key))).as("replayed").isEqualTo(200);

		MvcResult war = api.war(game.guest(), game.gameId(), warId);
		assertThat(ApiDriver.<List<Object>>read(war, "$.scoreEvents")).hasSize(1);
		assertThat((String) read(war, "$.scoreEvents[0].side")).isEqualTo("ATTACKER");
		assertThat((Integer) read(war, "$.scoreEvents[0].enemyLosses")).isEqualTo(ostranLosses);
		int points = Math.min(10, ostranLosses * 5 / 1000);
		assertThat((Integer) read(war, "$.scoreEvents[0].points")).isEqualTo(points);
		assertThat((Integer) read(war, "$.war.score.attacker.battles")).isEqualTo(points);
		assertThat((Integer) read(war, "$.war.score.attacker.battlesWon")).isEqualTo(1);
		assertThat((Integer) read(war, "$.war.score.defender.total")).isZero();
		assertThat((Integer) read(war, "$.war.score.net")).isEqualTo(read(war, "$.war.score.attacker.total"));
	}

	@Test
	void occupationAndSiegesNeedWar() throws Exception {
		Lobby game = world.startedGame();
		world.place(game, "varn", "marrowfield", "INFANTRY", 1000);
		world.place(game, "varn", "sablewater", "INFANTRY", 1000);

		world.completeRound(game, 1);
		assertThat(world.ownerAndController(game, "marrowfield")).containsExactly("ostra", "ostra");
		assertThat(world.sieges(game)).isEmpty();

		String warId = d.declareWar(game, game.host(), d.ostra(game));
		world.completeRound(game, 2);
		assertThat(world.ownerAndController(game, "marrowfield")).as("occupied, never owned")
			.containsExactly("ostra", "varn");
		assertThat(world.sieges(game)).extracting(s -> s.get("status")).containsExactly("ACTIVE");

		MvcResult war = api.war(game.host(), game.gameId(), warId);
		int marrow = world.population(game, "marrowfield");
		long ostranPopulation = (Long) jdbc.queryForObject("""
				SELECT sum(c.population) FROM game.city c JOIN game.province_state ps ON ps.id = c.province_state_id
				WHERE c.game_id = ?::uuid AND ps.owner_nation_id = ?::uuid
				""", Long.class, game.gameId(), d.ostra(game));
		assertThat(((Number) read(war, "$.war.score.attacker.occupiedEnemyPopulation")).longValue()).isEqualTo(marrow);
		assertThat((Integer) read(war, "$.war.score.attacker.occupation")).isEqualTo((int) (marrow * 50L / ostranPopulation));
	}

	// --- alliances ------------------------------------------------------------------------------

	@Test
	void anAllianceNeedsBothSidesGrantsAccessAndWithdrawsForcesWhenItEnds() throws Exception {
		Lobby game = world.startedGame();
		MvcResult proposed = api.proposeAlliance(game.host(), game.gameId(), d.ostra(game), "NORMAL", UUID.randomUUID());
		assertThat(status(proposed)).isEqualTo(201);
		String allianceId = read(proposed, "$.alliance.id");
		assertThat(code(api.alliance(game.host(), game.gameId(), allianceId, "accept", UUID.randomUUID())))
			.isEqualTo("403 NOT_ALLIANCE_RECIPIENT");
		assertThat(code(api.alliance(game.guest(), game.gameId(), allianceId, "accept", UUID.randomUUID())))
			.isEqualTo("409 NOT_ACTIVE_PLAYER");
		assertThat(code(api.proposeAlliance(game.host(), game.gameId(), d.ostra(game), "DEFENSIVE", UUID.randomUUID())))
			.isEqualTo("409 ALLIANCE_ALREADY_PROPOSED");
		world.endTurn(game, game.host(), 1);

		MvcResult accepted = api.alliance(game.guest(), game.gameId(), allianceId, "accept", UUID.randomUUID());
		assertThat(status(accepted)).isEqualTo(200);
		assertThat((String) read(accepted, "$.alliance.status")).isEqualTo("ACTIVE");
		assertThat((Boolean) read(accepted, "$.alliance.grantsMilitaryAccess")).isTrue();
		String ostran = world.place(game, "ostra", "sablewater", "INFANTRY", 1000);
		assertThat(status(move(game, game.guest(), ostran, "elmsford"))).as("military access").isEqualTo(200);
		world.endTurn(game, game.guest(), 2);

		assertThat(code(api.declareWar(game.host(), game.gameId(), d.ostra(game), UUID.randomUUID())))
			.isEqualTo("409 ALLIED_NATION");
		MvcResult ended = api.alliance(game.host(), game.gameId(), allianceId, "terminate", UUID.randomUUID());
		assertThat(status(ended)).as(body(ended)).isEqualTo(200);
		assertThat((String) read(ended, "$.alliance.status")).isEqualTo("ENDED");
		assertThat(ApiDriver.<List<String>>read(ended, "$.withdrawals[*].toProvinceKey")).containsExactly("sablewater");
		assertThat(world.provinceOf(ostran)).isEqualTo("sablewater");
		assertThat(world.manpower(ostran)).isEqualTo(1000);
		assertThat(d.events(game, "ALLIANCE_FORMED")).isEqualTo(1);
		assertThat(d.events(game, "ALLIANCE_ENDED")).isEqualTo(1);
		assertThat(d.events(game, "FORCES_WITHDRAWN")).isEqualTo(1);
		assertThat(status(api.declareWar(game.host(), game.gameId(), d.ostra(game), UUID.randomUUID()))).isEqualTo(201);
	}

	@Test
	void aDefensivePactGrantsNoAccessAndProposalsCanBeRejected() throws Exception {
		Lobby game = world.startedGame();
		String first = read(api.proposeAlliance(game.host(), game.gameId(), d.ostra(game), "DEFENSIVE", UUID.randomUUID()),
				"$.alliance.id");
		world.endTurn(game, game.host(), 1);
		assertThat(status(api.alliance(game.guest(), game.gameId(), first, "reject", UUID.randomUUID()))).isEqualTo(200);
		assertThat(d.allianceStatus(first)).isEqualTo("REJECTED");
		String second = read(api.proposeAlliance(game.guest(), game.gameId(), d.varn(game), "DEFENSIVE", UUID.randomUUID()),
				"$.alliance.id");
		world.endTurn(game, game.guest(), 2);
		assertThat(status(api.alliance(game.host(), game.gameId(), second, "accept", UUID.randomUUID()))).isEqualTo(200);

		String varn = world.place(game, "varn", "elmsford", "INFANTRY", 1000);
		assertThat(code(move(game, game.host(), varn, "sablewater"))).isEqualTo("422 NO_MILITARY_ACCESS");
		MvcResult overview = api.diplomacy(game.host(), game.gameId());
		assertThat(ApiDriver.<List<String>>read(overview, "$.nations[?(@.you == false)].status")).containsExactly("ALLIED");
		assertThat(ApiDriver.<List<String>>read(overview, "$.nations[?(@.you == false)].allianceKind"))
			.containsExactly("DEFENSIVE");
	}

	@Test
	void anUnansweredAllianceProposalExpires() throws Exception {
		Lobby game = world.startedGame();
		String id = read(api.proposeAlliance(game.host(), game.gameId(), d.ostra(game), "NORMAL", UUID.randomUUID()),
				"$.alliance.id");
		for (int round = 1; round <= 3; round++) {
			world.completeRound(game, round);
		}
		assertThat(d.allianceStatus(id)).isEqualTo("PROPOSED");
		world.completeRound(game, 4);
		assertThat(d.allianceStatus(id)).isEqualTo("EXPIRED");
	}

	// --- peace ----------------------------------------------------------------------------------

	@Test
	void acceptingPeaceSettlesEveryTermInOneTransaction() throws Exception {
		Lobby game = world.startedGame();
		String varn = d.varn(game);
		String ostra = d.ostra(game);
		String warId = d.declareWar(game, game.host(), ostra);
		String occupier = world.place(game, "varn", "marrowfield", "INFANTRY", 1000);
		String raider = world.place(game, "varn", "ostmark", "INFANTRY", 1000);
		String besieger = world.place(game, "ostra", "elmsford", "INFANTRY", 1000);
		world.completeRound(game, 1);
		assertThat(world.ownerAndController(game, "marrowfield")).containsExactly("ostra", "varn");
		assertThat(world.ownerAndController(game, "ostmark")).containsExactly("ostra", "varn");
		assertThat(world.sieges(game)).extracting(s -> s.get("status")).containsExactly("ACTIVE");

		String saltreach = api.cityId(game.host(), game.gameId(), "saltreach");
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		String proposal = d.proposePeace(game, game.host(), warId,
				terms(term("PROVINCE", ostra, varn, "\"provinceKey\":\"marrowfield\""),
						term("GOLD", ostra, varn, "\"amountMg\":20000"), term("TRIBUTE", ostra, varn, ""),
						term("POPULATION", ostra, varn, "\"sourceCityId\":\"" + saltreach + "\",\"destinationCityId\":\""
								+ varnhold + "\",\"people\":1000")));
		world.endTurn(game, game.host(), 3);

		MvcResult incoming = api.peaceProposal(game.guest(), game.gameId(), proposal);
		assertThat((Boolean) read(incoming, "$.incoming")).isTrue();
		assertThat(ApiDriver.<List<String>>read(incoming, "$.violations")).isEmpty();
		assertThat(ApiDriver.<List<Integer>>read(incoming, "$.terms[?(@.type == 'TRIBUTE')].tributeRounds")).containsExactly(10);
		long varnGold = world.treasury(game, "varn");
		long ostraGold = world.treasury(game, "ostra");
		long people = d.people(game);
		int salt = world.population(game, "saltreach");
		int capital = world.population(game, "varnhold");

		MvcResult accepted = d.acceptPeace(game, game.guest(), proposal);

		assertThat(status(accepted)).as(body(accepted)).isEqualTo(200);
		assertThat((String) read(accepted, "$.war.status")).isEqualTo("ENDED");
		assertThat(world.ownerAndController(game, "marrowfield")).containsExactly("varn", "varn");
		assertThat(world.ownerAndController(game, "ostmark")).as("occupation ends with the war")
			.containsExactly("ostra", "ostra");
		assertThat(ApiDriver.<List<String>>read(accepted, "$.controlReturned")).containsExactly("ostmark");
		assertThat(world.treasury(game, "ostra")).isEqualTo(ostraGold - 20_000);
		assertThat(world.treasury(game, "varn")).isEqualTo(varnGold + 20_000);
		assertThat(world.population(game, "saltreach")).isEqualTo(salt - 1000);
		assertThat(world.population(game, "varnhold")).isEqualTo(capital + 1000);
		assertThat(d.people(game)).as("population conserved").isEqualTo(people);
		assertThat(world.provinceOf(occupier)).isEqualTo("marrowfield");
		assertThat(world.provinceOf(raider)).as("nearest Varn province").isEqualTo("greyspire");
		assertThat(world.provinceOf(besieger)).isEqualTo("sablewater");
		assertThat(world.manpower(raider)).isEqualTo(1000);
		assertThat(world.inconsistentCohorts(game)).isEmpty();
		assertThat(world.sieges(game)).extracting(s -> s.get("status")).containsExactly("LIFTED");
		assertThat(d.proposalStatus(proposal)).isEqualTo("ACCEPTED");
		assertThat(world.count("game.peace_treaty", game)).isEqualTo(1);
		assertThat(world.count("game.truce", game)).isEqualTo(1);
		Map<String, Object> tribute = d.tribute(game);
		assertThat(tribute).containsEntry("status", "ACTIVE").containsEntry("rate_bps", 1000)
			.containsEntry("rounds_total", 10).containsEntry("first_round", 2);
		for (String kind : List.of("PEACE_SIGNED", "PROVINCE_CEDED", "REPARATIONS_PAID", "POPULATION_TRANSFERRED",
				"CONTROL_RETURNED", "FORCES_WITHDRAWN")) {
			assertThat(d.events(game, kind)).as(kind).isEqualTo(1);
		}

		// The truce forbids a new war; former enemies have no access to each other's land.
		assertThat(code(api.declareWar(game.guest(), game.gameId(), varn, UUID.randomUUID())))
			.isEqualTo("409 TRUCE_IN_EFFECT");
		assertThat(code(move(game, game.guest(), besieger, "elmsford"))).isEqualTo("422 NO_MILITARY_ACCESS");

		// Round 2 ends: the first tribute payment, exactly what leaves Ostra arrives in Varn.
		world.endTurn(game, game.guest(), 4);
		List<Map<String, Object>> payments = d.tributePayments(game);
		assertThat(payments).hasSize(1);
		long gross = (Long) d.report(game, "ostra", 2).get("gross_income_mg");
		long due = gross * 1000 / 10_000;
		long paid = (Long) payments.getFirst().get("paid_mg");
		assertThat(payments.getFirst()).containsEntry("due_mg", due).containsEntry("gross_income_mg", gross);
		assertThat(paid).isEqualTo(Math.min(due, (Long) d.report(game, "ostra", 2).get("treasury_after_mg")));
		assertThat(world.treasury(game, "ostra")).isEqualTo((Long) d.report(game, "ostra", 2).get("treasury_after_mg") - paid);
		assertThat(world.treasury(game, "varn")).isEqualTo((Long) d.report(game, "varn", 2).get("treasury_after_mg") + paid);
	}

	@Test
	void acceptanceRevalidatesTheTermsAndAnInvalidTreatyChangesNothing() throws Exception {
		Lobby game = world.startedGame();
		String warId = d.declareWar(game, game.host(), d.ostra(game));
		String proposal = d.proposePeace(game, game.host(), warId,
				terms(term("GOLD", d.ostra(game), d.varn(game), "\"amountMg\":40000"),
						term("PROVINCE", d.ostra(game), d.varn(game), "\"provinceKey\":\"saltreach\"")));
		world.endTurn(game, game.host(), 1);
		world.setTreasury(game, "ostra", 1_000);
		long varnGold = world.treasury(game, "varn");

		MvcResult refused = d.acceptPeace(game, game.guest(), proposal);

		assertThat(code(refused)).isEqualTo("409 PEACE_TERMS_NO_LONGER_VALID");
		assertThat((String) read(refused, "$.detail")).contains("INSUFFICIENT_GOLD");
		assertThat(d.proposalStatus(proposal)).isEqualTo("PENDING");
		assertThat(d.wars(game, "ACTIVE")).isEqualTo(1);
		assertThat(world.ownerAndController(game, "saltreach")).containsExactly("ostra", "ostra");
		assertThat(world.treasury(game, "ostra")).isEqualTo(1_000);
		assertThat(world.treasury(game, "varn")).isEqualTo(varnGold);
		assertThat(world.count("game.peace_treaty", game)).isZero();
		assertThat(d.events(game, "PEACE_SIGNED")).isZero();
		assertThat(world.commands(game, "ACCEPT_PEACE")).isZero();
		assertThat(ApiDriver.<List<String>>read(api.peaceProposal(game.guest(), game.gameId(), proposal), "$.violations"))
			.anyMatch(v -> v.contains("INSUFFICIENT_GOLD"));
	}

	@Test
	void invalidTermsAreRefusedWhenProposedAndCanBeCheckedBeforehand() throws Exception {
		Lobby game = world.startedGame();
		String warId = d.declareWar(game, game.host(), d.ostra(game));
		String capital = terms(term("PROVINCE", d.ostra(game), d.varn(game), "\"provinceKey\":\"ostrava\""));
		assertThat(code(api.proposePeace(game.host(), game.gameId(), warId, capital, UUID.randomUUID())))
			.isEqualTo("422 PEACE_TERMS_INVALID");
		MvcResult check = api.checkPeace(game.host(), game.gameId(), warId, capital);
		assertThat((Boolean) read(check, "$.valid")).isFalse();
		assertThat(ApiDriver.<List<String>>read(check, "$.violations[*].code")).containsExactly("CAPITAL_NOT_CEDABLE");
		assertThat(code(api.proposePeace(game.host(), game.gameId(), warId,
				terms(term("GOLD", d.ostra(game), d.varn(game), "")), UUID.randomUUID()))).isEqualTo("400 INVALID_TERM");
		assertThat(code(api.proposePeace(game.host(), game.gameId(), warId,
				terms(term("CONCEDE", d.ostra(game), d.varn(game), "")), UUID.randomUUID()))).isEqualTo("400 VALIDATION_FAILED");
		assertThat(world.count("game.peace_proposal", game)).isZero();
	}

	@Test
	void proposalsCanBeSupersededCancelledCounteredAndRejected() throws Exception {
		Lobby game = world.startedGame();
		String warId = d.declareWar(game, game.host(), d.ostra(game));
		String p1 = d.proposePeace(game, game.host(), warId, "[]");
		String p2 = d.proposePeace(game, game.host(), warId, "[]");
		assertThat(d.proposalStatus(p1)).isEqualTo("SUPERSEDED");
		assertThat(code(d.acceptPeace(game, game.host(), p2))).isEqualTo("403 NOT_PROPOSAL_RECIPIENT");
		assertThat(status(api.peace(game.host(), game.gameId(), p2, "cancel", UUID.randomUUID()))).isEqualTo(200);
		assertThat(d.proposalStatus(p2)).isEqualTo("CANCELLED");
		assertThat(code(api.peace(game.host(), game.gameId(), p2, "cancel", UUID.randomUUID())))
			.isEqualTo("409 PROPOSAL_NOT_PENDING");
		String p3 = d.proposePeace(game, game.host(), warId,
				terms(term("GOLD", d.ostra(game), d.varn(game), "\"amountMg\":5000")));
		world.endTurn(game, game.host(), 1);

		assertThat(code(api.peace(game.guest(), game.gameId(), p3, "cancel", UUID.randomUUID())))
			.isEqualTo("403 NOT_PROPOSAL_PROPOSER");
		MvcResult countered = api.counterPeace(game.guest(), game.gameId(), p3, "[]", UUID.randomUUID());
		assertThat(status(countered)).as(body(countered)).isEqualTo(201);
		String p4 = read(countered, "$.proposal.id");
		assertThat((String) read(countered, "$.proposal.counterOfId")).isEqualTo(p3);
		assertThat(d.proposalStatus(p3)).isEqualTo("SUPERSEDED");
		world.endTurn(game, game.guest(), 2);

		assertThat(status(api.peace(game.host(), game.gameId(), p4, "reject", UUID.randomUUID()))).isEqualTo(200);
		assertThat(d.proposalStatus(p4)).isEqualTo("REJECTED");
		assertThat(d.wars(game, "ACTIVE")).isEqualTo(1);
		MvcResult mine = api.peaceProposals(game.host(), game.gameId());
		assertThat(ApiDriver.<List<String>>read(mine, "$[*].status"))
			.containsExactlyInAnyOrder("SUPERSEDED", "CANCELLED", "SUPERSEDED", "REJECTED");
	}

	@Test
	void anUnansweredPeaceProposalExpires() throws Exception {
		Lobby game = world.startedGame();
		String warId = d.declareWar(game, game.host(), d.ostra(game));
		String proposal = d.proposePeace(game, game.host(), warId, "[]");
		for (int round = 1; round <= 3; round++) {
			world.completeRound(game, round);
		}
		assertThat(d.proposalStatus(proposal)).isEqualTo("PENDING");
		world.completeRound(game, 4);
		assertThat(d.proposalStatus(proposal)).isEqualTo("EXPIRED");
		assertThat(code(d.acceptPeace(game, game.guest(), proposal))).isEqualTo("409 NOT_ACTIVE_PLAYER");
	}

	@Test
	void simultaneousAcceptancesSettleTheTreatyOnce() throws Exception {
		Lobby game = world.startedGame();
		String warId = d.declareWar(game, game.host(), d.ostra(game));
		String proposal = d.proposePeace(game, game.host(), warId,
				terms(term("GOLD", d.ostra(game), d.varn(game), "\"amountMg\":10000")));
		world.endTurn(game, game.host(), 1);
		long ostraGold = world.treasury(game, "ostra");

		List<Integer> statuses = Concurrently.run(4, i -> status(d.acceptPeace(game, game.guest(), proposal)));

		assertThat(statuses).containsOnlyOnce(200);
		assertThat(statuses).filteredOn(s -> s != 200).containsOnly(409);
		assertThat(world.count("game.peace_treaty", game)).isEqualTo(1);
		assertThat(world.treasury(game, "ostra")).isEqualTo(ostraGold - 10_000);
		assertThat(d.events(game, "REPARATIONS_PAID")).isEqualTo(1);
	}

	@Test
	void aRetriedAcceptanceReplaysTheTreatyWithoutPayingTwice() throws Exception {
		Lobby game = world.startedGame();
		String warId = d.declareWar(game, game.host(), d.ostra(game));
		String proposal = d.proposePeace(game, game.host(), warId,
				terms(term("GOLD", d.ostra(game), d.varn(game), "\"amountMg\":10000")));
		world.endTurn(game, game.host(), 1);
		long ostraGold = world.treasury(game, "ostra");
		UUID key = UUID.randomUUID();
		MvcResult first = api.peace(game.guest(), game.gameId(), proposal, "accept", key);
		MvcResult retry = api.peace(game.guest(), game.gameId(), proposal, "accept", key);
		assertThat(status(retry)).isEqualTo(200);
		assertThat((String) read(retry, "$.treaty.id")).isEqualTo(read(first, "$.treaty.id"));
		assertThat(world.treasury(game, "ostra")).isEqualTo(ostraGold - 10_000);
	}

	// --- tribute and truce ----------------------------------------------------------------------

	@Test
	void tributeIsChargedOncePerRoundForTenRoundsAndShortfallsAreForgiven() throws Exception {
		Lobby game = world.startedGame();
		String warId = d.declareWar(game, game.host(), d.ostra(game));
		String proposal = d.proposePeace(game, game.host(), warId, terms(term("TRIBUTE", d.ostra(game), d.varn(game), "")));
		world.endTurn(game, game.host(), 1);
		assertThat(status(d.acceptPeace(game, game.guest(), proposal))).isEqualTo(200);
		world.endTurn(game, game.guest(), 2);

		// Round 2: Ostra cannot pay — huge upkeep empties its treasury at settlement.
		world.setTreasury(game, "ostra", 0);
		List<String> army = new ArrayList<>();
		for (int i = 0; i < 120; i++) {
			army.add(world.place(game, "ostra", "ostrava", "INFANTRY", 1000));
		}
		world.completeRound(game, 2);
		jdbc.update("DELETE FROM game.battalion_cohort WHERE battalion_id::text = ANY (?)",
				(Object) army.toArray(new String[0]));
		jdbc.update("DELETE FROM game.battalion WHERE id::text = ANY (?)", (Object) army.toArray(new String[0]));
		for (int round = 3; round <= 12; round++) {
			world.completeRound(game, round);
		}

		List<Map<String, Object>> payments = d.tributePayments(game);
		assertThat(payments).extracting(p -> p.get("round_number")).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		assertThat(payments.get(1).get("paid_mg")).as("shortfall: nothing to pay, nothing owed later").isEqualTo(0L);
		assertThat((Long) payments.get(1).get("due_mg")).isPositive();
		for (Map<String, Object> p : payments) {
			int round = (Integer) p.get("round_number");
			long due = (Long) d.report(game, "ostra", round).get("gross_income_mg") / 10;
			assertThat(p.get("due_mg")).as("round " + round).isEqualTo(due);
			assertThat((Long) p.get("paid_mg")).isEqualTo(Math.min(due, (Long) d.report(game, "ostra", round).get("treasury_after_mg")));
		}
		Map<String, Object> tribute = d.tribute(game);
		assertThat(tribute).containsEntry("status", "COMPLETED").containsEntry("rounds_charged", 10);
		assertThat(((Number) tribute.get("total_paid_mg")).longValue())
			.isEqualTo(payments.stream().mapToLong(p -> (Long) p.get("paid_mg")).sum());
		assertThat(d.events(game, "TRIBUTE_COMPLETED")).isEqualTo(1);
		MvcResult tributes = api.tributes(game.guest(), game.gameId());
		assertThat(ApiDriver.<List<Object>>read(tributes, "$[0].payments")).hasSize(10);
	}

	@Test
	void theTruceEndsAndARenewedWarCancelsARunningTribute() throws Exception {
		Lobby game = world.startedGame();
		String warId = d.declareWar(game, game.host(), d.ostra(game));
		String proposal = d.proposePeace(game, game.host(), warId, terms(term("TRIBUTE", d.ostra(game), d.varn(game), "")));
		world.endTurn(game, game.host(), 1);
		assertThat(status(d.acceptPeace(game, game.guest(), proposal))).isEqualTo(200);
		assertThat((Integer) read(api.treaties(game.host(), game.gameId()), "$[0].truceUntilRound")).isEqualTo(11);
		world.endTurn(game, game.guest(), 2);
		assertThat(code(api.declareWar(game.host(), game.gameId(), d.ostra(game), UUID.randomUUID())))
			.isEqualTo("409 TRUCE_IN_EFFECT");

		// A shorter truce stands in for a later ruleset: the war resumes while tribute still runs.
		jdbc.update("UPDATE game.truce SET until_round = 1 WHERE game_id = ?::uuid", game.gameId());
		assertThat(status(api.declareWar(game.host(), game.gameId(), d.ostra(game), UUID.randomUUID()))).isEqualTo(201);
		assertThat(d.tribute(game)).containsEntry("status", "CANCELLED").containsEntry("end_reason", "WAR_RENEWED");
		world.completeRound(game, 2);
		assertThat(d.tributePayments(game)).hasSize(1);
	}

	// --- access control -------------------------------------------------------------------------

	@Test
	void diplomacyIsPrivateToMembersAndNeedsAuthentication() throws Exception {
		Lobby game = world.startedGame();
		String warId = d.declareWar(game, game.host(), d.ostra(game));
		String proposal = d.proposePeace(game, game.host(), warId, "[]");
		String stranger = ApiDriver.newPlayer();
		String base = "/api/v1/games/" + game.gameId();
		for (String path : List.of("/diplomacy", "/diplomacy/wars/" + warId, "/diplomacy/peace-proposals",
				"/diplomacy/peace-proposals/" + proposal, "/diplomacy/treaties", "/diplomacy/tributes",
				"/diplomacy/events", "/result")) {
			assertThat(status(api.anonymous(base + path))).as(path).isEqualTo(401);
		}
		assertThat(code(api.diplomacy(stranger, game.gameId()))).isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.peaceProposal(stranger, game.gameId(), proposal))).isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.declareWar(stranger, game.gameId(), d.varn(game), UUID.randomUUID())))
			.isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(d.acceptPeace(game, stranger, proposal))).isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.peaceProposal(game.guest(), game.gameId(), UUID.randomUUID().toString())))
			.isEqualTo("404 PEACE_PROPOSAL_NOT_FOUND");
		assertThat(code(api.result(game.host(), game.gameId()))).isEqualTo("409 GAME_NOT_FINISHED");
	}

}
