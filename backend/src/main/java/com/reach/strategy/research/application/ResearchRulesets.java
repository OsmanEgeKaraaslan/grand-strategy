package com.reach.strategy.research.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.reach.strategy.research.rules.ResearchRules;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Research values per ruleset version ({@code rulesets/<version>/research.json}). Versions before
 * v4 have none: their games have no institutions, no research and no technology effects.
 */
@Component
public class ResearchRulesets {

	private final Map<String, ResearchRules> byVersion;

	ResearchRulesets(JsonMapper jsonMapper) throws IOException {
		Map<String, ResearchRules> loaded = new HashMap<>();
		for (Resource resource : new PathMatchingResourcePatternResolver()
			.getResources("classpath*:rulesets/*/research.json")) {
			try (InputStream in = resource.getInputStream()) {
				ResearchRules rules = jsonMapper.readValue(in, ResearchRules.class);
				if (loaded.put(rules.version(), rules) != null) {
					throw new IllegalStateException("Duplicate research ruleset " + rules.version());
				}
			}
		}
		this.byVersion = Map.copyOf(loaded);
	}

	public Optional<ResearchRules> find(String version) {
		return Optional.ofNullable(byVersion.get(version));
	}

	public ResearchRules rules(String version) {
		return find(version).orElseThrow(() -> new IllegalStateException("Ruleset " + version + " has no research"));
	}

}
