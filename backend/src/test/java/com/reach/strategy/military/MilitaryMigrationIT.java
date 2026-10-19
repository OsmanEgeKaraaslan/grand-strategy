package com.reach.strategy.military;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.TestcontainersConfiguration;
import com.reach.strategy.support.LegacyGames;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V7 on a database holding a running Phase 4 game: nothing about it may change — no battalions
 * appear, no civilians are taken, treasuries and control stay — and it can use the new commands.
 */
@Testcontainers(disabledWithoutDocker = true)
class MilitaryMigrationIT {

	// One database per test: each starts from an empty schema at its own migration version.
	@Container
	final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

	@Test
	void runningGamesKeepTheirStateAndGetNoArmies() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword());
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		LegacyGames.flyway(dataSource, "5").migrate();
		UUID gameId = LegacyGames.insertRunningPhase3Game(jdbc);
		LegacyGames.flyway(dataSource, "6").migrate();
		List<Map<String, Object>> before = state(jdbc, gameId);

		LegacyGames.flyway(dataSource, "latest").migrate();

		assertThat(state(jdbc, gameId)).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.battalion WHERE game_id = ?", Integer.class, gameId))
			.isZero();
		assertThat(jdbc.queryForObject("SELECT ruleset_version FROM game.game WHERE id = ?", String.class, gameId))
			.as("keeps ruleset v1: no starting forces, no upkeep").isEqualTo("v1");
		UUID gamePlayer = jdbc.queryForObject("SELECT id FROM game.game_player WHERE game_id = ? AND seat_order = 1",
				UUID.class, gameId);
		jdbc.update("""
				INSERT INTO game.game_command (id, game_id, game_player_id, idempotency_key, command_type, turn_number,
				    created_at)
				VALUES (gen_random_uuid(), ?, ?, gen_random_uuid(), 'RECRUIT', 6, now())
				""", gameId, gamePlayer);
	}

	@Test
	void existingBattalionsGetOneCohortFromTheirOriginCity() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword());
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		LegacyGames.flyway(dataSource, "5").migrate();
		UUID gameId = LegacyGames.insertRunningPhase3Game(jdbc);
		LegacyGames.flyway(dataSource, "7").migrate();
		// A Phase 5 battalion that has already lost 350 men in battle.
		UUID battalion = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO game.battalion (id, game_id, nation_id, unit_type, manpower, max_manpower, origin_city_id,
				    province_state_id, recruitment_cost_mg, recruited_round, recruited_turn_number, created_at)
				SELECT ?, c.game_id, ps.owner_nation_id, 'INFANTRY', 650, 1000, c.id, ps.id, 10000, 1, 1, now()
				FROM game.city c JOIN game.province_state ps ON ps.id = c.province_state_id
				WHERE c.game_id = ? ORDER BY c.id LIMIT 1
				""", battalion, gameId);
		Integer populationBefore = jdbc.queryForObject("SELECT sum(population) FROM game.city WHERE game_id = ?",
				Integer.class, gameId);

		LegacyGames.flyway(dataSource, "latest").migrate();

		List<Map<String, Object>> cohorts = jdbc.queryForList("""
				SELECT bc.origin_city_id = b.origin_city_id AS same_origin, bc.soldiers
				FROM game.battalion_cohort bc JOIN game.battalion b ON b.id = bc.battalion_id
				WHERE bc.battalion_id = ?
				""", battalion);
		assertThat(cohorts).hasSize(1);
		assertThat(cohorts.getFirst().get("same_origin")).isEqualTo(true);
		assertThat(cohorts.getFirst().get("soldiers")).isEqualTo(650);
		assertThat(jdbc.queryForMap(
				"SELECT manpower, reinforced_this_turn, last_reinforced_turn_number FROM game.battalion WHERE id = ?",
				battalion))
			.containsEntry("manpower", 650)
			.containsEntry("reinforced_this_turn", 0)
			.containsEntry("last_reinforced_turn_number", null);
		assertThat(jdbc.queryForObject("SELECT sum(population) FROM game.city WHERE game_id = ?", Integer.class, gameId))
			.as("no civilians moved").isEqualTo(populationBefore);
	}

	@Test
	void runningGamesGetNoFortressesAndKeepTheirBuildingsUnderV9() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword());
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		LegacyGames.flyway(dataSource, "5").migrate();
		UUID gameId = LegacyGames.insertRunningPhase3Game(jdbc);
		LegacyGames.flyway(dataSource, "8").migrate();
		jdbc.update("UPDATE game.building SET status = 'DAMAGED' WHERE game_id = ? AND id = "
				+ "(SELECT id FROM game.building WHERE game_id = ? ORDER BY id LIMIT 1)", gameId, gameId);
		List<Map<String, Object>> buildingsBefore = jdbc.queryForList(
				"SELECT id, level, status, cost_paid_mg FROM game.building WHERE game_id = ? ORDER BY id", gameId);
		List<Map<String, Object>> before = state(jdbc, gameId);

		LegacyGames.flyway(dataSource, "latest").migrate();

		assertThat(state(jdbc, gameId)).isEqualTo(before);
		assertThat(jdbc.queryForList(
				"SELECT id, level, status, cost_paid_mg FROM game.building WHERE game_id = ? ORDER BY id", gameId))
			.isEqualTo(buildingsBefore);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.fortification WHERE game_id = ?", Integer.class, gameId))
			.as("no fortress is invented for a running game").isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.siege WHERE game_id = ?", Integer.class, gameId))
			.isZero();
		assertThat(jdbc.queryForObject("SELECT ruleset_version FROM game.game WHERE id = ?", String.class, gameId))
			.isEqualTo("v1");
	}

	@Test
	void runningGamesGetNoResearchAndKeepTheirWorkshopsUnderV10() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword());
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		LegacyGames.flyway(dataSource, "5").migrate();
		UUID gameId = LegacyGames.insertRunningPhase3Game(jdbc);
		LegacyGames.flyway(dataSource, "9").migrate();
		List<Map<String, Object>> buildingsBefore = jdbc.queryForList(
				"SELECT id, level, status, cost_paid_mg FROM game.building WHERE game_id = ? ORDER BY id", gameId);
		List<Map<String, Object>> before = state(jdbc, gameId);

		LegacyGames.flyway(dataSource, "latest").migrate();

		assertThat(state(jdbc, gameId)).isEqualTo(before);
		assertThat(jdbc.queryForList(
				"SELECT id, level, status, cost_paid_mg FROM game.building WHERE game_id = ? ORDER BY id", gameId))
			.isEqualTo(buildingsBefore);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM game.building WHERE game_id = ? AND upgrade_target_tier IS NOT NULL", Integer.class,
				gameId)).isZero();
		for (String table : List.of("nation_research", "institution", "nation_doctrine", "population_growth")) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM game." + table + " WHERE game_id = ?", Integer.class,
					gameId)).as(table).isZero();
		}
	}

	@Test
	void runningGamesGetNoDiplomacyAndStayInProgressUnderV11() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword());
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		LegacyGames.flyway(dataSource, "5").migrate();
		UUID gameId = LegacyGames.insertRunningPhase3Game(jdbc);
		LegacyGames.flyway(dataSource, "10").migrate();
		List<Map<String, Object>> before = state(jdbc, gameId);

		LegacyGames.flyway(dataSource, "latest").migrate();

		assertThat(state(jdbc, gameId)).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT status FROM game.game WHERE id = ?", String.class, gameId))
			.isEqualTo("IN_PROGRESS");
		for (String table : List.of("diplomacy_game", "game_result")) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM game." + table + " WHERE game_id = ?", Integer.class,
					gameId)).as(table).isZero();
		}
		// The new status and command types are accepted by the replaced constraints.
		UUID gamePlayer = jdbc.queryForObject("SELECT id FROM game.game_player WHERE game_id = ? AND seat_order = 1",
				UUID.class, gameId);
		jdbc.update("""
				INSERT INTO game.game_command (id, game_id, game_player_id, idempotency_key, command_type, turn_number,
				    created_at)
				VALUES (gen_random_uuid(), ?, ?, gen_random_uuid(), 'ACCEPT_PEACE', 6, now())
				""", gameId, gamePlayer);
		jdbc.update("UPDATE game.game SET status = 'FINISHED' WHERE id = ?", gameId);
		assertThat(jdbc.queryForObject("SELECT current_round FROM game.game WHERE id = ?", Integer.class, gameId))
			.as("a finished game keeps its round").isEqualTo(3);
	}

	private static List<Map<String, Object>> state(JdbcTemplate jdbc, UUID gameId) {
		return jdbc.queryForList("""
				SELECT pt.key, c.population, ps.owner_nation_id, ps.controller_nation_id, ne.treasury_mg,
				       g.current_round, g.turn_number, g.state_version
				FROM game.province_state ps
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				JOIN game.city c ON c.province_state_id = ps.id
				JOIN game.nation_economy ne ON ne.nation_id = ps.owner_nation_id
				JOIN game.game g ON g.id = ps.game_id
				WHERE ps.game_id = ?
				ORDER BY pt.key
				""", gameId);
	}

}
