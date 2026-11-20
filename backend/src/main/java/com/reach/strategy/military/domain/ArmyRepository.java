package com.reach.strategy.military.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Armies ({@code army}) in plain JDBC; an army never changes after it is created, it is only deleted. */
@Repository
public class ArmyRepository {

	private static final String SELECT = "SELECT id, game_id, nation_id, name, created_at FROM game.army ";

	private final JdbcTemplate jdbc;

	ArmyRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Army> findByGameIdOrderById(UUID gameId) {
		return jdbc.query(SELECT + "WHERE game_id = ? ORDER BY id", ArmyRepository::row, gameId);
	}

	public Optional<Army> findByGameIdAndId(UUID gameId, UUID id) {
		return jdbc.query(SELECT + "WHERE game_id = ? AND id = ?", ArmyRepository::row, gameId, id).stream().findFirst();
	}

	public Army saveAndFlush(Army army) {
		jdbc.update("INSERT INTO game.army (id, game_id, nation_id, name, created_at) VALUES (?, ?, ?, ?, ?)", army.id(),
				army.gameId(), army.nationId(), army.name(), Timestamp.from(army.createdAt()));
		return army;
	}

	public void delete(Army army) {
		jdbc.update("DELETE FROM game.army WHERE game_id = ? AND id = ?", army.gameId(), army.id());
	}

	/** Nothing to do: every write is already sent. */
	public void flush() {
	}

	private static Army row(ResultSet rs, int n) throws SQLException {
		return Army.restore(rs.getObject("id", UUID.class), rs.getObject("game_id", UUID.class),
				rs.getObject("nation_id", UUID.class), rs.getString("name"), rs.getTimestamp("created_at").toInstant());
	}

}
