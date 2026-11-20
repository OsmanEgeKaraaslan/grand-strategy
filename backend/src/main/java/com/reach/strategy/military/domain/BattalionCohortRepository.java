package com.reach.strategy.military.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Cohorts ({@code battalion_cohort}) in plain JDBC; only {@link CohortLedger} writes them. */
@Repository
public class BattalionCohortRepository {

	private static final String SELECT = "SELECT id, game_id, battalion_id, origin_city_id, soldiers FROM game.battalion_cohort ";

	private final JdbcTemplate jdbc;

	BattalionCohortRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<BattalionCohort> findByGameIdAndBattalionIdOrderByOriginCityId(UUID gameId, UUID battalionId) {
		return jdbc.query(SELECT + "WHERE game_id = ? AND battalion_id = ? ORDER BY origin_city_id",
				BattalionCohortRepository::row, gameId, battalionId);
	}

	public List<BattalionCohort> findByGameId(UUID gameId) {
		return jdbc.query(SELECT + "WHERE game_id = ? ORDER BY battalion_id, origin_city_id", BattalionCohortRepository::row,
				gameId);
	}

	void insert(BattalionCohort cohort) {
		jdbc.update(
				"INSERT INTO game.battalion_cohort (id, game_id, battalion_id, origin_city_id, soldiers) VALUES (?, ?, ?, ?, ?)",
				cohort.id(), cohort.gameId(), cohort.battalionId(), cohort.originCityId(), cohort.soldiers());
	}

	void updateSoldiers(BattalionCohort cohort) {
		jdbc.update("UPDATE game.battalion_cohort SET soldiers = ? WHERE game_id = ? AND id = ?", cohort.soldiers(),
				cohort.gameId(), cohort.id());
	}

	void delete(BattalionCohort cohort) {
		jdbc.update("DELETE FROM game.battalion_cohort WHERE game_id = ? AND id = ?", cohort.gameId(), cohort.id());
	}

	void deleteByBattalion(UUID gameId, UUID battalionId) {
		jdbc.update("DELETE FROM game.battalion_cohort WHERE game_id = ? AND battalion_id = ?", gameId, battalionId);
	}

	private static BattalionCohort row(ResultSet rs, int n) throws SQLException {
		return BattalionCohort.restore(rs.getObject("id", UUID.class), rs.getObject("game_id", UUID.class),
				rs.getObject("battalion_id", UUID.class), rs.getObject("origin_city_id", UUID.class), rs.getInt("soldiers"));
	}

}
