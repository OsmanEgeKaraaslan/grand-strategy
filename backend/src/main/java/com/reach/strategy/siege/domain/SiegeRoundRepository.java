package com.reach.strategy.siege.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Per-round siege history ({@code siege_round}). Its primary key (siege, round) makes processing a
 * siege twice in one round impossible even if every other guard failed.
 */
@Repository
public class SiegeRoundRepository {

	private final JdbcTemplate jdbc;

	SiegeRoundRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Row(int round, int artilleryManpower, int progressAdded, int progressAfter, int populationBefore,
			int populationLost) {
	}

	public void insert(UUID gameId, UUID siegeId, Row row) {
		jdbc.update("""
				INSERT INTO game.siege_round (siege_id, game_id, round_number, artillery_manpower, progress_added,
				    progress_after, population_before, population_lost)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", siegeId, gameId, row.round(), row.artilleryManpower(), row.progressAdded(), row.progressAfter(),
				row.populationBefore(), row.populationLost());
	}

	public List<Row> of(UUID gameId, UUID siegeId) {
		return jdbc.query("""
				SELECT round_number, artillery_manpower, progress_added, progress_after, population_before, population_lost
				FROM game.siege_round WHERE game_id = ? AND siege_id = ? ORDER BY round_number
				""", (rs, n) -> new Row(rs.getInt("round_number"), rs.getInt("artillery_manpower"),
				rs.getInt("progress_added"), rs.getInt("progress_after"), rs.getInt("population_before"),
				rs.getInt("population_lost")), gameId, siegeId);
	}

}
