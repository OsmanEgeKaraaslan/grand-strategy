package com.reach.strategy.research.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.research.rules.InstitutionType;
import com.reach.strategy.research.rules.Studies;

import com.reach.strategy.platform.read.ReadScope;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Research progress ({@code nation_research}), institutions ({@code institution}) and chosen doctrines
 * ({@code nation_doctrine}) as {@link Studies} values, in plain JDBC (ENGINE_DESIGN S3, decision D4).
 * {@link #persist} compares two of them by id and writes only new and changed rows, batched per table.
 */
@Repository
public class ResearchStore {

	private static final String PROGRESS = """
			SELECT nation_id, military_points, civilian_points, doctrine_points, military_level, civilian_level,
			       ruleset_version
			FROM game.nation_research
			""";

	private static final String INSTITUTIONS = """
			SELECT id, city_id, type, status, ordered_round, completes_after_round, cost_paid_mg, ruleset_version, created_at
			FROM game.institution
			""";

	private static final String DOCTRINES = """
			SELECT id, nation_id, doctrine_key, tier, cost_points, unlocked_round, created_at FROM game.nation_doctrine
			""";

	private final JdbcTemplate jdbc;

	ResearchStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<Studies.Progress> progress(UUID gameId) {
		return ReadScope.remember(List.of("research.progress", gameId),
				() -> List.copyOf(jdbc.query(PROGRESS + "WHERE game_id = ? ORDER BY nation_id", ResearchStore::progress, gameId)));
	}

	@Transactional(readOnly = true)
	public Optional<Studies.Progress> progress(UUID gameId, UUID nationId) {
		return jdbc.query(PROGRESS + "WHERE game_id = ? AND nation_id = ?", ResearchStore::progress, gameId, nationId)
			.stream()
			.findFirst();
	}

	@Transactional(readOnly = true)
	public List<Studies.Institution> institutions(UUID gameId) {
		return ReadScope.remember(List.of("research.institutions", gameId),
				() -> List.copyOf(jdbc.query(INSTITUTIONS + "WHERE game_id = ? ORDER BY id", ResearchStore::institution, gameId)));
	}

	@Transactional(readOnly = true)
	public List<Studies.Institution> institutionsOfCity(UUID gameId, UUID cityId) {
		return jdbc.query(INSTITUTIONS + "WHERE game_id = ? AND city_id = ? ORDER BY type", ResearchStore::institution,
				gameId, cityId);
	}

	@Transactional(readOnly = true)
	public Optional<Studies.Institution> institution(UUID gameId, UUID id) {
		return jdbc.query(INSTITUTIONS + "WHERE game_id = ? AND id = ?", ResearchStore::institution, gameId, id)
			.stream()
			.findFirst();
	}

	/** Every chosen doctrine of the game, by nation id and then doctrine key. */
	@Transactional(readOnly = true)
	public List<Studies.Doctrine> doctrines(UUID gameId) {
		return ReadScope.remember(List.of("research.doctrines", gameId), () -> List.copyOf(jdbc
			.query(DOCTRINES + "WHERE game_id = ? ORDER BY nation_id, doctrine_key", ResearchStore::doctrine, gameId)));
	}

	/** A nation's chosen doctrines by doctrine key. */
	@Transactional(readOnly = true)
	public List<Studies.Doctrine> doctrines(UUID gameId, UUID nationId) {
		return jdbc.query(DOCTRINES + "WHERE game_id = ? AND nation_id = ? ORDER BY doctrine_key",
				ResearchStore::doctrine, gameId, nationId);
	}

	/** Writes the rows that are new or different in {@code after}; nothing is ever removed. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void persist(UUID gameId, Studies before, Studies after) {
		// Keep a turn scope exact (ENGINE_DESIGN S6): whatever this write may change is read again.
		for (String name : List.of("research.progress", "research.institutions", "research.doctrines",
				"research.effects")) {
			ReadScope.forget(name);
		}
		List<Studies.Progress> begun = after.progress()
			.stream()
			.filter(p -> before.progressOf(p.nationId()).isEmpty())
			.toList();
		if (!begun.isEmpty()) {
			jdbc.batchUpdate("""
					INSERT INTO game.nation_research (nation_id, game_id, military_points, civilian_points, doctrine_points,
					    military_level, civilian_level, ruleset_version)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?)
					""", begun, begun.size(), (ps, p) -> {
				ps.setObject(1, p.nationId());
				ps.setObject(2, gameId);
				ps.setInt(3, p.militaryPoints());
				ps.setInt(4, p.civilianPoints());
				ps.setInt(5, p.doctrinePoints());
				ps.setInt(6, p.militaryLevel());
				ps.setInt(7, p.civilianLevel());
				ps.setString(8, p.rulesetVersion());
			});
		}
		List<Studies.Progress> changed = after.progress()
			.stream()
			.filter(p -> before.progressOf(p.nationId()).filter(old -> !old.equals(p)).isPresent())
			.toList();
		if (!changed.isEmpty()) {
			jdbc.batchUpdate("""
					UPDATE game.nation_research SET military_points = ?, civilian_points = ?, doctrine_points = ?,
					    military_level = ?, civilian_level = ?
					WHERE game_id = ? AND nation_id = ?
					""", changed, changed.size(), (ps, p) -> {
				ps.setInt(1, p.militaryPoints());
				ps.setInt(2, p.civilianPoints());
				ps.setInt(3, p.doctrinePoints());
				ps.setInt(4, p.militaryLevel());
				ps.setInt(5, p.civilianLevel());
				ps.setObject(6, gameId);
				ps.setObject(7, p.nationId());
			});
		}
		List<Studies.Institution> institutions = after.institutions()
			.stream()
			.filter(i -> !i.equals(before.institution(i.id()).orElse(null)))
			.toList();
		if (!institutions.isEmpty()) {
			jdbc.batchUpdate("""
					INSERT INTO game.institution (id, game_id, city_id, type, status, ordered_round, completes_after_round,
					    cost_paid_mg, ruleset_version, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT (id) DO UPDATE SET status = excluded.status
					""", institutions, institutions.size(), (ps, i) -> {
				ps.setObject(1, i.id());
				ps.setObject(2, gameId);
				ps.setObject(3, i.cityId());
				ps.setString(4, i.type().name());
				ps.setString(5, i.status().name());
				ps.setInt(6, i.orderedRound());
				ps.setInt(7, i.completesAfterRound());
				ps.setLong(8, i.costPaidMg());
				ps.setString(9, i.rulesetVersion());
				ps.setTimestamp(10, Timestamp.from(i.createdAt()));
			});
		}
		List<Studies.Doctrine> chosen = after.doctrines()
			.stream()
			.filter(d -> before.doctrine(d.id()).isEmpty())
			.toList();
		if (!chosen.isEmpty()) {
			jdbc.batchUpdate("""
					INSERT INTO game.nation_doctrine (id, game_id, nation_id, doctrine_key, tier, cost_points, unlocked_round,
					    created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?)
					""", chosen, chosen.size(), (ps, d) -> {
				ps.setObject(1, d.id());
				ps.setObject(2, gameId);
				ps.setObject(3, d.nationId());
				ps.setString(4, d.doctrineKey());
				ps.setInt(5, d.tier());
				ps.setInt(6, d.costPoints());
				ps.setInt(7, d.unlockedRound());
				ps.setTimestamp(8, Timestamp.from(d.createdAt()));
			});
		}
	}

	private static Studies.Progress progress(ResultSet rs, int n) throws SQLException {
		return new Studies.Progress(rs.getObject("nation_id", UUID.class), rs.getInt("military_points"),
				rs.getInt("civilian_points"), rs.getInt("doctrine_points"), rs.getInt("military_level"),
				rs.getInt("civilian_level"), rs.getString("ruleset_version"));
	}

	private static Studies.Institution institution(ResultSet rs, int n) throws SQLException {
		return new Studies.Institution(rs.getObject("id", UUID.class), rs.getObject("city_id", UUID.class),
				InstitutionType.valueOf(rs.getString("type")), Studies.Institution.Status.valueOf(rs.getString("status")),
				rs.getInt("ordered_round"), rs.getInt("completes_after_round"), rs.getLong("cost_paid_mg"),
				rs.getString("ruleset_version"), rs.getTimestamp("created_at").toInstant());
	}

	private static Studies.Doctrine doctrine(ResultSet rs, int n) throws SQLException {
		return new Studies.Doctrine(rs.getObject("id", UUID.class), rs.getObject("nation_id", UUID.class),
				rs.getString("doctrine_key"), rs.getInt("tier"), rs.getInt("cost_points"), rs.getInt("unlocked_round"),
				rs.getTimestamp("created_at").toInstant());
	}

}
