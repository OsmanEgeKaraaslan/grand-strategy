package com.reach.strategy.identity.web;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.application.ModerationService;
import com.reach.strategy.platform.ratelimit.RateLimited;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Account moderation (Q-MOD1). Administrators only; the service checks it on every call. */
@RateLimited("admin")
@Validated
@RestController
@RequestMapping("/api/v1/admin/players")
class AdminPlayersController {

	private final ModerationService moderation;

	AdminPlayersController(ModerationService moderation) {
		this.moderation = moderation;
	}

	@GetMapping
	List<ModerationService.AdminPlayer> search(CurrentPlayer me, @RequestParam(defaultValue = "") @Size(max = 64) String query) {
		return moderation.search(me, query);
	}

	@PostMapping("/{playerId}/ban")
	ModerationService.AdminPlayer ban(CurrentPlayer me, @PathVariable UUID playerId, @Valid @RequestBody BanRequest request) {
		return moderation.ban(me, playerId, request.reason());
	}

	@PostMapping("/{playerId}/unban")
	ModerationService.AdminPlayer unban(CurrentPlayer me, @PathVariable UUID playerId) {
		return moderation.unban(me, playerId);
	}

	@PostMapping("/{playerId}/name-reset")
	ModerationService.AdminPlayer resetName(CurrentPlayer me, @PathVariable UUID playerId) {
		return moderation.resetName(me, playerId);
	}

	/** The reason is shown to the suspended player. */
	record BanRequest(@NotBlank @Size(min = 3, max = 200) String reason) {
	}

}
