package com.reach.strategy.platform.health;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness endpoint for clients and uptime checks. It reports that the API process is
 * serving requests; dependency health (database) is exposed separately through
 * {@code /actuator/health/readiness} for the deployment platform.
 */
@RestController
@RequestMapping("/api/v1/health")
class HealthController {

	private final String service;
	private final String version;
	private final Clock clock;

	HealthController(@Value("${spring.application.name}") String service, ObjectProvider<BuildProperties> buildProperties,
			Clock clock) {
		this.service = service;
		BuildProperties build = buildProperties.getIfAvailable();
		this.version = build != null ? build.getVersion() : "unknown";
		this.clock = clock;
	}

	@GetMapping
	HealthResponse health() {
		return new HealthResponse("UP", service, version, Instant.now(clock));
	}

	record HealthResponse(String status, String service, String version, Instant timestamp) {
	}

}
