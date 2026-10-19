package com.reach.strategy.military.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import com.reach.strategy.military.rules.MilitaryRules;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Military balance values per ruleset version, from {@code rulesets/<version>/military.json}. A
 * game keeps the version it was created with, so new balance means a new version.
 */
@Component
public class MilitaryRulesets {

	private final Map<String, MilitaryRules> byVersion;

	MilitaryRulesets(JsonMapper jsonMapper) throws IOException {
		Map<String, MilitaryRules> loaded = new HashMap<>();
		for (Resource resource : new PathMatchingResourcePatternResolver()
			.getResources("classpath*:rulesets/*/military.json")) {
			try (InputStream in = resource.getInputStream()) {
				MilitaryRules rules = jsonMapper.readValue(in, MilitaryRules.class);
				if (loaded.put(rules.version(), rules) != null) {
					throw new IllegalStateException("Duplicate military ruleset " + rules.version());
				}
			}
		}
		if (loaded.isEmpty()) {
			throw new IllegalStateException("No military ruleset found on the classpath");
		}
		this.byVersion = Map.copyOf(loaded);
	}

	public MilitaryRules rules(String version) {
		MilitaryRules rules = byVersion.get(version);
		if (rules == null) {
			throw new IllegalStateException("Unknown military ruleset version " + version);
		}
		return rules;
	}

}
