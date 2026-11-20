package com.reach.strategy.military.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.military.rules.Cohorts;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a battalion's cohort rows in step with its manpower. Every change goes through here and is
 * checked: after each operation the cohorts add up to exactly the battalion's manpower, otherwise
 * the command's transaction fails.
 */
@Component
public class CohortLedger {

	private final BattalionCohortRepository cohorts;

	CohortLedger(BattalionCohortRepository cohorts) {
		this.cohorts = cohorts;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public List<Cohorts.Cohort> of(Battalion battalion) {
		return rows(battalion).stream().map(BattalionCohort::asCohort).toList();
	}

	/** A newly raised battalion: all its soldiers come from one city. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void raised(Battalion battalion) {
		cohorts.insert(BattalionCohort.of(battalion.gameId(), battalion.id(), battalion.originCityId(),
				battalion.manpower()));
	}

	/** After {@link Battalion#reinforce}: the new soldiers join the cohort of their city. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void reinforced(Battalion battalion, UUID cityId, int soldiers) {
		replace(battalion, Cohorts.add(of(battalion), cityId, soldiers));
	}

	/** After {@link Battalion#suffer}: the dead are taken from the cohorts proportionally. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void suffered(Battalion battalion, int losses) {
		if (losses > 0) {
			replace(battalion, Cohorts.afterLosses(of(battalion), losses));
		}
	}

	/** The battalion is about to be deleted: its cohorts go with it. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void removed(Battalion battalion) {
		cohorts.deleteByBattalion(battalion.gameId(), battalion.id());
	}

	private List<BattalionCohort> rows(Battalion battalion) {
		return cohorts.findByGameIdAndBattalionIdOrderByOriginCityId(battalion.gameId(), battalion.id());
	}

	private void replace(Battalion battalion, List<Cohorts.Cohort> target) {
		if (Cohorts.total(target) != battalion.manpower()) {
			throw new IllegalStateException("Cohorts of battalion " + battalion.id() + " total " + Cohorts.total(target)
					+ " but it has " + battalion.manpower() + " soldiers");
		}
		Map<UUID, Integer> wanted = new HashMap<>();
		target.forEach(c -> wanted.put(c.originCityId(), c.soldiers()));
		for (BattalionCohort row : rows(battalion)) {
			Integer soldiers = wanted.remove(row.originCityId());
			if (soldiers == null) {
				cohorts.delete(row);
			}
			else if (soldiers != row.soldiers()) {
				row.setSoldiers(soldiers);
				cohorts.updateSoldiers(row);
			}
		}
		wanted.forEach((city, soldiers) -> cohorts.insert(BattalionCohort.of(battalion.gameId(), battalion.id(), city,
				soldiers)));
	}

}
