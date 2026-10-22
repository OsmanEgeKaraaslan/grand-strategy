package com.reach.strategy.identity.domain;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A player's unique public handle: 3–20 characters, lowercase letters, digits and '_', starting with
 * a letter. Input is matched case-insensitively (it is lowercased), so "Aldric" and "aldric" are the
 * same handle; the database's unique index is the race-free backstop. Reserved system names and the
 * {@code player_} prefix of migrated accounts cannot be chosen.
 */
public record Username(String value) {

	public static final int MIN_LENGTH = 3;
	public static final int MAX_LENGTH = 20;

	private static final Pattern ALLOWED = Pattern.compile("[a-z][a-z0-9_]{2,19}");
	private static final String GENERATED_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	/** Kept in step with the reserved list of migration V16. */
	public static final Set<String> RESERVED = Set.of("admin", "administrator", "system", "root", "support", "moderator",
			"mod", "staff", "official", "ai", "computer", "npc", "null", "undefined", "anonymous", "me", "you",
			"everyone", "api", "help", "guest", "server", "game", "sundered_reach");

	public Username {
		if (value == null || !ALLOWED.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid username");
		}
	}

	/** Why a requested handle cannot be used, or empty when it can. */
	public enum Problem {
		INVALID, RESERVED
	}

	/** Normalizes player input (trimmed, lowercased) and checks it. */
	public static Optional<Username> parse(String raw) {
		return check(raw).isEmpty() ? Optional.of(new Username(normalize(raw))) : Optional.empty();
	}

	public static Optional<Problem> check(String raw) {
		if (raw == null) {
			return Optional.of(Problem.INVALID);
		}
		String normalized = normalize(raw);
		if (!ALLOWED.matcher(normalized).matches()) {
			return Optional.of(Problem.INVALID);
		}
		if (RESERVED.contains(normalized) || normalized.startsWith("player_")) {
			return Optional.of(Problem.RESERVED);
		}
		return Optional.empty();
	}

	/** Lookup form of a handle typed by someone else (no reservation check: reserved names just do not exist). */
	public static String normalize(String raw) {
		return raw.strip().toLowerCase(Locale.ROOT);
	}

	/** A handle suggested from a display name ("Aldric the Bold" → "aldric_the_bold"), if one fits. */
	public static Optional<Username> suggestedFrom(String displayName) {
		if (displayName == null) {
			return Optional.empty();
		}
		String candidate = displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+", "");
		if (candidate.length() > MAX_LENGTH) {
			candidate = candidate.substring(0, MAX_LENGTH);
		}
		return parse(candidate);
	}

	/** A placeholder for accounts without a usable suggestion, e.g. {@code commander_7kq2xm}. */
	public static Username generated() {
		StringBuilder name = new StringBuilder("commander_");
		for (int i = 0; i < 6; i++) {
			name.append(GENERATED_ALPHABET.charAt(RANDOM.nextInt(GENERATED_ALPHABET.length())));
		}
		return new Username(name.toString());
	}

}
