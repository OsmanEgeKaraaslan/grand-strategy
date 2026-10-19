package com.reach.strategy.diplomacy.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.reach.strategy.diplomacy.rules.DiplomacyRules;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Diplomacy values per ruleset version ({@code rulesets/<version>/diplomacy.json}). Versions
 * before v5 have none: their games keep the pre-diplomacy rule (permanent war, no game end).
 */
@Component
public class DiplomacyRulesets {

	private final Map<String, DiplomacyRules> byVersion;

	DiplomacyRulesets(JsonMapper jsonMapper) throws IOException {
		Map<String, DiplomacyRules> loaded = new HashMap<>();
		for (Resource resource : new PathMatchingResourcePatternResolver()
			.getResources("classpath*:rulesets/*/diplomacy.json")) {
			try (InputStream in = resource.getInputStream()) {
				DiplomacyRules rules = jsonMapper.readValue(in, DiplomacyRules.class);
				if (loaded.put(rules.version(), rules) != null) {
					throw new IllegalStateException("Duplicate diplomacy ruleset " + rules.version());
				}
			}
		}
		this.byVersion = Map.copyOf(loaded);
	}

	public Optional<DiplomacyRules> find(String version) {
		return Optional.ofNullable(byVersion.get(version));
	}

	public DiplomacyRules rules(String version) {
		return find(version).orElseThrow(() -> new IllegalStateException("Ruleset " + version + " has no diplomacy"));
	}

}
