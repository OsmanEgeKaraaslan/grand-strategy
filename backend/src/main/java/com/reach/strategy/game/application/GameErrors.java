package com.reach.strategy.game.application;

import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.platform.web.ApiException;

import org.springframework.http.HttpStatus;

/** Error responses shared by the game use cases (codes documented in docs/API_DESIGN.md §3). */
final class GameErrors {

	private GameErrors() {
	}

	/** Also used for games the caller is not a member of, so their existence is not revealed. */
	static ApiException gameNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "Game not found.");
	}

	static ApiException inviteCodeNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "INVITE_CODE_NOT_FOUND", "No game matches this invitation code.");
	}

	static ApiException alreadyStarted() {
		return new ApiException(HttpStatus.CONFLICT, "GAME_ALREADY_STARTED",
				"The game has started; nation choices are locked.");
	}

	static ApiException notInProgress() {
		return new ApiException(HttpStatus.CONFLICT, "GAME_NOT_IN_PROGRESS", "The game has not started.");
	}

	static ApiException finished() {
		return new ApiException(HttpStatus.CONFLICT, "GAME_FINISHED",
				"The game has finished; its final state can be viewed but not changed.");
	}

	static ApiException abandoned() {
		return new ApiException(HttpStatus.CONFLICT, "GAME_ABANDONED", "This game was closed by an administrator.");
	}

	static ApiException pausedForConference() {
		return new ApiException(HttpStatus.CONFLICT, "GAME_PAUSED_FOR_CONFERENCE",
				"A peace conference is in progress; normal play resumes when it is concluded or cancelled.");
	}

	static ApiException eliminated() {
		return new ApiException(HttpStatus.CONFLICT, "NATION_ELIMINATED",
				"Your nation has been eliminated; you can follow the game but no longer act.");
	}

	/** Commands need a running game: not started yet, or already finished. */
	static ApiException notPlayable(GameStatus status) {
		return switch (status) {
			case FINISHED -> finished();
			case ABANDONED -> abandoned();
			default -> notInProgress();
		};
	}

}
