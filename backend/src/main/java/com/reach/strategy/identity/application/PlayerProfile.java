package com.reach.strategy.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.reach.strategy.identity.domain.Player;

/**
 * The authenticated player's own account view (GET/PATCH /api/v1/me). Contains settings only its
 * owner sees (whether the handle was chosen, history visibility, legal acceptance); never credentials
 * or email.
 */
public record PlayerProfile(UUID id, String username, String displayName, String emblem, boolean usernameChosen,
		boolean matchHistoryPublic, Instant createdAt, Instant updatedAt, Terms terms, boolean admin) {

	/**
	 * @param accepted the player accepted exactly the current versions
	 * @param acceptedTermsVersion the version last accepted, null if never (accounts older than Phase 15A)
	 */
	public record Terms(String currentTermsVersion, String currentPrivacyVersion, boolean accepted,
			String acceptedTermsVersion, String acceptedPrivacyVersion, Instant acceptedAt) {
	}

	/** @param admin the account may use the moderation tools (Q-MOD1); shown only to its owner */
	static PlayerProfile of(Player player, LegalTerms legal, boolean admin) {
		Terms terms = new Terms(legal.termsVersion(), legal.privacyVersion(),
				legal.isCurrent(player.termsVersion(), player.privacyVersion()), player.termsVersion(),
				player.privacyVersion(), player.termsAcceptedAt());
		return new PlayerProfile(player.id(), player.username(), player.displayName(), player.emblem(),
				player.usernameChosen(), player.matchHistoryPublic(), player.createdAt(), player.updatedAt(), terms, admin);
	}

}
