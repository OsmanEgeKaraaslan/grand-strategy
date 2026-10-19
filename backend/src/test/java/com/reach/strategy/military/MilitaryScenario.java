package com.reach.strategy.military;

import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Game setup and database probes for the military integration tests. The host plays the Kingdom of
 * Varn (west, seat 0, moves first), the guest the Ostran Confederacy (east). Ruleset v2: each starts
 * with 3 infantry and 1 cavalry in its capital (Varnhold / Ostrava), raised from 12,000 civilians.
 * <p>
 * Direct inserts stand in for situations that would otherwise take several rounds of play (an
 * enemy battalion already standing in a province); they write exactly what the game would.
 */
public final class MilitaryScenario {

	private final ApiDriver api;
	private final JdbcTemplate jdbc;

	public MilitaryScenario(ApiDriver api, JdbcTemplate jdbc) {
		this.api = api;
		this.jdbc = jdbc;
	}

	public Lobby startedGame() throws Exception {
		Lobby lobby = api.readyLobby("Military " + UUID.randomUUID().toString().substring(0, 6));
		MvcResult view = api.lobby(lobby.host(), lobby.gameId());
		String varn = ApiDriver.<List<String>>read(view, "$.nations[?(@.key == 'varn')].id").getFirst();
		String ostra = ApiDriver.<List<String>>read(view, "$.nations[?(@.key == 'ostra')].id").getFirst();
		api.chooseNation(lobby.host(), lobby.gameId(), varn);
		api.chooseNation(lobby.guest(), lobby.gameId(), ostra);
		assertThat(status(api.start(lobby.host(), lobby.gameId()))).isEqualTo(200);
		return lobby;
	}

	public void endTurn(Lobby game, String token, int turn) throws Exception {
		assertThat(status(api.endTurn(token, game.gameId(), turn, UUID.randomUUID()))).isEqualTo(200);
	}

	public void completeRound(Lobby game, int round) throws Exception {
		int turn = 2 * round - 1;
		endTurn(game, game.host(), turn);
		endTurn(game, game.guest(), turn + 1);
	}

	public String nationId(Lobby game, String nationKey) {
		return jdbc.queryForObject("""
				SELECT n.id::text FROM game.nation n JOIN game.nation_template nt ON nt.id = n.nation_template_id
				WHERE n.game_id = ?::uuid AND nt.key = ?
				""", String.class, game.gameId(), nationKey);
	}

	/** Battalion ids of a nation in a province, in id order. */
	public List<String> battalions(Lobby game, String nationKey, String provinceKey) {
		return jdbc.queryForList("""
				SELECT b.id::text FROM game.battalion b
				JOIN game.province_state ps ON ps.id = b.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				WHERE b.game_id = ?::uuid AND b.nation_id = ?::uuid AND pt.key = ?
				ORDER BY b.id
				""", String.class, game.gameId(), nationId(game, nationKey), provinceKey);
	}

