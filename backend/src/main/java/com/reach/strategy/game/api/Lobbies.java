package com.reach.strategy.game.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Lobby facts and invitation admission for the social module (Phase 13A). Membership changes still
 * happen only here, under the game's row lock, with the same rules as joining by invitation code.
 */
public interface Lobbies {

	/** Names of those games the player is a member of; other games are absent (no existence leak). */
	Map<UUID, String> namesFor(UUID playerId, Collection<UUID> gameIds);

	Optional<LobbyFacts> facts(UUID gameId);

	/**
	 * Seats an invited player in the inviter's lobby, in the caller's transaction and under the game
	 * row lock, so simultaneous acceptances can never overfill it. Never throws for these outcomes.
	 */
	AdmitOutcome admitInvited(UUID gameId, UUID inviterId, UUID inviteeId);

	/** The faction of every human player seated in a game (key, name, colour), by player id. */
	Map<UUID, Faction> factions(UUID gameId);

	record Faction(String key, String name, String color) {
	}

	/** Games the player is seated in that have not finished (lobbies and running games). */
	int unfinishedGames(UUID playerId);

	/**
	 * @param status WAITING_FOR_PLAYERS, READY, IN_PROGRESS or FINISHED
	 * @param humanMemberIds human players seated in the game
	 */
	record LobbyFacts(UUID gameId, String name, String mapName, String status, UUID hostId, int playerCount,
			int maxPlayers, Set<UUID> humanMemberIds) {

		public boolean joinable() {
			return "WAITING_FOR_PLAYERS".equals(status) && playerCount < maxPlayers;
		}

	}

	enum AdmitOutcome {
		ADMITTED, ALREADY_MEMBER, GAME_FULL, ALREADY_STARTED, INVITER_NOT_HOST, NOT_FOUND
	}

}
