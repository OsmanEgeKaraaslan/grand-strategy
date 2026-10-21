package com.reach.strategy.ai.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.reach.strategy.ai.rules.AiRules;
import com.reach.strategy.game.api.ComputerOpponents;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Computer-opponent values per ruleset version ({@code rulesets/<version>/ai.json}). A ruleset with
 * such a file gives every nation nobody chose a computer seat (v7); rulesets without one keep the
 * passive placeholders of Phase 10.
 */
@Component
class AiRulesets implements ComputerOpponents {

	private final Map<String, AiRules> byVersion;

	AiRulesets(JsonMapper jsonMapper) throws IOException {
		Map<String, AiRules> loaded = new HashMap<>();
		for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:rulesets/*/ai.json")) {
			try (InputStream in = resource.getInputStream()) {
				AiRules rules = jsonMapper.readValue(in, AiRules.class);
				if (loaded.put(rules.version(), rules) != null) {
					throw new IllegalStateException("Duplicate AI ruleset " + rules.version());
				}
			}
		}
		this.byVersion = Map.copyOf(loaded);
	}

	Optional<AiRules> find(String version) {
		return Optional.ofNullable(byVersion.get(version));
	}

	@Override
	public boolean enabled(String rulesetVersion) {
		return byVersion.containsKey(rulesetVersion);
	}

}
