package com.reach.strategy.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.reach.strategy.identity.domain.Player;

/**
 * The authenticated player's own account view (GET/PATCH /api/v1/me). Contains settings only its
 * owner sees (whether the handle was chosen, history visibility); never credentials or email.
 */
public record PlayerProfile(UUID id, String username, String displayName, String emblem, boolean usernameChosen,
		boolean matchHistoryPublic, Instant createdAt, Instant updatedAt) {

	static PlayerProfile of(Player player) {
		return new PlayerProfile(player.id(), player.username(), player.displayName(), player.emblem(),
				player.usernameChosen(), player.matchHistoryPublic(), player.createdAt(), player.updatedAt());
	}

}
