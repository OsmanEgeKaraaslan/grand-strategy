package com.reach.strategy.identity.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.application.PlayerProfile;
import com.reach.strategy.identity.application.PlayerService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated player's own profile. There is deliberately no endpoint addressing a
 * profile by id: a player can only ever read or change their own.
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

	private final PlayerService playerService;

	MeController(PlayerService playerService) {
		this.playerService = playerService;
	}

	/** Also performs first-login provisioning (via {@link CurrentPlayer} resolution). */
	@GetMapping
	PlayerProfile me(CurrentPlayer me) {
		return playerService.profile(me.id());
	}

	@PatchMapping
	PlayerProfile update(CurrentPlayer me, @Valid @RequestBody UpdateProfileRequest request) {
		return playerService.rename(me.id(), request.displayName());
	}

	record UpdateProfileRequest(@NotBlank @Size(max = 64) String displayName) {
	}

}
