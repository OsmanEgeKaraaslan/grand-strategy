package com.reach.strategy.platform.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Browser origins allowed to call the API (the React frontend). Empty means no cross-origin
 * access. Authentication uses bearer tokens, not cookies, so credentials are never allowed.
 */
@ConfigurationProperties("strategy.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
	}

}
