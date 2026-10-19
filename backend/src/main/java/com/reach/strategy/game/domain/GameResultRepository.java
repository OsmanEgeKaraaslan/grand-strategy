package com.reach.strategy.game.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Final results of finished games (Phase 9). The primary key on {@code game_id} makes storing a
 * second result for the same game impossible; rows are never updated. Each standing names the
 * player behind the nation, ready for player statistics (Phase 12).
 */
@Repository
public class GameResultRepository {

	private final JdbcTemplate jdbc;

	GameResultRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** @param winnerNationId null for a draw */
	public record Result(UUID gameId, int finishedRound, String reason, UUID winnerNationId, String rulesetVersion,
			String statisticsJson, Instant finishedAt) {

		public boolean draw() {
			return winnerNationId == null;
		}

	}

	public record Standing(UUID nationId, UUID playerId, int rank, long score, int ownedProvinces, long ownedPopulation,
			long treasuryMg) {
	}

	public void insert(Result result, List<Standing> standings) {
		jdbc.update("""
				INSERT INTO game.game_result (game_id, finished_round, reason, outcome, winner_nation_id, ruleset_version,
				    statistics, finished_at)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
				""", result.gameId(), result.finishedRound(), result.reason(), result.draw() ? "DRAW" : "WINNER",
				result.winnerNationId(), result.rulesetVersion(), result.statisticsJson(),
				Timestamp.from(result.finishedAt()));
		for (Standing s : standings) {
			jdbc.update("""
					INSERT INTO game.game_result_standing (game_id, nation_id, player_id, rank, score, owned_provinces,
					    owned_population, treasury_mg)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?)
					""", result.gameId(), s.nationId(), s.playerId(), s.rank(), s.score(), s.ownedProvinces(),
					s.ownedPopulation(), s.treasuryMg());
		}
	}

	public Optional<Result> find(UUID gameId) {
		return jdbc.query("""
				SELECT game_id, finished_round, reason, winner_nation_id, ruleset_version, statistics::text AS statistics,
				       finished_at
				FROM game.game_result WHERE game_id = ?
				""", (rs, row) -> new Result(rs.getObject("game_id", UUID.class), rs.getInt("finished_round"),
				rs.getString("reason"), rs.getObject("winner_nation_id", UUID.class), rs.getString("ruleset_version"),
				rs.getString("statistics"), rs.getTimestamp("finished_at").toInstant()), gameId)
			.stream()
			.findFirst();
	}

	public List<Standing> standings(UUID gameId) {
		return jdbc.query("""
				SELECT nation_id, player_id, rank, score, owned_provinces, owned_population, treasury_mg
				FROM game.game_result_standing WHERE game_id = ?
				ORDER BY rank, score DESC, nation_id
				""", (rs, row) -> new Standing(rs.getObject("nation_id", UUID.class), rs.getObject("player_id", UUID.class),
				rs.getInt("rank"), rs.getLong("score"), rs.getInt("owned_provinces"), rs.getLong("owned_population"),
				rs.getLong("treasury_mg")), gameId);
	}

}
