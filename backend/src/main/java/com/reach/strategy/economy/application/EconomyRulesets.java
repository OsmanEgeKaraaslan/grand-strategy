package com.reach.strategy.economy.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import com.reach.strategy.economy.rules.EconomyRules;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Economy balance values per ruleset version, loaded once from {@code rulesets/<version>/economy.json}
 * and validated at start-up. A game always uses the version it was created with, so changing
 * balance means adding a new version, never editing an existing file.
 */
@Component
public class EconomyRulesets {

	private final Map<String, EconomyRules> byVersion;

	EconomyRulesets(JsonMapper jsonMapper) throws IOException {
		Map<String, EconomyRules> loaded = new HashMap<>();
		for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:rulesets/*/economy.json")) {
			try (InputStream in = resource.getInputStream()) {
				EconomyRules rules = jsonMapper.readValue(in, EconomyRules.class);
				if (loaded.put(rules.version(), rules) != null) {
					throw new IllegalStateException("Duplicate economy ruleset " + rules.version());
				}
			}
		}
		if (loaded.isEmpty()) {
			throw new IllegalStateException("No economy ruleset found on the classpath");
		}
		this.byVersion = Map.copyOf(loaded);
	}

	public EconomyRules rules(String version) {
		EconomyRules rules = byVersion.get(version);
		if (rules == null) {
			throw new IllegalStateException("Unknown ruleset version " + version);
		}
		return rules;
	}

}
