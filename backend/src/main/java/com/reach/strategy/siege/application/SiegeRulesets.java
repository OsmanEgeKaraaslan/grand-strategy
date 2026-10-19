package com.reach.strategy.siege.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.reach.strategy.siege.rules.SiegeRules;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Siege and fortification values per ruleset version ({@code rulesets/<version>/siege.json}).
 * Versions before v3 have none: their games have no fortresses, so every province there is
 * occupied by the simple Phase 5 rule, exactly as before.
 */
@Component
public class SiegeRulesets {

	private final Map<String, SiegeRules> byVersion;

	SiegeRulesets(JsonMapper jsonMapper) throws IOException {
		Map<String, SiegeRules> loaded = new HashMap<>();
		for (Resource resource : new PathMatchingResourcePatternResolver()
			.getResources("classpath*:rulesets/*/siege.json")) {
			try (InputStream in = resource.getInputStream()) {
				SiegeRules rules = jsonMapper.readValue(in, SiegeRules.class);
				if (loaded.put(rules.version(), rules) != null) {
					throw new IllegalStateException("Duplicate siege ruleset " + rules.version());
				}
			}
		}
		this.byVersion = Map.copyOf(loaded);
	}

	public Optional<SiegeRules> find(String version) {
		return Optional.ofNullable(byVersion.get(version));
	}

	public SiegeRules rules(String version) {
		return find(version).orElseThrow(() -> new IllegalStateException("Ruleset " + version + " has no sieges"));
	}

}
