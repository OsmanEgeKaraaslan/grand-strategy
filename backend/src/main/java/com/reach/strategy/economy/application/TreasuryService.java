package com.reach.strategy.economy.application;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import com.reach.strategy.economy.api.EconomyApi;
import com.reach.strategy.economy.api.UpkeepSource;
import com.reach.strategy.economy.domain.EconomyReportRepository;
import com.reach.strategy.economy.domain.NationEconomy;
import com.reach.strategy.economy.domain.NationEconomyRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Treasury reads and payments for other modules, plus the total upkeep of a nation. */
@Service
class TreasuryService implements EconomyApi {

	private final NationEconomyRepository economies;
	private final List<UpkeepSource> upkeepSources;
	private final EconomyReportRepository reports;

	TreasuryService(NationEconomyRepository economies, List<UpkeepSource> upkeepSources,
			EconomyReportRepository reports) {
		this.reports = reports;
		this.economies = economies;
		this.upkeepSources = List.copyOf(upkeepSources);
	}

	@Override
	@Transactional(readOnly = true)
	public TreasuryState treasury(UUID nationId) {
		NationEconomy economy = load(nationId);
		return new TreasuryState(economy.treasuryMg(), economy.inArrears());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void spend(UUID nationId, long amountMg) {
		NationEconomy economy = load(nationId);
		economy.spend(amountMg);
		economies.saveAndFlush(economy);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void transfer(UUID fromNationId, UUID toNationId, long amountMg) {
		if (amountMg < 0 || fromNationId.equals(toNationId)) {
			throw new IllegalArgumentException("Invalid transfer of " + amountMg + " mg");
		}
		NationEconomy from = load(fromNationId);
		NationEconomy to = load(toNationId);
		if (!from.gameId().equals(to.gameId())) {
			throw new IllegalArgumentException("Treasuries of different games");
		}
		from.spend(amountMg);
		to.receive(amountMg);
		economies.saveAllAndFlush(java.util.List.of(from, to));
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

	private NationEconomy load(UUID nationId) {
		return economies.findById(nationId)
			.orElseThrow(() -> new IllegalStateException("Nation " + nationId + " has no treasury"));
	}

}
