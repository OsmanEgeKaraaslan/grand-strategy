package com.reach.strategy.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

	/** A clock the test moves by hand. */
	static final class ManualClock extends Clock {

		Instant now = Instant.parse("2026-10-22T10:00:00Z");

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

	}

	private static RateLimitProperties properties(boolean enabled, int maxKeys) {
		return new RateLimitProperties(enabled, maxKeys,
				Map.of("api", new RateLimitProperties.Policy(3, Duration.ofSeconds(30))));
	}

	@Test
	void allowsTheCapacityThenRefusesWithTheWaitUntilTheNextToken() {
		ManualClock clock = new ManualClock();
		RateLimiter limiter = new RateLimiter(properties(true, 100), clock);

		assertThat(limiter.tryAcquire("api", "a")).isZero();
		assertThat(limiter.tryAcquire("api", "a")).isZero();
		assertThat(limiter.tryAcquire("api", "a")).isZero();
		// One token every 10 s.
		assertThat(limiter.tryAcquire("api", "a")).isEqualTo(10);

		clock.advance(Duration.ofSeconds(4));
		assertThat(limiter.tryAcquire("api", "a")).isEqualTo(6);
		clock.advance(Duration.ofSeconds(6));
		assertThat(limiter.tryAcquire("api", "a")).isZero();
	}

	@Test
	void accountsHaveSeparateBudgets() {
		RateLimiter limiter = new RateLimiter(properties(true, 100), new ManualClock());
		for (int i = 0; i < 3; i++) {
			limiter.tryAcquire("api", "a");
		}
		assertThat(limiter.tryAcquire("api", "a")).isPositive();
		assertThat(limiter.tryAcquire("api", "b")).isZero();
	}

	@Test
	void refillNeverExceedsTheCapacity() {
		ManualClock clock = new ManualClock();
		RateLimiter limiter = new RateLimiter(properties(true, 100), clock);
		limiter.tryAcquire("api", "a");
		clock.advance(Duration.ofDays(1));
		for (int i = 0; i < 3; i++) {
			assertThat(limiter.tryAcquire("api", "a")).isZero();
		}
		assertThat(limiter.tryAcquire("api", "a")).isPositive();
	}

	@Test
	void disabledOrUnknownPoliciesNeverLimit() {
		RateLimiter disabled = new RateLimiter(properties(false, 100), new ManualClock());
		RateLimiter enabled = new RateLimiter(properties(true, 100), new ManualClock());
		for (int i = 0; i < 10; i++) {
			assertThat(disabled.tryAcquire("api", "a")).isZero();
			assertThat(enabled.tryAcquire("unknown", "a")).isZero();
		}
	}

	@Test
	void memoryStaysBoundedByForgettingIdleBucketsFirst() {
		ManualClock clock = new ManualClock();
		RateLimiter limiter = new RateLimiter(properties(true, 3), clock);
		limiter.tryAcquire("api", "a");
		limiter.tryAcquire("api", "b");
		limiter.tryAcquire("api", "c");
		clock.advance(Duration.ofMinutes(1));
		limiter.tryAcquire("api", "d");
		assertThat(limiter.trackedBuckets()).isEqualTo(1);

		// All buckets busy: the limiter resets rather than growing or locking players out.
		limiter.tryAcquire("api", "e");
		limiter.tryAcquire("api", "f");
		limiter.tryAcquire("api", "g");
		assertThat(limiter.trackedBuckets()).isLessThanOrEqualTo(3);
	}

	@Test
	void policiesMustBeUsable() {
		assertThatThrownBy(() -> new RateLimitProperties(true, 10,
				Map.of("api", new RateLimitProperties.Policy(0, Duration.ofMinutes(1)))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimitProperties(true, 10,
				Map.of("api", new RateLimitProperties.Policy(5, Duration.ZERO))))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
