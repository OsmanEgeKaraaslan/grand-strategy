package com.reach.strategy.research.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.reach.strategy.research.rules.InstitutionType.Pool;
import com.reach.strategy.research.rules.ResearchRules.Track;
import org.junit.jupiter.api.Test;

/** The immutable research state (ENGINE_DESIGN S3): points, levels, institutions and doctrines. */
class StudiesTest {

	static final UUID ME = new UUID(0, 1);
	static final UUID CITY = new UUID(0, 10);
	static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

	@Test
	void pointsAreGainedAndSpentPerPoolAndLevelsRiseOneAtATime() {
		Studies.Progress start = Studies.Progress.start(ME, "v8");
		assertThat(start.level(Track.MILITARY)).isEqualTo(1);
		assertThat(start.level(Track.CIVILIAN)).isEqualTo(1);

		Studies.Progress p = start.add(Pool.MILITARY, 30).add(Pool.DOCTRINE, 5).spend(Pool.MILITARY, 20)
			.raise(Track.MILITARY, 2);

		assertThat(p.points(Pool.MILITARY)).isEqualTo(10);
		assertThat(p.points(Pool.CIVILIAN)).isZero();
		assertThat(p.points(Pool.DOCTRINE)).isEqualTo(5);
		assertThat(p.level(Track.MILITARY)).isEqualTo(2);
		assertThat(p.level(Track.CIVILIAN)).isEqualTo(1);
		assertThatThrownBy(() -> p.raise(Track.MILITARY, 4)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> p.spend(Pool.MILITARY, 11)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> p.add(Pool.MILITARY, -1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anInstitutionCompletesOnceAndRowsAreNeverReplacedByMistake() {
		Studies.Institution project = Studies.Institution.project(new UUID(0, 100), CITY, InstitutionType.values()[0], 2,
				4, 3_000, "v8", NOW);
		assertThat(project.active()).isFalse();
		assertThat(project.completed().active()).isTrue();
		assertThatThrownBy(() -> project.completed().completed()).isInstanceOf(IllegalStateException.class);

		Studies studies = Studies.empty().begin(Studies.Progress.start(ME, "v8")).found(project);
		assertThatThrownBy(() -> studies.found(project)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> studies.begin(Studies.Progress.start(ME, "v8")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> studies.update(Studies.Progress.start(new UUID(0, 9), "v8")))
			.isInstanceOf(IllegalArgumentException.class);

		Studies after = studies.update(project.completed())
			.choose(new Studies.Doctrine(new UUID(0, 200), ME, "SIEGE_ENGINEERING", 1, 5, 3, NOW));
		assertThat(after.institution(project.id()).orElseThrow().active()).isTrue();
		assertThat(after.doctrines()).hasSize(1);
		assertThat(after.progressOf(ME).orElseThrow()).isSameAs(studies.progressOf(ME).orElseThrow());
		assertThat(Studies.of(List.of(), List.of(), List.of()).progress()).isEmpty();
	}

}
