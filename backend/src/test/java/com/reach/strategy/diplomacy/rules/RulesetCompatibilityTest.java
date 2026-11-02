package com.reach.strategy.diplomacy.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import com.reach.strategy.ai.rules.AiRules;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Ruleset v8 adds switches and AI peace values; every older ruleset must still load (strictly, as the
 * application reads them) and keep its old behaviour: the new switches off, no v8 peace rules.
 */
class RulesetCompatibilityTest {

	private static final JsonMapper STRICT = JsonMapper.builder()
		.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	private static <T> T load(String path, Class<T> type) throws IOException {
		try (InputStream in = RulesetCompatibilityTest.class.getResourceAsStream(path)) {
			return STRICT.readValue(in, type);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "v5", "v6", "v7" })
	void olderDiplomacyRulesetsLoadWithTheV8SwitchesOff(String version) throws IOException {
		DiplomacyRules rules = load("/rulesets/" + version + "/diplomacy.json", DiplomacyRules.class);
		assertThat(rules.features().offensiveCallsToArms()).isFalse();
		assertThat(rules.features().truceAllowsCallsToArms()).isFalse();
	}

	@Test
	void v8TurnsOnOffensiveCallsAndTheTruceRuleAndKeepsEveryOtherValueOfV7() throws IOException {
		DiplomacyRules v7 = load("/rulesets/v7/diplomacy.json", DiplomacyRules.class);
		DiplomacyRules v8 = load("/rulesets/v8/diplomacy.json", DiplomacyRules.class);
		assertThat(v8.features().offensiveCallsToArms()).isTrue();
		assertThat(v8.features().truceAllowsCallsToArms()).isTrue();
		assertThat(v8.truceRounds()).isEqualTo(v7.truceRounds());
		assertThat(v8.warScore()).isEqualTo(v7.warScore());
		assertThat(v8.victory()).isEqualTo(v7.victory());
	}

	@Test
	void onlyV8HasThePeaceEvaluationAndEverythingElseOfTheAiIsUnchanged() throws IOException {
		AiRules v7 = load("/rulesets/v7/ai.json", AiRules.class);
		AiRules v8 = load("/rulesets/v8/ai.json", AiRules.class);
		assertThat(v7.peace()).isNull();
		assertThat(v8.peace()).isNotNull();
		assertThat(v8.diplomacy()).isEqualTo(v7.diplomacy());
		assertThat(v8.military()).isEqualTo(v7.military());
		assertThat(v8.economy()).isEqualTo(v7.economy());
		assertThat(v8.personalities()).isEqualTo(v7.personalities());
	}

}
