package com.reach.strategy.game.web;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.game.application.GameModerationService;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.platform.ratelimit.RateLimited;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Game moderation (Q-T3, Q-MOD1). Administrators only; the service checks it on every call. */
@RateLimited("admin")
@RestController
@RequestMapping("/api/v1/admin/games")
class AdminGamesController {

	private final GameModerationService moderation;

	AdminGamesController(GameModerationService moderation) {
		this.moderation = moderation;
	}

	@GetMapping
	List<GameModerationService.AdminGame> unfinished(CurrentPlayer me) {
		return moderation.unfinished(me);
	}

	@PostMapping("/{gameId}/abandon")
	GameModerationService.AdminGame abandon(CurrentPlayer me, @PathVariable UUID gameId,
			@Valid @RequestBody AbandonRequest request) {
		return moderation.abandon(me, gameId, request.reason());
	}

	/** Recorded in the moderation log only. */
	record AbandonRequest(@NotBlank @Size(min = 3, max = 200) String reason) {
	}

}
