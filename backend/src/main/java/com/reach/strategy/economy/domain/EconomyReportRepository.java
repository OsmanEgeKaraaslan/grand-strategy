package com.reach.strategy.economy.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.reach.strategy.economy.rules.Treasury;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only record of each nation's round-end settlement ({@code economy_round_report}). */
@Repository
public class EconomyReportRepository {

	private final JdbcTemplate jdbc;

	EconomyReportRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Report(int round, long grossIncomeMg, long upkeepMg, long deficitMg, long treasuryAfterMg,
			boolean inArrears) {
	}

	/** The primary key (game, round, nation) makes paying a nation twice for one round impossible. */
	public void insert(UUID gameId, int round, UUID nationId, Treasury.Settlement settlement, Instant now) {
		jdbc.update("""
				INSERT INTO game.economy_round_report
				    (game_id, round_number, nation_id, gross_income_mg, upkeep_mg, deficit_mg, treasury_after_mg,
				     in_arrears, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", gameId, round, nationId, settlement.grossIncomeMg(), settlement.upkeepMg(), settlement.deficitMg(),
				settlement.treasuryAfterMg(), settlement.inArrears(), Timestamp.from(now));
	}

	/** One city's growth at one round end; the primary key forbids growing a city twice in a round. */
	public record Growth(UUID cityId, int populationBefore, int growth) {
	}

	/** Every city's growth of one round in one batched statement. */
	public void insertGrowths(UUID gameId, int round, java.util.List<Growth> growths) {
		if (growths.isEmpty()) {
			return;
		}
		jdbc.batchUpdate("""
				INSERT INTO game.population_growth (game_id, round_number, city_id, population_before, growth)
				VALUES (?, ?, ?, ?, ?)
				""", growths, growths.size(), (ps, g) -> {
			ps.setObject(1, gameId);
			ps.setInt(2, round);
			ps.setObject(3, g.cityId());
			ps.setInt(4, g.populationBefore());
			ps.setInt(5, g.growth());
		});
	}

	public OptionalLong grossIncome(UUID gameId, UUID nationId, int round) {
		return jdbc.queryForList("""
				SELECT gross_income_mg FROM game.economy_round_report
				WHERE game_id = ? AND nation_id = ? AND round_number = ?
				""", Long.class, gameId, nationId, round)
			.stream()
			.mapToLong(Long::longValue)
			.findFirst();
	}

	public Optional<Report> latest(UUID gameId, UUID nationId) {
		return jdbc.query("""
				SELECT round_number, gross_income_mg, upkeep_mg, deficit_mg, treasury_after_mg, in_arrears
				FROM game.economy_round_report
				WHERE game_id = ? AND nation_id = ?
				ORDER BY round_number DESC LIMIT 1
				""", (rs, row) -> new Report(rs.getInt("round_number"), rs.getLong("gross_income_mg"),
				rs.getLong("upkeep_mg"), rs.getLong("deficit_mg"), rs.getLong("treasury_after_mg"),
				rs.getBoolean("in_arrears")), gameId, nationId)
			.stream()
			.findFirst();
	}

}
