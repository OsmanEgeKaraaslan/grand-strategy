package com.reach.strategy.game.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.reach.strategy.game.api.RoundPhase;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Builds the round processor. The order of phases is defined solely by {@link RoundPhase.Step}
 * (docs/TURN_SYSTEM.md §5), never by bean ordering (AGENTS.md §2); a step may have at most one
 * implementation.
 */
@Configuration
class RoundPhasesConfig {

	@Bean
	RoundProcessor roundProcessor(JdbcTemplate jdbc, List<RoundPhase> phases) {
		Map<RoundPhase.Step, RoundPhase> byStep = phases.stream()
			.collect(Collectors.toMap(RoundPhase::step, Function.identity(), (a, b) -> {
				throw new IllegalStateException("Two round phases for step " + a.step() + ": " + a + ", " + b);
			}));
		List<RoundPhase> ordered = byStep.values()
			.stream()
			.sorted(Comparator.comparing(RoundPhase::step))
			.toList();
		return new RoundProcessor(jdbc, ordered);
	}

}
