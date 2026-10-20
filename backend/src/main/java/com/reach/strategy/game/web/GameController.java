package com.reach.strategy.game.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.reach.strategy.game.application.GameSummary;
import com.reach.strategy.game.application.LobbyService;
import com.reach.strategy.game.application.LobbyView;
import com.reach.strategy.identity.api.CurrentPlayer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lobby: create, list, view, join, choose a nation, start. */
@RestController
@RequestMapping("/api/v1/games")
class GameController {

	private final LobbyService lobbyService;

	GameController(LobbyService lobbyService) {
		this.lobbyService = lobbyService;
	}

	@PostMapping
	ResponseEntity<LobbyView> create(CurrentPlayer me, @Valid @RequestBody CreateGameRequest request) {
		LobbyView lobby = lobbyService.create(me, request.name(), request.maxPlayers(), request.mapKey());
		return ResponseEntity.created(URI.create("/api/v1/games/" + lobby.id())).body(lobby);
	}

	@GetMapping
	List<GameSummary> myGames(CurrentPlayer me) {
		return lobbyService.myGames(me);
	}

	@GetMapping("/{gameId}")
	LobbyView lobby(CurrentPlayer me, @PathVariable UUID gameId) {
		return lobbyService.lobby(me, gameId);
	}

	@PostMapping("/join")
	LobbyView join(CurrentPlayer me, @Valid @RequestBody JoinGameRequest request) {
		return lobbyService.join(me, request.inviteCode());
	}

	/** "me" is the authenticated caller: a player can only ever choose their own nation. */
	@PutMapping("/{gameId}/players/me/nation")
	LobbyView chooseNation(CurrentPlayer me, @PathVariable UUID gameId, @Valid @RequestBody ChooseNationRequest request) {
		return lobbyService.chooseNation(me, gameId, request.nationId());
	}

	@PostMapping("/{gameId}/start")
	LobbyView start(CurrentPlayer me, @PathVariable UUID gameId) {
		return lobbyService.start(me, gameId);
	}

	/**
	 * @param maxPlayers human players (2–4 on the Grand Campaign; default 2)
	 * @param mapKey map to play (default: the configured default map)
	 */
	record CreateGameRequest(@NotBlank @Size(min = 3, max = 40) String name, @Min(2) @Max(8) Integer maxPlayers,
			@Size(max = 64) String mapKey) {
	}

	record JoinGameRequest(@NotBlank @Size(max = 32) String inviteCode) {
	}

	record ChooseNationRequest(@NotNull UUID nationId) {
	}

}
