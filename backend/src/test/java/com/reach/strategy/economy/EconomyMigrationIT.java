package com.reach.strategy.economy;

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
 * V6 on a database that already holds a running Phase 3 game: the game must keep working with a
 * treasury and its starting capital workshop, without touching anything else.
 */
@Testcontainers(disabledWithoutDocker = true)
class EconomyMigrationIT {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

	@Test
	void runningPhase3GamesAreBackfilledWithTheV1StartingEconomy() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword());
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		LegacyGames.flyway(dataSource, "5").migrate();
		UUID gameId = LegacyGames.insertRunningPhase3Game(jdbc);

		LegacyGames.flyway(dataSource, "latest").migrate();

		assertThat(jdbc.queryForObject("SELECT ruleset_version FROM game.game WHERE id = ?", String.class, gameId))
			.isEqualTo("v1");
		assertThat(jdbc.queryForList("SELECT treasury_mg FROM game.nation_economy WHERE game_id = ?", Long.class, gameId))
			.containsExactly(50_000L, 50_000L);
		List<Map<String, Object>> buildings = jdbc.queryForList("""
				SELECT pt.key, b.level, b.status, b.ordered_round
				FROM game.building b
				JOIN game.city c ON c.id = b.city_id
				JOIN game.province_state ps ON ps.id = c.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				WHERE b.game_id = ? ORDER BY pt.key
				""", gameId);
		assertThat(buildings).extracting(row -> row.get("key")).containsExactly("ostrava", "varnhold");
		assertThat(buildings).allSatisfy(row -> {
			assertThat(row.get("level")).isEqualTo(1);
			assertThat(row.get("status")).isEqualTo("ACTIVE");
			assertThat(row.get("ordered_round")).isEqualTo(0);
		});
		assertThat(jdbc.queryForObject("SELECT current_round FROM game.game WHERE id = ?", Integer.class, gameId))
			.as("turn state untouched").isEqualTo(3);
	}

}
