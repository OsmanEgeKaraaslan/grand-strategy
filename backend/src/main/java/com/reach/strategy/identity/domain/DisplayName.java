package com.reach.strategy.identity.domain;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A validated public player name: 3–24 letters, digits, spaces, '_' or '-', trimmed, with
 * single inner spaces. Uniqueness (case-insensitive) is enforced by the database.
 */
public record DisplayName(String value) {

	public static final int MIN_LENGTH = 3;
	public static final int MAX_LENGTH = 24;

	private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{N}_-]+( [\\p{L}\\p{N}_-]+)*");
	private static final String SUFFIX_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	public DisplayName {
		if (!isValid(value)) {
			throw new IllegalArgumentException("Invalid display name");
		}
	}

	public static boolean isValid(String value) {
		return value != null && value.length() >= MIN_LENGTH && value.length() <= MAX_LENGTH
				&& ALLOWED.matcher(value).matches();
	}

	/** Normalizes user input; empty if it cannot become a valid name. */
	public static Optional<DisplayName> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String normalized = raw.strip().replaceAll("\\s+", " ");
		return isValid(normalized) ? Optional.of(new DisplayName(normalized)) : Optional.empty();
	}

	/** Placeholder for new accounts that did not choose a name, e.g. {@code Commander-7KQ2XM}. */
	public static DisplayName generated() {
		StringBuilder name = new StringBuilder("Commander-");
		for (int i = 0; i < 6; i++) {
			name.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
		}
		return new DisplayName(name.toString());
	}

}
