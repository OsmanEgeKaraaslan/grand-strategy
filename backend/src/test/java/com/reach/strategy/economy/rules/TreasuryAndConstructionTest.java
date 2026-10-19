package com.reach.strategy.economy.rules;

import static com.reach.strategy.economy.rules.Construction.Blocker.IN_ARREARS;
import static com.reach.strategy.economy.rules.Construction.Blocker.INSUFFICIENT_GOLD;
import static com.reach.strategy.economy.rules.Construction.Blocker.NOT_OWNER;
import static com.reach.strategy.economy.rules.Construction.Blocker.NOT_YOUR_TURN;
import static com.reach.strategy.economy.rules.Construction.Blocker.OCCUPIED;
import static com.reach.strategy.economy.rules.Construction.Blocker.TIER_LOCKED;
import static com.reach.strategy.economy.rules.Construction.Blocker.WORKSHOP_LIMIT_REACHED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TreasuryAndConstructionTest {

	static final EconomyRules V1 = CityEconomyTest.V1;

	final UUID varn = UUID.randomUUID();
	final UUID ostra = UUID.randomUUID();

	// --- settlement (Q-E1 order, Q-E1b arrears) -------------------------------------------

	@Test
	void incomeIsAddedBeforeUpkeepIsPaid() {
		Treasury.Settlement settlement = Treasury.settle(50_000, 20_000, 0);

		assertThat(settlement.treasuryAfterMg()).isEqualTo(70_000);
		assertThat(settlement.deficitMg()).isZero();
		assertThat(settlement.inArrears()).isFalse();
	}

	@Test
	void thisRoundsIncomeCanPayThisRoundsUpkeep() {
		Treasury.Settlement settlement = Treasury.settle(0, 5_000, 5_000);

		assertThat(settlement.treasuryAfterMg()).isZero();
		assertThat(settlement.inArrears()).isFalse();
	}

	@Test
	void aDeficitFloorsTheTreasuryAtZeroAndPutsTheNationInArrears() {
		Treasury.Settlement settlement = Treasury.settle(1_000, 2_000, 4_500);

		assertThat(settlement.treasuryAfterMg()).isZero();
		assertThat(settlement.deficitMg()).isEqualTo(1_500);
		assertThat(settlement.inArrears()).isTrue();
	}

	@Test
	void arrearsClearAtTheNextRoundThatSettlesWithoutDeficit() {
		Treasury.Settlement deficit = Treasury.settle(0, 1_000, 3_000);
		Treasury.Settlement recovered = Treasury.settle(deficit.treasuryAfterMg(), 4_000, 3_000);

		assertThat(deficit.inArrears()).isTrue();
		assertThat(recovered.inArrears()).isFalse();
		assertThat(recovered.treasuryAfterMg()).isEqualTo(1_000);
	}

	@Test
	void settlementRejectsNegativeInputsAndOverflow() {
		assertThatThrownBy(() -> Treasury.settle(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Treasury.settle(Long.MAX_VALUE, 1, 0)).isInstanceOf(ArithmeticException.class);
	}

	// --- construction availability ----------------------------------------------------------

	@Test
	void aTier1WorkshopCosts40GoldAndIsAvailableImmediately() {
		assertThat(V1.tier(1).buildCostMg()).isEqualTo(40_000);
		assertThat(V1.tier(1).constructible()).isTrue();
		assertThat(Construction.blockers(request(1, 0, 40_000, false), V1)).isEmpty();
	}

	@Test
	void tier2IsCalculatedButLockedForConstruction() {
		assertThat(Construction.blockers(request(2, 0, 1_000_000, false), V1)).containsExactly(TIER_LOCKED);
		assertThat(Construction.blockers(request(3, 0, 1_000_000, false), V1)).containsExactly(TIER_LOCKED);
	}

	@Test
	void theCityLimitCountsProjectsToo() {
		assertThat(Construction.blockers(request(1, 5, 40_000, false), V1)).isEmpty();
		assertThat(Construction.blockers(request(1, 6, 40_000, false), V1)).containsExactly(WORKSHOP_LIMIT_REACHED);
	}

	@Test
	void unaffordableAndInArrearsAreBlocked() {
		assertThat(Construction.blockers(request(1, 0, 39_999, false), V1)).containsExactly(INSUFFICIENT_GOLD);
		assertThat(Construction.blockers(request(1, 0, 100_000, true), V1)).containsExactly(IN_ARREARS);
	}

	@Test
	void onlyTheOwnerWhoAlsoControlsTheCityMayBuild() {
		var occupiedByOstra = new Construction.Request(varn, varn, ostra, true, 1, 0, 100_000, false);
		var occupierBuilding = new Construction.Request(ostra, varn, ostra, true, 1, 0, 100_000, false);
		var foreignCity = new Construction.Request(varn, ostra, ostra, true, 1, 0, 100_000, false);

		assertThat(Construction.blockers(occupiedByOstra, V1)).containsExactly(OCCUPIED);
		assertThat(Construction.blockers(occupierBuilding, V1)).containsExactly(NOT_OWNER);
		assertThat(Construction.blockers(foreignCity, V1)).containsExactly(NOT_OWNER);
	}

	@Test
	void everyReasonIsReported() {
		var hopeless = new Construction.Request(varn, ostra, ostra, false, 2, 6, 0, true);

		assertThat(Construction.blockers(hopeless, V1))
			.containsExactly(NOT_YOUR_TURN, NOT_OWNER, TIER_LOCKED, WORKSHOP_LIMIT_REACHED, IN_ARREARS, INSUFFICIENT_GOLD);
	}

	// --- construction timing (Q-E8) ---------------------------------------------------------

	@Test
	void aOneRoundProjectOrderedInRoundNCompletesAtTheEndOfRoundNAndEarnsFromNPlus1() {
		int completes = Construction.completesAfterRound(3, 1);

		assertThat(completes).isEqualTo(3);
		assertThat(Construction.operationalFromRound(completes)).isEqualTo(4);
	}

	@Test
	void longerProjectsCompleteAtRoundNPlusDMinus1() {
		assertThat(Construction.completesAfterRound(3, 2)).isEqualTo(4);
		assertThat(Construction.completesAfterRound(1, 5)).isEqualTo(5);
		assertThatThrownBy(() -> Construction.completesAfterRound(0, 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void prioritySortsTiersByProductivity() {
		assertThat(V1.tiersByPriority().keySet()).containsExactly(2, 1);
	}

	private Construction.Request request(int tier, int existing, long treasury, boolean arrears) {
		return new Construction.Request(varn, varn, varn, true, tier, existing, treasury, arrears);
	}

}
