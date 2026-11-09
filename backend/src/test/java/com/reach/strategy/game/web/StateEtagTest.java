package com.reach.strategy.game.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StateEtagTest {

	@Test
	void matchesTheExactTagAWeakTagAListAndTheWildcard() {
		assertThat(PlayController.matches("\"42\"", "\"42\"")).isTrue();
		assertThat(PlayController.matches("W/\"42\"", "\"42\"")).isTrue();
		assertThat(PlayController.matches("\"41\", \"42\"", "\"42\"")).isTrue();
		assertThat(PlayController.matches("*", "\"42\"")).isTrue();
	}

	@Test
	void doesNotMatchAnotherVersionOrAMissingHeader() {
		assertThat(PlayController.matches("\"41\"", "\"42\"")).isFalse();
		assertThat(PlayController.matches("42", "\"42\"")).isFalse();
		assertThat(PlayController.matches(null, "\"42\"")).isFalse();
		assertThat(PlayController.matches(" ", "\"42\"")).isFalse();
	}

}
