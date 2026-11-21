package com.reach.strategy.economy.domain;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.economy.rules.Ledger;
import com.reach.strategy.economy.rules.WorkshopStatus;

import com.reach.strategy.platform.read.ReadScope;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Treasuries ({@code nation_economy}) and buildings ({@code building}) as {@link Ledger} values, in
 * plain JDBC (ENGINE_DESIGN S3, decision D4). {@link #persist} compares two ledgers by id and writes
 * only new and changed rows, one batched statement per table.
 */
@Repository
public class EconomyStore {

	private static final String BUILDING_COLUMNS = """
			id, city_id, level, status, ordered_round, completes_after_round, cost_paid_mg, created_at, \
			repair_ordered_round, repair_completes_after_round, repair_cost_paid_mg, upgrade_target_tier, \
			upgrade_ordered_round, upgrade_completes_after_round, upgrade_cost_paid_mg""";

	private static final String UPSERT_BUILDING = "INSERT INTO game.building (game_id, type, " + BUILDING_COLUMNS + """
			) VALUES (?, 'WORKSHOP', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (id) DO UPDATE SET level = excluded.level, status = excluded.status,
			    repair_ordered_round = excluded.repair_ordered_round,
			    repair_completes_after_round = excluded.repair_completes_after_round,
			    repair_cost_paid_mg = excluded.repair_cost_paid_mg, upgrade_target_tier = excluded.upgrade_target_tier,
			    upgrade_ordered_round = excluded.upgrade_ordered_round,
			    upgrade_completes_after_round = excluded.upgrade_completes_after_round,
			    upgrade_cost_paid_mg = excluded.upgrade_cost_paid_mg
			""";

	private static final String INSERT_PURSE =
			"INSERT INTO game.nation_economy (nation_id, game_id, treasury_mg, in_arrears) VALUES (?, ?, ?, ?)";

	private final JdbcTemplate jdbc;

	EconomyStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Every treasury and building of a game, for a change that may touch any of them. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Ledger load(UUID gameId) {
		return Ledger.of(purses(gameId), buildings(gameId));
	}

	/** Every treasury of the game in nation id order. */
	@Transactional(readOnly = true)
	public List<Ledger.Purse> purses(UUID gameId) {
		return ReadScope.remember(List.of("economy.purses", gameId), () -> List.copyOf(jdbc.query(
				"SELECT nation_id, treasury_mg, in_arrears FROM game.nation_economy WHERE game_id = ? ORDER BY nation_id",
				EconomyStore::purse, gameId)));
	}

	/** One nation's treasury (nation ids are unique across games). */
	@Transactional(readOnly = true)
	public Optional<Ledger.Purse> purse(UUID nationId) {
		return jdbc.query("SELECT nation_id, treasury_mg, in_arrears FROM game.nation_economy WHERE nation_id = ?",
				EconomyStore::purse, nationId)
			.stream()
			.findFirst();
	}

	/** The game a nation's treasury belongs to, or null if it has none. */
	@Transactional(readOnly = true)
	public UUID gameOfNation(UUID nationId) {
		return jdbc.query("SELECT game_id FROM game.nation_economy WHERE nation_id = ?",
				(rs, n) -> rs.getObject("game_id", UUID.class), nationId)
			.stream()
			.findFirst()
			.orElse(null);
	}

	/** Every building of the game in id order. */
	@Transactional(readOnly = true)
	public List<Ledger.Building> buildings(UUID gameId) {
		return ReadScope.remember(List.of("economy.buildings", gameId),
				() -> List.copyOf(jdbc.query("SELECT " + BUILDING_COLUMNS + " FROM game.building WHERE game_id = ? ORDER BY id",
						EconomyStore::building, gameId)));
	}

	@Transactional(readOnly = true)
	public List<Ledger.Building> buildingsOfCity(UUID gameId, UUID cityId) {
		return jdbc.query("SELECT " + BUILDING_COLUMNS + " FROM game.building WHERE game_id = ? AND city_id = ? ORDER BY id",
				EconomyStore::building, gameId, cityId);
	}

	@Transactional(readOnly = true)
	public Optional<Ledger.Building> building(UUID gameId, UUID buildingId) {
		return jdbc.query("SELECT " + BUILDING_COLUMNS + " FROM game.building WHERE game_id = ? AND id = ?",
				EconomyStore::building, gameId, buildingId)
			.stream()
			.findFirst();
	}

	/**
	 * Writes the treasuries and buildings that are new or different in {@code after}. A ledger never
	 * removes anything, so there is nothing to delete. {@code gameId} may be null when only existing
	 * treasuries changed (they are found by nation id).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void persist(UUID gameId, Ledger before, Ledger after) {
		// Keep a turn scope exact (ENGINE_DESIGN S6): whatever this write may change is read again.
		ReadScope.forget("economy.purse");
		ReadScope.forget("economy.purses");
		ReadScope.forget("economy.buildings");
		java.util.Map<UUID, Ledger.Purse> old = new java.util.HashMap<>();
		before.purses().forEach(p -> old.put(p.nationId(), p));
		List<Ledger.Purse> opened = after.purses().stream().filter(p -> !old.containsKey(p.nationId())).toList();
		List<Ledger.Purse> changed = after.purses()
			.stream()
			.filter(p -> old.containsKey(p.nationId()) && !p.equals(old.get(p.nationId())))
			.toList();
		if (!opened.isEmpty()) {
			java.util.Objects.requireNonNull(gameId, "a new treasury needs its game");
			jdbc.batchUpdate(INSERT_PURSE, opened, opened.size(), (ps, p) -> {
				ps.setObject(1, p.nationId());
				ps.setObject(2, gameId);
				ps.setLong(3, p.treasuryMg());
				ps.setBoolean(4, p.inArrears());
			});
		}
		if (!changed.isEmpty()) {
			jdbc.batchUpdate("UPDATE game.nation_economy SET treasury_mg = ?, in_arrears = ? WHERE nation_id = ?", changed,
					changed.size(), (ps, p) -> {
						ps.setLong(1, p.treasuryMg());
						ps.setBoolean(2, p.inArrears());
						ps.setObject(3, p.nationId());
					});
		}
		List<Ledger.Building> buildings = after.buildings()
			.stream()
			.filter(b -> !b.equals(before.building(b.id()).orElse(null)))
			.toList();
		if (!buildings.isEmpty()) {
			jdbc.batchUpdate(UPSERT_BUILDING, buildings, buildings.size(), (ps, b) -> bind(ps, gameId, b));
		}
	}

	private static void bind(PreparedStatement ps, UUID gameId, Ledger.Building b) throws SQLException {
		ps.setObject(1, gameId);
		ps.setObject(2, b.id());
		ps.setObject(3, b.cityId());
		ps.setInt(4, b.tier());
		ps.setString(5, b.status().name());
		ps.setInt(6, b.orderedRound());
		ps.setObject(7, b.completesAfterRound(), Types.INTEGER);
		ps.setLong(8, b.costPaidMg());
		ps.setTimestamp(9, Timestamp.from(b.createdAt()));
		ps.setObject(10, b.repairOrderedRound(), Types.INTEGER);
		ps.setObject(11, b.repairCompletesAfterRound(), Types.INTEGER);
		ps.setLong(12, b.repairCostPaidMg());
		ps.setObject(13, b.upgradeTargetTier(), Types.INTEGER);
		ps.setObject(14, b.upgradeOrderedRound(), Types.INTEGER);
		ps.setObject(15, b.upgradeCompletesAfterRound(), Types.INTEGER);
		ps.setLong(16, b.upgradeCostPaidMg());
	}

	private static Ledger.Purse purse(ResultSet rs, int n) throws SQLException {
		return new Ledger.Purse(rs.getObject("nation_id", UUID.class), rs.getLong("treasury_mg"),
				rs.getBoolean("in_arrears"));
	}

	private static Ledger.Building building(ResultSet rs, int n) throws SQLException {
		return new Ledger.Building(rs.getObject("id", UUID.class), rs.getObject("city_id", UUID.class), rs.getInt("level"),
				WorkshopStatus.valueOf(rs.getString("status")), rs.getInt("ordered_round"),
				rs.getObject("completes_after_round", Integer.class), rs.getLong("cost_paid_mg"),
				rs.getTimestamp("created_at").toInstant(), rs.getObject("repair_ordered_round", Integer.class),
				rs.getObject("repair_completes_after_round", Integer.class), rs.getLong("repair_cost_paid_mg"),
				rs.getObject("upgrade_target_tier", Integer.class), rs.getObject("upgrade_ordered_round", Integer.class),
				rs.getObject("upgrade_completes_after_round", Integer.class), rs.getLong("upgrade_cost_paid_mg"));
	}

}
