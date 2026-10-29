package com.reach.strategy.platform.ratelimit;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-account request budgets (Phase 15B). Each policy is a token bucket holding {@code capacity}
 * requests that refills completely, evenly, over {@code window}. The {@code api} policy applies to
 * every API request; endpoints opt into further policies with {@link RateLimited}.
 *
 * @param enabled false turns every limit off (local experiments only)
 * @param maxTrackedKeys bound on remembered buckets; idle (full) buckets are dropped first
 * @param policies the budgets by name; an unknown name on an endpoint fails at startup
 */
@ConfigurationProperties("strategy.rate-limits")
public record RateLimitProperties(boolean enabled, int maxTrackedKeys, Map<String, Policy> policies) {

	public static final String GLOBAL_POLICY = "api";

	public RateLimitProperties {
		maxTrackedKeys = maxTrackedKeys > 0 ? maxTrackedKeys : 100_000;
		policies = policies == null ? Map.of() : Map.copyOf(policies);
		policies.forEach((name, policy) -> {
			if (policy.capacity() < 1 || policy.window() == null || policy.window().isNegative() || policy.window().isZero()) {
				throw new IllegalArgumentException("Rate-limit policy '" + name + "' needs capacity >= 1 and a positive window");
			}
		});
	}

	public record Policy(int capacity, Duration window) {
	}

}
