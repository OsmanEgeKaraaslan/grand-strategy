package com.reach.strategy.game.domain;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Secret lobby invitation code: 12 characters from a 32-symbol alphabet without look-alikes
 * (no I, O, 0, 1), i.e. 60 bits from {@link SecureRandom} — not guessable by enumeration.
 * Displayed as {@code ABCD-EFGH-JKLM}; input is accepted case-insensitively with or without
 * separators.
 */
public record InviteCode(String value) {

	static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	static final int LENGTH = 12;

	private static final Pattern FORMAT = Pattern.compile("[A-HJ-NP-Z2-9]{" + LENGTH + "}");
	private static final SecureRandom RANDOM = new SecureRandom();

	public InviteCode {
		if (value == null || !FORMAT.matcher(value).matches()) {
			throw new IllegalArgumentException("Malformed invite code");
		}
	}

	public static InviteCode generate() {
		StringBuilder code = new StringBuilder(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return new InviteCode(code.toString());
	}

	/** Normalizes user input; empty when it cannot be a valid code. */
	public static Optional<InviteCode> parse(String input) {
		if (input == null) {
			return Optional.empty();
		}
		String normalized = input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		return FORMAT.matcher(normalized).matches() ? Optional.of(new InviteCode(normalized)) : Optional.empty();
	}

	public String formatted() {
		return value.substring(0, 4) + "-" + value.substring(4, 8) + "-" + value.substring(8);
	}

}
