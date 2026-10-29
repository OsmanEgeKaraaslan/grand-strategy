package com.reach.strategy.platform.ratelimit;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory token buckets, one per (policy, account). Single-instance by design: with several API
 * instances each enforces its own budget (docs/SECURITY_AUDIT.md, remaining risks). Memory is bounded
 * by {@link RateLimitProperties#maxTrackedKeys()}: once exceeded, buckets that have refilled
 * completely are forgotten (forgetting a full bucket changes nothing), and if that is not enough all
 * buckets are reset — failing open rather than locking out legitimate players.
 */
public class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	private final RateLimitProperties properties;
	private final Clock clock;
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	public RateLimiter(RateLimitProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	/** Whether the policy exists; endpoints naming an unknown policy are a configuration error. */
	public boolean knows(String policyName) {
		return properties.policies().containsKey(policyName);
	}

	/**
	 * Takes one request from the account's budget.
	 *
	 * @return 0 if allowed, otherwise the whole seconds until the next request would be allowed (>= 1)
	 */
	public long tryAcquire(String policyName, String accountKey) {
		RateLimitProperties.Policy policy = properties.policies().get(policyName);
		if (!properties.enabled() || policy == null) {
			return 0;
		}
		long now = clock.millis();
		if (buckets.size() >= properties.maxTrackedKeys()) {
			evict(now);
		}
		Bucket bucket = buckets.computeIfAbsent(policyName + '|' + accountKey, key -> new Bucket(policy, now));
		return bucket.tryAcquire(now);
	}

	private void evict(long now) {
		buckets.values().removeIf(bucket -> bucket.isFull(now));
		if (buckets.size() >= properties.maxTrackedKeys()) {
			log.warn("Rate limiter tracks {} active buckets (limit {}); resetting all", buckets.size(),
					properties.maxTrackedKeys());
			buckets.clear();
		}
	}

	int trackedBuckets() {
		return buckets.size();
	}

	/** Continuous refill: capacity tokens per window, i.e. one token every window/capacity. */
	private static final class Bucket {

		private final double capacity;
		private final double tokensPerMilli;
		private double tokens;
		private long updatedAt;

		Bucket(RateLimitProperties.Policy policy, long now) {
			this.capacity = policy.capacity();
			this.tokensPerMilli = policy.capacity() / (double) policy.window().toMillis();
			this.tokens = capacity;
			this.updatedAt = now;
		}

		synchronized long tryAcquire(long now) {
			refill(now);
			if (tokens >= 1) {
				tokens -= 1;
				return 0;
			}
			return Math.max(1, (long) Math.ceil((1 - tokens) / tokensPerMilli / 1000));
		}

		synchronized boolean isFull(long now) {
			refill(now);
			return tokens >= capacity;
		}

		private void refill(long now) {
			if (now > updatedAt) {
				tokens = Math.min(capacity, tokens + (now - updatedAt) * tokensPerMilli);
				updatedAt = now;
			}
		}

	}

}
