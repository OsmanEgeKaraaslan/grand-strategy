package com.reach.strategy.support;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Databases as earlier releases left them, for migration tests. */
public final class LegacyGames {

	private LegacyGames() {
	}

	public static Flyway flyway(DriverManagerDataSource dataSource, String target) {
		return Flyway.configure()
			.dataSource(dataSource)
			.schemas("game")
			.defaultSchema("game")
			.createSchemas(true)
			.locations("classpath:db/migration")
			.target(target)
			.load();
	}

	/** A started two-player game in round 3, exactly as Phase 3 instantiated it. */
	public static UUID insertRunningPhase3Game(JdbcTemplate jdbc) {
		UUID host = UUID.randomUUID();
		UUID guest = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		jdbc.update("INSERT INTO game.player VALUES (?, gen_random_uuid(), 'Host-Player', now(), now())", host);
		jdbc.update("INSERT INTO game.player VALUES (?, gen_random_uuid(), 'Guest-Player', now(), now())", guest);
		jdbc.update("""
				INSERT INTO game.game (id, name, host_player_id, status, max_players, player_count, invite_code,
				    created_at, updated_at, map_template_id, current_round, active_seat, turn_number, state_version,
				    started_at, last_action_at)
				SELECT ?, 'Old game', ?, 'IN_PROGRESS', 2, 2, 'ABCDEFGHJKMN', now(), now(), m.id, 3, 1, 6, 9, now(), now()
				FROM game.map_template m WHERE m.key = 'sundered-reach'
				""", gameId, host);
		int seat = 0;
		for (Object[] member : new Object[][] { { host, "varn" }, { guest, "ostra" } }) {
			UUID gamePlayer = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO game.game_player (id, game_id, player_id, seat_order, joined_at, nation_template_id)
					SELECT ?, ?, ?, ?, now(), nt.id FROM game.nation_template nt WHERE nt.key = ?
					""", gamePlayer, gameId, member[0], seat++, member[1]);
			jdbc.update("""
					INSERT INTO game.nation (id, game_id, nation_template_id, game_player_id, name, color)
					SELECT gen_random_uuid(), ?, nt.id, ?, nt.name, nt.color FROM game.nation_template nt WHERE nt.key = ?
					""", gameId, gamePlayer, member[1]);
		}
		jdbc.update("""
				INSERT INTO game.province_state (id, game_id, province_template_id, owner_nation_id, controller_nation_id)
				SELECT gen_random_uuid(), ?, nsp.province_template_id, n.id, n.id
				FROM game.nation_start_province nsp JOIN game.nation n ON n.nation_template_id = nsp.nation_template_id
				WHERE n.game_id = ?
				""", gameId, gameId);
		jdbc.update("""
				INSERT INTO game.city (id, game_id, province_state_id, name, population)
				SELECT gen_random_uuid(), ps.game_id, ps.id, pt.city_name, pt.start_population
				FROM game.province_state ps JOIN game.province_template pt ON pt.id = ps.province_template_id
				WHERE ps.game_id = ?
				""", gameId);
		return gameId;
	}

}
