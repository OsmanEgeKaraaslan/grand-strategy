package com.reach.strategy.campaign;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.reach.strategy.military.MilitaryScenario;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Multi-human games for the Phase 10 integration tests (ruleset v6). {@link #start} creates a lobby
 * for N humans on the given map, seats them in order, lets seat i choose {@code nationKeys[i]} and
 * starts; every other nation of the map is NPC-controlled.
 */
public final class CampaignScenario {

	public final ApiDriver api;
	public final JdbcTemplate jdbc;
	public final MilitaryScenario world;

	public CampaignScenario(ApiDriver api, JdbcTemplate jdbc) {
		this.api = api;
		this.jdbc = jdbc;
		this.world = new MilitaryScenario(api, jdbc);
	}

	/** A started game; {@code tokens.get(seat)} is the player of {@code nationKeys.get(seat)}. */
	public record Game(String gameId, List<String> tokens, List<String> nationKeys) {

		/** The two-player view used by the shared military probes (host = seat 0, guest = seat 1). */
		public Lobby lobby() {
			return new Lobby(gameId, tokens.get(0), tokens.size() > 1 ? tokens.get(1) : null, null, null, null);
		}

		public String token(String nationKey) {
			return tokens.get(nationKeys.indexOf(nationKey));
		}

	}

	public Game start(String mapKey, List<String> nationKeys) throws Exception {
		List<String> tokens = new ArrayList<>();
		for (int i = 0; i < nationKeys.size(); i++) {
			tokens.add(ApiDriver.newPlayer());
		}
		MvcResult created = api.createGame(tokens.getFirst(), "Campaign " + UUID.randomUUID().toString().substring(0, 6),
				nationKeys.size(), mapKey);
		assertThat(status(created)).as(ApiDriver.body(created)).isEqualTo(201);
		String gameId = read(created, "$.id");
		String invite = read(created, "$.inviteCode");
		for (int seat = 1; seat < tokens.size(); seat++) {
			assertThat(status(api.join(tokens.get(seat), invite))).isEqualTo(200);
		}
		for (int seat = 0; seat < tokens.size(); seat++) {
			String nationId = ApiDriver.<List<String>>read(created, "$.nations[?(@.key == '" + nationKeys.get(seat) + "')].id")
				.getFirst();
			MvcResult chosen = api.chooseNation(tokens.get(seat), gameId, nationId);
			assertThat(status(chosen)).as(ApiDriver.body(chosen)).isEqualTo(200);
		}
		MvcResult started = api.start(tokens.getFirst(), gameId);
		assertThat(status(started)).as(ApiDriver.body(started)).isEqualTo(200);
		return new Game(gameId, List.copyOf(tokens), List.copyOf(nationKeys));
	}

	/** The token of the active player and the current turn number. */
	public int turn(Game game) {
		return jdbc.queryForObject("SELECT turn_number FROM game.game WHERE id = ?::uuid", Integer.class, game.gameId());
	}

	public int round(Game game) {
		return jdbc.queryForObject("SELECT current_round FROM game.game WHERE id = ?::uuid", Integer.class, game.gameId());
	}

	public String activeToken(Game game) {
		int seat = jdbc.queryForObject("SELECT active_seat FROM game.game WHERE id = ?::uuid", Integer.class, game.gameId());
		return game.tokens().get(seat);
	}

	/** Ends the active player's turn. */
	public MvcResult endTurn(Game game) throws Exception {
		return api.endTurn(activeToken(game), game.gameId(), turn(game), UUID.randomUUID());
	}

	/** Ends turns until the round has advanced. */
	public void completeRound(Game game) throws Exception {
		int round = round(game);
		while (round(game) == round) {
			MvcResult ended = endTurn(game);
			assertThat(status(ended)).as(ApiDriver.body(ended)).isEqualTo(200);
		}
	}

	/** Ends turns until it is {@code nationKey}'s turn (at most one full round of turns). */
	public String turnOf(Game game, String nationKey) throws Exception {
		String token = game.token(nationKey);
		for (int i = 0; i <= game.tokens().size() && !activeToken(game).equals(token); i++) {
			MvcResult ended = endTurn(game);
			assertThat(status(ended)).as(ApiDriver.body(ended)).isEqualTo(200);
		}
		assertThat(activeToken(game)).isEqualTo(token);
		return token;
	}

	/** Declares war (on the declarer's turn) and returns the war id. */
	public String declareWar(Game game, String attackerKey, String targetKey) throws Exception {
		MvcResult war = api.declareWar(turnOf(game, attackerKey), game.gameId(), nationId(game, targetKey),
				UUID.randomUUID());
		assertThat(status(war)).as(ApiDriver.body(war)).isEqualTo(201);
		return read(war, "$.war.id");
	}

	/** A proposed and accepted alliance between two human nations; returns its id. */
	public String alliance(Game game, String proposerKey, String partnerKey, String kind) throws Exception {
		MvcResult proposed = api.proposeAlliance(turnOf(game, proposerKey), game.gameId(), nationId(game, partnerKey),
				kind, UUID.randomUUID());
		assertThat(status(proposed)).as(ApiDriver.body(proposed)).isEqualTo(201);
		String allianceId = read(proposed, "$.alliance.id");
		MvcResult accepted = api.alliance(turnOf(game, partnerKey), game.gameId(), allianceId, "accept",
				UUID.randomUUID());
		assertThat(status(accepted)).as(ApiDriver.body(accepted)).isEqualTo(200);
		return allianceId;
	}

	/** The defending leader calls an ally, who honours it on its turn; returns the call id. */
	public String callAndHonour(Game game, String warId, String callerKey, String allyKey) throws Exception {
		MvcResult called = api.callToArms(turnOf(game, callerKey), game.gameId(), warId, nationId(game, allyKey),
				UUID.randomUUID());
		assertThat(status(called)).as(ApiDriver.body(called)).isEqualTo(201);
		String callId = read(called, "$.call.id");
		MvcResult honoured = api.answerCall(turnOf(game, allyKey), game.gameId(), callId, true, UUID.randomUUID());
		assertThat(status(honoured)).as(ApiDriver.body(honoured)).isEqualTo(200);
		return callId;
	}

	public String place(Game game, String nationKey, String provinceKey, String unitType, int manpower) {
		return world.place(game.lobby(), nationKey, provinceKey, unitType, manpower);
	}

	public boolean paused(Game game) {
		return jdbc.queryForObject("SELECT paused_conference_id IS NOT NULL FROM game.game WHERE id = ?::uuid",
				Boolean.class, game.gameId());
	}

	public String nationId(Game game, String nationKey) {
		return world.nationId(game.lobby(), nationKey);
	}

	public long treasury(Game game, String nationKey) {
		return world.treasury(game.lobby(), nationKey);
	}

	/**
	 * Battalions standing where their nation may not stand, by the movement access rule
	 * ({@code military.api.Hostility.Relations.mayEnter}, DIPLOMACY.md §3): allowed are the nation's own
	 * territory (owned or controlled), territory whose legal owner <em>or</em> controller it is at war
	 * with, and territory whose controller grants it access — in rulesets v6/v7 a normal alliance or
	 * fighting on the same side of an active war; a defensive pact grants none. Returns readable rows
	 * (nation, province, owner, controller) so a violation explains itself.
	 */
	public List<java.util.Map<String, Object>> battalionsWithoutAccess(Game game) {
		return jdbc.queryForList("""
				SELECT bt.key AS nation, pt.key AS province, ot.key AS owner, ct.key AS controller, b.id AS battalion
				FROM game.battalion b
				JOIN game.province_state ps ON ps.id = b.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				JOIN game.nation bn ON bn.id = b.nation_id JOIN game.nation_template bt ON bt.id = bn.nation_template_id
				JOIN game.nation onat ON onat.id = ps.owner_nation_id JOIN game.nation_template ot ON ot.id = onat.nation_template_id
				JOIN game.nation cn ON cn.id = ps.controller_nation_id JOIN game.nation_template ct ON ct.id = cn.nation_template_id
				WHERE b.game_id = ?::uuid
				  AND b.nation_id <> ps.owner_nation_id AND b.nation_id <> ps.controller_nation_id
				  -- at war with the owner or the controller
				  AND NOT EXISTS (SELECT 1 FROM game.war_front f WHERE f.game_id = b.game_id AND f.active
				      AND ((f.nation_a_id = b.nation_id AND f.nation_b_id IN (ps.owner_nation_id, ps.controller_nation_id))
				        OR (f.nation_b_id = b.nation_id AND f.nation_a_id IN (ps.owner_nation_id, ps.controller_nation_id))))
				  -- access from the controller: a normal alliance ...
				  AND NOT EXISTS (SELECT 1 FROM game.alliance a WHERE a.game_id = b.game_id AND a.status = 'ACTIVE'
				      AND a.kind = 'NORMAL'
				      AND ((a.nation_a_id = b.nation_id AND a.nation_b_id = ps.controller_nation_id)
				        OR (a.nation_b_id = b.nation_id AND a.nation_a_id = ps.controller_nation_id)))
				  -- ... or the same side of an active war, both still fighting
				  AND NOT EXISTS (SELECT 1 FROM game.war w
				      JOIN game.war_participant mine ON mine.war_id = w.id AND mine.nation_id = b.nation_id AND mine.left_round IS NULL
				      JOIN game.war_participant theirs ON theirs.war_id = w.id AND theirs.nation_id = ps.controller_nation_id
				          AND theirs.left_round IS NULL AND theirs.side = mine.side
				      WHERE w.game_id = b.game_id AND w.status = 'ACTIVE')
				ORDER BY bt.key, pt.key
				""", game.gameId());
	}

}
