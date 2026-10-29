package com.reach.strategy.game.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.game.api.Lobbies;
import com.reach.strategy.identity.api.AccountDeletionParticipant;

import org.springframework.stereotype.Component;

/**
 * Account deletion (Phase 15A) never touches games: a player still seated in an unfinished game (lobby
 * or running match) cannot delete the account, because the other players' game would stall on a seat
 * nobody can play. Finished games keep the anonymized player as a participant.
 */
@Component
class GameAccountDeletion implements AccountDeletionParticipant {

	private final Lobbies lobbies;

	GameAccountDeletion(Lobbies lobbies) {
		this.lobbies = lobbies;
	}

	@Override
	public Optional<String> blocker(UUID playerId) {
		int unfinished = lobbies.unfinishedGames(playerId);
		return unfinished == 0 ? Optional.empty()
				: Optional.of("You are still seated in " + unfinished + " unfinished " + (unfinished == 1 ? "game" : "games")
						+ " (lobbies included). Accounts can be deleted once every game has finished.");
	}

	@Override
	public void accountDeleted(UUID playerId, Instant now) {
		// Nothing to remove: finished games and their results stay intact.
	}

}
