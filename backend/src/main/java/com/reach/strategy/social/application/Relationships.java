package com.reach.strategy.social.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.reach.strategy.social.domain.FriendStore;

import org.springframework.stereotype.Component;

/**
 * How the viewer relates to other players, for profiles and search results. BLOCKED means the
 * viewer blocked them; that someone blocked the viewer is not revealed (requests and invitations
 * to them simply fail with PLAYER_UNAVAILABLE).
 */
@Component
class Relationships {

	enum Relation {
		YOU, NONE, FRIEND, REQUEST_SENT, REQUEST_RECEIVED, BLOCKED
	}

	private final FriendStore store;

	Relationships(FriendStore store) {
		this.store = store;
	}

	Relation between(UUID viewer, UUID other) {
		return of(viewer, Set.of(other)).get(other);
	}

	/** Three small queries for any number of players (no per-player lookups). */
	Map<UUID, Relation> of(UUID viewer, Collection<UUID> others) {
		Set<UUID> friends = new HashSet<>();
		store.friendsOf(viewer).forEach(f -> friends.add(f.playerId()));
		Set<UUID> blocked = new HashSet<>(store.blockedBy(viewer));
		Map<UUID, Relation> pending = new HashMap<>();
		store.pendingFor(viewer)
			.forEach(r -> pending.put(r.senderId().equals(viewer) ? r.recipientId() : r.senderId(),
					r.senderId().equals(viewer) ? Relation.REQUEST_SENT : Relation.REQUEST_RECEIVED));
		Map<UUID, Relation> result = new HashMap<>();
		for (UUID other : others) {
			result.put(other, other.equals(viewer) ? Relation.YOU
					: blocked.contains(other) ? Relation.BLOCKED
							: friends.contains(other) ? Relation.FRIEND : pending.getOrDefault(other, Relation.NONE));
		}
		return result;
	}

}
