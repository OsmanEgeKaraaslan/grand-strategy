package com.reach.strategy.economy.application;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import com.reach.strategy.economy.api.EconomyApi;
import com.reach.strategy.economy.api.UpkeepSource;
import com.reach.strategy.economy.domain.EconomyReportRepository;
import com.reach.strategy.economy.domain.EconomyStore;
import com.reach.strategy.economy.rules.Ledger;
import com.reach.strategy.platform.read.ReadScope;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Treasury reads and payments for other modules, plus the total upkeep of a nation. */
@Service
class TreasuryService implements EconomyApi {

	private final EconomyStore store;
	private final List<UpkeepSource> upkeepSources;
	private final EconomyReportRepository reports;

	TreasuryService(EconomyStore store, List<UpkeepSource> upkeepSources,
			EconomyReportRepository reports) {
		this.reports = reports;
		this.store = store;
		this.upkeepSources = List.copyOf(upkeepSources);
	}

	@Override
	@Transactional(readOnly = true)
	public TreasuryState treasury(UUID nationId) {
		// Inside a read scope (the computer's observation) every view shares one read per nation.
		Ledger.Purse purse = ReadScope.remember(List.of("economy.purse", nationId), () -> load(nationId));
		return new TreasuryState(purse.treasuryMg(), purse.inArrears());
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, TreasuryState> treasuries(UUID gameId) {
		Map<UUID, TreasuryState> all = new java.util.HashMap<>();
		store.purses(gameId).forEach(p -> all.put(p.nationId(), new TreasuryState(p.treasuryMg(), p.inArrears())));
		return Map.copyOf(all);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public TreasuryState spend(UUID nationId, long amountMg) {
		Ledger before = Ledger.of(List.of(load(nationId)), List.of());
		Ledger after = before.spend(nationId, amountMg);
		store.persist(null, before, after);
		Ledger.Purse purse = after.purse(nationId);
		return new TreasuryState(purse.treasuryMg(), purse.inArrears());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void transfer(UUID fromNationId, UUID toNationId, long amountMg) {
		if (amountMg < 0 || fromNationId.equals(toNationId)) {
			throw new IllegalArgumentException("Invalid transfer of " + amountMg + " mg");
		}
		UUID fromGame = store.gameOfNation(fromNationId);
		if (fromGame == null || !fromGame.equals(store.gameOfNation(toNationId))) {
			throw new IllegalArgumentException("Treasuries of different games");
		}
		Ledger before = Ledger.of(List.of(load(fromNationId), load(toNationId)), List.of());
		store.persist(fromGame, before, before.transfer(fromNationId, toNationId, amountMg));
	}

	@Override
	@Transactional(readOnly = true)
	public OptionalLong grossIncome(UUID gameId, UUID nationId, int round) {
		return reports.grossIncome(gameId, nationId, round);
	}

	/** Upkeep of a nation at the next round end, summed over every source (exact arithmetic). */
	long upkeepOf(UUID gameId, String rulesetVersion, UUID nationId) {
		long total = 0;
		for (UpkeepSource source : upkeepSources) {
			Map<UUID, Long> byNation = source.upkeepByNation(gameId, rulesetVersion);
			total = Math.addExact(total, byNation.getOrDefault(nationId, 0L));
		}
		return total;
	}

	private Ledger.Purse load(UUID nationId) {
		return store.purse(nationId)
			.orElseThrow(() -> new IllegalStateException("Nation " + nationId + " has no treasury"));
	}

}
