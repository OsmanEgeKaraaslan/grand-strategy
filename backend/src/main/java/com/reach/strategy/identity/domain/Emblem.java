package com.reach.strategy.identity.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The predefined profile emblems (Phase 12A). Players pick one of these instead of uploading an
 * image, so there is no image storage or moderation. Kept in step with migration V16's check.
 */
public enum Emblem {

	CROWN, SHIELD, SWORDS, TOWER, FLAG, SCHOLAR, MOUNTAIN, WHEAT;

	public String key() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static Optional<Emblem> parse(String key) {
		return key == null ? Optional.empty()
				: Arrays.stream(values()).filter(e -> e.key().equals(key.strip().toLowerCase(Locale.ROOT))).findFirst();
	}

}
