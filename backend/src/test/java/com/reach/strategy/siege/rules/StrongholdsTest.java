package com.reach.strategy.siege.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The immutable fortress and siege state (ENGINE_DESIGN S3). */
class StrongholdsTest {

	static final UUID CITY = new UUID(0, 10);
	static final UUID PROVINCE = new UUID(0, 20);
	static final UUID ATTACKER = new UUID(0, 1);
	static final UUID DEFENDER = new UUID(0, 2);
	static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

	static Strongholds.Fortress fortress() {
		return Strongholds.Fortress.starting(new UUID(0, 100), CITY, 1, 100, "v8", NOW);
	}

	@Test
	void anUpgradeRaisesTheLevelAndRestoresFullIntegrity() {
		Strongholds.Fortress upgrading = fortress().projectStarted(FortressWorks.Project.UPGRADE, 2, 3, 5, 9_000);
		assertThatThrownBy(() -> upgrading.projectStarted(FortressWorks.Project.REPAIR, null, 3, 4, 1))
			.isInstanceOf(IllegalStateException.class);

		Strongholds.Fortress done = upgrading.projectCompleted(200);

		assertThat(done.level()).isEqualTo(2);
		assertThat(done.integrity()).isEqualTo(200);
		assertThat(done.project()).isNull();
		assertThat(done.projectCostMg()).isZero();
	}

	@Test
	void bombardmentDamagesTheFortressAndCancelsAnUpgradeButNotARepair() {
		Strongholds.Fortress bombarded = fortress().projectStarted(FortressWorks.Project.UPGRADE, 2, 3, 5, 9_000)
			.bombarded(40);
		assertThat(bombarded.status()).isEqualTo(FortressWorks.Status.DAMAGED);
		assertThat(bombarded.integrity()).isEqualTo(40);
		assertThat(bombarded.project()).isNull();

		Strongholds.Fortress repairing = bombarded.projectStarted(FortressWorks.Project.REPAIR, null, 4, 5, 2_000);
		assertThat(repairing.bombarded(10).project()).isEqualTo(FortressWorks.Project.REPAIR);
		assertThat(repairing.upgradeCancelled()).isSameAs(repairing);
		assertThat(repairing.projectCompleted(100).status()).isEqualTo(FortressWorks.Status.ACTIVE);
		assertThat(Strongholds.Fortress.firstLevelProject(new UUID(0, 101), CITY, "v8", NOW).level()).isZero();
	}

	@Test
	void aSiegeAdvancesOncePerRoundAndEndsOnce() {
		Strongholds.Siege siege = Strongholds.Siege.start(new UUID(0, 300), PROVINCE, CITY, ATTACKER, DEFENDER, 1, 10, 2,
				5, NOW);
		Strongholds.Siege advanced = siege.advanced(2, 6, 50);

		assertThat(advanced.progress()).isEqualTo(6);
		assertThat(advanced.populationLost()).isEqualTo(50);
		assertThat(advanced.fallen()).isFalse();
		assertThat(advanced.processableIn(2)).isFalse();
		assertThatThrownBy(() -> advanced.advanced(2, 1, 0)).isInstanceOf(IllegalStateException.class);

		Strongholds.Siege fallen = advanced.advanced(3, 6, 40);
		assertThat(fallen.fallen()).isTrue();
		Strongholds.Siege won = fallen.succeeded(3, true, new UUID(0, 400));
		assertThat(won.status()).isEqualTo(Strongholds.Siege.Status.SUCCEEDED);
		assertThat(won.endReason()).isEqualTo(Strongholds.Siege.EndReason.CAPTURED);
		assertThat(won.endedRound()).isEqualTo(3);
		assertThatThrownBy(() -> won.lifted(4)).isInstanceOf(IllegalStateException.class);
		assertThat(siege.lifted(2).endReason()).isEqualTo(Strongholds.Siege.EndReason.BESIEGERS_GONE);
	}

	@Test
	void changesKeepTheOtherRowsAsTheyWere() {
		Strongholds before = Strongholds.of(List.of(fortress()), List.of());
		Strongholds.Siege siege = Strongholds.Siege.start(new UUID(0, 300), PROVINCE, CITY, ATTACKER, DEFENDER, 1, 10, 2,
				5, NOW);

		Strongholds after = before.with(siege);

		assertThat(after.fortress(new UUID(0, 100)).orElseThrow()).isSameAs(before.fortress(new UUID(0, 100)).orElseThrow());
		assertThat(after.sieges()).containsExactly(siege);
		assertThat(before.sieges()).isEmpty();
	}

}
