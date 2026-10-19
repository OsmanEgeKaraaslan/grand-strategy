package com.reach.strategy.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.reach.strategy.identity.domain.Player;

public record PlayerProfile(UUID id, String displayName, Instant createdAt, Instant updatedAt) {

	static PlayerProfile of(Player player) {
		return new PlayerProfile(player.id(), player.displayName(), player.createdAt(), player.updatedAt());
	}

}
