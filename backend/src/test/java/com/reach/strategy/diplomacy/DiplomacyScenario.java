package com.reach.strategy.diplomacy;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.military.MilitaryScenario;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Diplomacy commands and database probes for the Phase 9 integration tests (ruleset v5). Host =
 * Kingdom of Berlardfaul (seat 0, odd turns), guest = Kingdom of Antara (even turns). Every new v5 game
 * starts at peace.
 */
final class DiplomacyScenario {

	final ApiDriver api;
	final JdbcTemplate jdbc;
	final MilitaryScenario world;

	DiplomacyScenario(ApiDriver api, JdbcTemplate jdbc) {
		this.api = api;
		this.jdbc = jdbc;
		this.world = new MilitaryScenario(api, jdbc);
	}

	String varn(Lobby game) {
		return world.nationId(game, "varn");
	}

	String ostra(Lobby game) {
		return world.nationId(game, "ostra");
	}

	/** Declares war and returns the war id. */
	String declareWar(Lobby game, String token, String targetNationId) throws Exception {
		MvcResult result = api.declareWar(token, game.gameId(), targetNationId, UUID.randomUUID());
		assertThat(status(result)).as(ApiDriver.body(result)).isEqualTo(201);
		return read(result, "$.war.id");
	}

	String proposePeace(Lobby game, String token, String warId, String terms) throws Exception {
		MvcResult result = api.proposePeace(token, game.gameId(), warId, terms, UUID.randomUUID());
		assertThat(status(result)).as(ApiDriver.body(result)).isEqualTo(201);
		return read(result, "$.proposal.id");
	}

	MvcResult acceptPeace(Lobby game, String token, String proposalId) throws Exception {
		return api.peace(token, game.gameId(), proposalId, "accept", UUID.randomUUID());
	}

	static String term(String type, String from, String to, String extra) {
		return "{\"type\":\"" + type + "\",\"fromNationId\":\"" + from + "\",\"toNationId\":\"" + to + "\""
				+ (extra.isEmpty() ? "" : "," + extra) + "}";
	}

	static String terms(String... terms) {
		return "[" + String.join(",", terms) + "]";
	}

	/** Jumps the turn counter to the first turn of a round (a stand-in for many uneventful rounds). */
	void jumpToRound(Lobby game, int round) {
		jdbc.update("UPDATE game.game SET current_round = ?, turn_number = ?, active_seat = 0 WHERE id = ?::uuid", round,
				2 * round - 1, game.gameId());
	}

	int count(String sql, Object... args) {
		Integer n = jdbc.queryForObject(sql, Integer.class, args);
		return n == null ? 0 : n;
	}

	int wars(Lobby game, String status) {
		return count("SELECT count(*) FROM game.war WHERE game_id = ?::uuid AND status = ?", game.gameId(), status);
	}

	int events(Lobby game, String kind) {
		return count("SELECT count(*) FROM game.diplomacy_event WHERE game_id = ?::uuid AND kind = ?", game.gameId(), kind);
	}

	String proposalStatus(String proposalId) {
		return jdbc.queryForObject("SELECT status FROM game.peace_proposal WHERE id = ?::uuid", String.class, proposalId);
	}

	String allianceStatus(String allianceId) {
		return jdbc.queryForObject("SELECT status FROM game.alliance WHERE id = ?::uuid", String.class, allianceId);
	}

	List<Map<String, Object>> tributePayments(Lobby game) {
		return jdbc.queryForList("""
				SELECT round_number, gross_income_mg, due_mg, paid_mg FROM game.tribute_payment WHERE game_id = ?::uuid
				ORDER BY round_number
				""", game.gameId());
	}

	Map<String, Object> tribute(Lobby game) {
		return jdbc.queryForList("SELECT * FROM game.tribute WHERE game_id = ?::uuid", game.gameId()).getFirst();
	}

	/** Gross income and treasury after settlement of one nation's round report. */
	Map<String, Object> report(Lobby game, String nationKey, int round) {
		return jdbc.queryForMap("""
				SELECT gross_income_mg, treasury_after_mg FROM game.economy_round_report
				WHERE game_id = ?::uuid AND nation_id = ?::uuid AND round_number = ?
				""", game.gameId(), world.nationId(game, nationKey), round);
	}

	/** Civilians in all cities plus soldiers in all battalions: conserved by every transfer. */
	long people(Lobby game) {
		return world.civilians(game) + world.totalManpower(game);
	}

	String gameStatus(Lobby game) {
		return jdbc.queryForObject("SELECT status FROM game.game WHERE id = ?::uuid", String.class, game.gameId());
	}

}