	public Map<String, Object> battalion(String battalionId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT b.manpower, b.unit_type, b.army_id::text AS army_id, b.last_moved_turn_number, pt.key AS province
				FROM game.battalion b
				JOIN game.province_state ps ON ps.id = b.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				WHERE b.id = ?::uuid
				""", battalionId);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	public int manpower(String battalionId) {
		return (Integer) battalion(battalionId).get("manpower");
	}

	public String provinceOf(String battalionId) {
		return (String) battalion(battalionId).get("province");
	}

	public long totalManpower(Lobby game) {
		Long total = jdbc.queryForObject("SELECT coalesce(sum(manpower), 0) FROM game.battalion WHERE game_id = ?::uuid",
				Long.class, game.gameId());
		return total == null ? 0 : total;
	}

	/** Places a ready battalion (and its single cohort) as if it had marched there in an earlier round. */
	public String place(Lobby game, String nationKey, String provinceKey, String unitType, int manpower) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO game.battalion (id, game_id, nation_id, unit_type, manpower, max_manpower, origin_city_id,
				    province_state_id, recruitment_cost_mg, recruited_round, recruited_turn_number, created_at)
				SELECT ?, ps.game_id, ?::uuid, ?, ?, 1000, c.id, ps.id, 0, 0, 0, now()
				FROM game.province_state ps
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				JOIN game.city c ON c.province_state_id = ps.id
				WHERE ps.game_id = ?::uuid AND pt.key = ?
				""", id, nationId(game, nationKey), unitType, manpower, game.gameId(), provinceKey);
		jdbc.update("""
				INSERT INTO game.battalion_cohort (id, game_id, battalion_id, origin_city_id, soldiers)
				SELECT gen_random_uuid(), game_id, id, origin_city_id, manpower FROM game.battalion WHERE id = ?
				""", id);
		return id.toString();
	}

	/** Battle damage stand-in: sets manpower and shrinks a single-origin battalion's cohort to match. */
	public void damage(String battalionId, int manpower) {
		jdbc.update("UPDATE game.battalion SET manpower = ? WHERE id = ?::uuid", manpower, battalionId);
		jdbc.update("UPDATE game.battalion_cohort SET soldiers = ? WHERE battalion_id = ?::uuid", manpower, battalionId);
	}

	/** Cohort soldiers by origin province key. */
	public Map<String, Integer> cohorts(String battalionId) {
		Map<String, Integer> result = new java.util.TreeMap<>();
		jdbc.query("""
				SELECT pt.key, bc.soldiers FROM game.battalion_cohort bc
				JOIN game.city c ON c.id = bc.origin_city_id
				JOIN game.province_state ps ON ps.id = c.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				WHERE bc.battalion_id = ?::uuid
				""", rs -> {
			result.put(rs.getString("key"), rs.getInt("soldiers"));
		}, battalionId);
		return result;
	}

	/** Battalions whose cohorts do not add up to their manpower (must always be empty). */
	public List<String> inconsistentCohorts(Lobby game) {
		return jdbc.queryForList("""
				SELECT b.id::text FROM game.battalion b
				LEFT JOIN game.battalion_cohort bc ON bc.battalion_id = b.id
				WHERE b.game_id = ?::uuid
				GROUP BY b.id, b.manpower
				HAVING coalesce(sum(bc.soldiers), 0) <> b.manpower
				""", String.class, game.gameId());
	}

	/** Inserts an ACTIVE Tier 1 workshop in a province's city; returns its id. */
	public String workshop(Lobby game, String provinceKey) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO game.building (id, game_id, city_id, type, level, status, ordered_round, cost_paid_mg, created_at)
				SELECT ?, c.game_id, c.id, 'WORKSHOP', 1, 'ACTIVE', 0, 0, now()
				FROM game.city c JOIN game.province_state ps ON ps.id = c.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				WHERE c.game_id = ?::uuid AND pt.key = ?
				""", id, game.gameId(), provinceKey);
		return id.toString();
	}

	public String buildingStatus(String buildingId) {
		return jdbc.queryForObject("SELECT status FROM game.building WHERE id = ?::uuid", String.class, buildingId);
	}

	/** level, status, integrity, project of a province's fortress; null if it has none. */
	public Map<String, Object> fortress(Lobby game, String provinceKey) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT f.level, f.status, f.integrity, f.max_integrity, f.project FROM game.fortification f
				JOIN game.city c ON c.id = f.city_id
				JOIN game.province_state ps ON ps.id = c.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				WHERE f.game_id = ?::uuid AND pt.key = ?
				""", game.gameId(), provinceKey);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	public void damageFortress(Lobby game, String provinceKey) {
		jdbc.update("""
				UPDATE game.fortification f SET status = 'DAMAGED', integrity = 0
				FROM game.city c, game.province_state ps, game.province_template pt
				WHERE f.city_id = c.id AND c.province_state_id = ps.id AND ps.province_template_id = pt.id
				  AND f.game_id = ?::uuid AND pt.key = ?
				""", game.gameId(), provinceKey);
	}

	/** Sieges of a game as maps (status, progress, required_progress, ...), oldest first. */
	public List<Map<String, Object>> sieges(Lobby game) {
		return jdbc.queryForList(
				"SELECT * FROM game.siege WHERE game_id = ?::uuid ORDER BY created_at, id", game.gameId());
	}

	/** Sets a nation's research pools (stand-in for rounds of institution output). */
	public void setResearchPoints(Lobby game, String nationKey, int military, int civilian, int doctrine) {
		jdbc.update("""
				UPDATE game.nation_research SET military_points = ?, civilian_points = ?, doctrine_points = ?
				WHERE nation_id = ?::uuid
				""", military, civilian, doctrine, nationId(game, nationKey));
	}

	public void setTechnology(Lobby game, String nationKey, int militaryLevel, int civilianLevel) {
		jdbc.update("UPDATE game.nation_research SET military_level = ?, civilian_level = ? WHERE nation_id = ?::uuid",
				militaryLevel, civilianLevel, nationId(game, nationKey));
	}

	public Map<String, Object> research(Lobby game, String nationKey) {
		return jdbc.queryForMap("SELECT * FROM game.nation_research WHERE nation_id = ?::uuid", nationId(game, nationKey));
	}

	/** An ACTIVE institution in a province's city, as if built in an earlier round. */
	public String activeInstitution(Lobby game, String provinceKey, String type) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO game.institution (id, game_id, city_id, type, status, ordered_round, completes_after_round,
				    cost_paid_mg, ruleset_version, created_at)
				SELECT ?, c.game_id, c.id, ?, 'ACTIVE', 1, 1, 0, g.ruleset_version, now()
				FROM game.city c JOIN game.province_state ps ON ps.id = c.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				JOIN game.game g ON g.id = c.game_id
				WHERE c.game_id = ?::uuid AND pt.key = ?
				""", id, type, game.gameId(), provinceKey);
		return id.toString();
	}

	/** The id of the first workshop of a province's city. */
	public String firstWorkshop(Lobby game, String provinceKey) {
		return jdbc.queryForObject("""
				SELECT b.id::text FROM game.building b JOIN game.city c ON c.id = b.city_id
				JOIN game.province_state ps ON ps.id = c.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				WHERE b.game_id = ?::uuid AND pt.key = ? ORDER BY b.id LIMIT 1
				""", String.class, game.gameId(), provinceKey);
	}

	public Map<String, Object> building(String buildingId) {
		return jdbc.queryForMap("SELECT * FROM game.building WHERE id = ?::uuid", buildingId);
	}

	public long civilians(Lobby game) {
		Long total = jdbc.queryForObject("SELECT sum(population) FROM game.city WHERE game_id = ?::uuid", Long.class,
				game.gameId());
		return total == null ? 0 : total;
	}

	public void setArrears(Lobby game, String nationKey, boolean inArrears) {
		jdbc.update("UPDATE game.nation_economy SET in_arrears = ? WHERE nation_id = ?::uuid", inArrears,
				nationId(game, nationKey));
	}

	public int population(Lobby game, String provinceKey) {
		return jdbc.queryForObject("""
				SELECT c.population FROM game.city c
				JOIN game.province_state ps ON ps.id = c.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				WHERE c.game_id = ?::uuid AND pt.key = ?
				""", Integer.class, game.gameId(), provinceKey);
	}

	public void setPopulation(Lobby game, String provinceKey, int population) {
		jdbc.update("""
				UPDATE game.city c SET population = ?
				FROM game.province_state ps, game.province_template pt
				WHERE c.province_state_id = ps.id AND ps.province_template_id = pt.id
				  AND c.game_id = ?::uuid AND pt.key = ?
				""", population, game.gameId(), provinceKey);
	}

	public long treasury(Lobby game, String nationKey) {
		return jdbc.queryForObject("SELECT treasury_mg FROM game.nation_economy WHERE nation_id = ?::uuid", Long.class,
				nationId(game, nationKey));
	}

	public void setTreasury(Lobby game, String nationKey, long treasuryMg) {
		jdbc.update("UPDATE game.nation_economy SET treasury_mg = ? WHERE nation_id = ?::uuid", treasuryMg,
				nationId(game, nationKey));
	}

	/** Owner and controller nation keys of a province. */
	public List<String> ownerAndController(Lobby game, String provinceKey) {
		return jdbc.queryForObject("""
				SELECT ont.key AS owner, cnt.key AS controller
				FROM game.province_state ps
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				JOIN game.nation o ON o.id = ps.owner_nation_id JOIN game.nation_template ont ON ont.id = o.nation_template_id
				JOIN game.nation c ON c.id = ps.controller_nation_id JOIN game.nation_template cnt ON cnt.id = c.nation_template_id
				WHERE ps.game_id = ?::uuid AND pt.key = ?
				""", (rs, row) -> List.of(rs.getString("owner"), rs.getString("controller")), game.gameId(), provinceKey);
	}

	public void occupy(Lobby game, String provinceKey, String controllerKey) {
		jdbc.update("""
				UPDATE game.province_state ps SET controller_nation_id = ?::uuid
				FROM game.province_template pt
				WHERE ps.province_template_id = pt.id AND pt.key = ? AND ps.game_id = ?::uuid
				""", nationId(game, controllerKey), provinceKey, game.gameId());
	}

	public int count(String table, Lobby game) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE game_id = ?::uuid", Integer.class,
				game.gameId());
	}

	public int commands(Lobby game, String type) {
		return jdbc.queryForObject("SELECT count(*) FROM game.game_command WHERE game_id = ?::uuid AND command_type = ?",
				Integer.class, game.gameId(), type);
	}

	public long stateVersion(Lobby game) {
		return jdbc.queryForObject("SELECT state_version FROM game.game WHERE id = ?::uuid", Long.class, game.gameId());
	}

}
