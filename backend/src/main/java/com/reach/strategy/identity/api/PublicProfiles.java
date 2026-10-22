package com.reach.strategy.identity.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to the intentionally public part of human accounts (Phase 12A): handle, display
 * name, emblem, creation date and the match-history visibility setting. Never Supabase ids, emails
 * or other account data. Computer accounts are not profiles and are never returned.
 */
public interface PublicProfiles {

	/** Exact, case-insensitive lookup. */
	Optional<PublicPlayer> byUsername(String username);

	/** Exact match first, then at most {@code limit} handles starting with the query (3+ characters). */
	List<PublicPlayer> search(String query, int limit);

	Map<UUID, PublicPlayer> byIds(Collection<UUID> playerIds);

	record PublicPlayer(UUID id, String username, String displayName, String emblem, Instant memberSince,
			boolean matchHistoryPublic) {
	}

}
