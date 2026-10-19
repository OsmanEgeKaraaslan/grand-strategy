package com.reach.strategy.game.web;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.reach.strategy.game.application.GameResultService;
import com.reach.strategy.game.application.GameStateService;
import com.reach.strategy.game.application.TurnService;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.platform.web.ApiException;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/** A started game: its state, the sequential-turn commands and, once finished, its result. */
@RestController
@RequestMapping("/api/v1/games/{gameId}")
class PlayController {

	private final GameStateService gameState;
	private final TurnService turns;
	private final GameResultService results;

	PlayController(GameStateService gameState, TurnService turns, GameResultService results) {
		this.results = results;
		this.gameState = gameState;
		this.turns = turns;
	}

	/**
	 * Polled by clients. The ETag is the game's state version and {@code no-cache} makes browsers
	 * revalidate, so unchanged polls are answered with an empty 304.
	 */
	@GetMapping("/state")
	ResponseEntity<GameStateService.GameStateView> state(CurrentPlayer me, @PathVariable UUID gameId, WebRequest request) {
		GameStateService.GameStateView view = gameState.state(me, gameId);
		String etag = "\"" + view.stateVersion() + "\"";
		if (request.checkNotModified(etag)) {
			return null;
		}
		return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noCache().cachePrivate()).body(view);
	}

	/**
	 * Ends the caller's turn. {@code Idempotency-Key} identifies the user's intent: retrying with
	 * the same key never ends a second turn. {@code expectedTurnNumber} must match the current turn.
	 */
	@PostMapping("/turn/end")
	TurnService.TurnResult endTurn(CurrentPlayer me, @PathVariable UUID gameId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody EndTurnRequest request) {
		if (idempotencyKey == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
					"The Idempotency-Key header (a UUID) is required for game commands.");
		}
		return turns.endTurn(me, gameId, idempotencyKey, request.expectedTurnNumber());
	}

	/** Final result of a finished game (winner or draw, standings, statistics); 409 before the end. */
	@GetMapping("/result")
	GameResultService.ResultView result(CurrentPlayer me, @PathVariable UUID gameId) {
		return results.result(me, gameId);
	}

	record EndTurnRequest(@NotNull Integer expectedTurnNumber) {
	}

}
