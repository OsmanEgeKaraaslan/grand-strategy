package com.reach.strategy.game.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.game.api.MatchRecords;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One indexed query over the stored results (game_result_standing by player) joined with the
 * player's own seat for the faction; faction and map names come from the immutable map templates.
 */
@Service
class MatchRecordsService implements MatchRecords {

	private final JdbcTemplate jdbc;
	private final WorldApi world;

	MatchRecordsService(JdbcTemplate jdbc, WorldApi world) {
		this.jdbc = jdbc;
		this.world = world;
	}

	private record Row(UUID gameId, String gameName, UUID mapId, java.time.Instant finishedAt, int finishedRound,
			Outcome outcome, int rank, UUID nationTemplateId) {
	}

	@Override
	@Transactional(readOnly = true)
	public List<CompletedMatch> completedMatches(UUID playerId) {
		List<Row> rows = jdbc.query("""
				SELECT r.game_id, g.name, g.map_template_id, r.finished_at, r.finished_round, r.outcome,
				       r.winner_nation_id = s.nation_id AS won, s.rank, gp.nation_template_id
				FROM game.game_result_standing s
				JOIN game.game_result r ON r.game_id = s.game_id
				JOIN game.game g ON g.id = s.game_id AND g.status = 'FINISHED'
				JOIN game.game_player gp ON gp.game_id = s.game_id AND gp.player_id = s.player_id
				WHERE s.player_id = ?
				ORDER BY r.finished_at DESC, r.game_id
				""", (rs, i) -> new Row(rs.getObject("game_id", UUID.class), rs.getString("name"),
				rs.getObject("map_template_id", UUID.class), rs.getTimestamp("finished_at").toInstant(),
				rs.getInt("finished_round"),
				"DRAW".equals(rs.getString("outcome")) ? Outcome.DRAW : rs.getBoolean("won") ? Outcome.WIN : Outcome.LOSS,
				rs.getInt("rank"), rs.getObject("nation_template_id", UUID.class)), playerId);
		Map<UUID, WorldApi.MapSummary> maps = new HashMap<>();
		return rows.stream().map(row -> {
			WorldApi.MapSummary map = maps.computeIfAbsent(row.mapId(), world::mapSummary);
			Optional<WorldApi.NationOption> nation = map.nation(row.nationTemplateId());
			return new CompletedMatch(row.gameId(), row.gameName(), map.name(), row.finishedAt(), row.finishedRound(),
					row.outcome(), row.rank(), nation.map(WorldApi.NationOption::key).orElse("unknown"),
					nation.map(WorldApi.NationOption::name).orElse("Unknown nation"));
		}).toList();
	}

}
