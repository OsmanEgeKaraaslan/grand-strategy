package com.reach.strategy.economy.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The immutable economic state (ENGINE_DESIGN S3): treasuries and workshop transitions. */
class LedgerTest {

	static final UUID ME = new UUID(0, 1);
	static final UUID YOU = new UUID(0, 2);
	static final UUID CITY = new UUID(0, 10);
	static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

	static Ledger.Building workshop() {
		return Ledger.Building.startingWorkshop(new UUID(0, 100), CITY, 1, NOW);
	}

	@Test
	void goldIsSpentAndMovedButNeverCreatedOrOverdrawn() {
		Ledger ledger = Ledger.empty().open(Ledger.Purse.opening(ME, 10_000)).open(Ledger.Purse.opening(YOU, 0));

		Ledger after = ledger.spend(ME, 4_000).transfer(ME, YOU, 1_500);

		assertThat(after.purse(ME).treasuryMg()).isEqualTo(4_500);
		assertThat(after.purse(YOU).treasuryMg()).isEqualTo(1_500);
		assertThat(ledger.purse(ME).treasuryMg()).isEqualTo(10_000);
		assertThatThrownBy(() -> ledger.spend(ME, 10_001)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> ledger.transfer(ME, ME, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ledger.purse(new UUID(0, 9))).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> ledger.open(Ledger.Purse.opening(ME, 1))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void settlementSetsTheTreasuryAndTheArrearsFlag() {
		Ledger ledger = Ledger.empty().open(Ledger.Purse.opening(ME, 1_000));

		Ledger settled = ledger.settle(ME, Treasury.settle(1_000, 200, 2_000));

		assertThat(settled.purse(ME).treasuryMg()).isZero();
		assertThat(settled.purse(ME).inArrears()).isTrue();
	}

	@Test
	void aProjectBecomesActiveAndAnUpgradeRaisesTheTier() {
		Ledger.Building project = Ledger.Building.workshopProject(new UUID(0, 101), CITY, 1, 2, 3, 5_000, NOW);
		assertThat(project.status()).isEqualTo(WorkshopStatus.UNDER_CONSTRUCTION);
		assertThat(project.completed().status()).isEqualTo(WorkshopStatus.ACTIVE);
		assertThatThrownBy(() -> project.completed().completed()).isInstanceOf(IllegalStateException.class);

		Ledger.Building upgrading = workshop().upgradeStarted(2, 4, 6, 8_000);
		assertThat(upgrading.upgrading()).isTrue();
		assertThat(upgrading.tier()).isEqualTo(1);
		Ledger.Building upgraded = upgrading.upgradeCompleted();
		assertThat(upgraded.tier()).isEqualTo(2);
		assertThat(upgraded.upgrading()).isFalse();
		assertThat(upgraded.upgradeCostPaidMg()).isEqualTo(8_000);
		assertThatThrownBy(() -> workshop().upgradeStarted(3, 4, 6, 1)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void damageCancelsARunningUpgradeAndARepairRestoresTheWorkshop() {
		Ledger.Building damaged = workshop().upgradeStarted(2, 4, 6, 8_000).damaged();
		assertThat(damaged.status()).isEqualTo(WorkshopStatus.DAMAGED);
		assertThat(damaged.upgrading()).isFalse();
		assertThat(damaged.upgradeCompletesAfterRound()).isNull();

		Ledger.Building repairing = damaged.repairStarted(5, 6, 1_500);
		assertThat(repairing.status()).isEqualTo(WorkshopStatus.UNDER_REPAIR);
		assertThat(repairing.repairCompletesAfterRound()).isEqualTo(6);
		assertThat(repairing.repairCompleted().status()).isEqualTo(WorkshopStatus.ACTIVE);
		assertThatThrownBy(() -> workshop().repairStarted(5, 6, 1)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void unchangedRowsStayTheSameObjectsAndUnknownBuildingsAreRefused() {
		Ledger ledger = Ledger.of(List.of(Ledger.Purse.opening(ME, 100)), List.of(workshop()));

		Ledger after = ledger.spend(ME, 10);

		assertThat(after.building(new UUID(0, 100)).orElseThrow()).isSameAs(ledger.building(new UUID(0, 100)).orElseThrow());
		assertThatThrownBy(() -> ledger.update(Ledger.Building.startingWorkshop(new UUID(0, 999), CITY, 1, NOW)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ledger.build(workshop())).isInstanceOf(IllegalArgumentException.class);
	}

}
