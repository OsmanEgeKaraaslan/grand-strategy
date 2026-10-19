package com.reach.strategy.world.application;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the application with a structurally broken map (bad adjacency, neutral or
 * doubly-owned provinces, missing geometry, ...): games must never be created on one.
 */
@Component
class MapTemplateVerifier implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(MapTemplateVerifier.class);

	private final WorldService world;

	MapTemplateVerifier(WorldService world) {
		this.world = world;
	}

	@Override
	public void run(ApplicationArguments args) {
		Map<String, List<String>> problems = world.validateAllMaps();
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Invalid map templates: " + problems);
		}
		log.info("Map templates verified");
	}

}
