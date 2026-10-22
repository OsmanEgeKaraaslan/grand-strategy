package com.reach.strategy.identity.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
 * The authenticated player's own account. Only the owner reads or changes it: the account is the
 * one behind the access token, never an id from the request. Public profiles of anyone (including
 * oneself) are served by the social module.
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
		return playerService.update(me.id(), new PlayerService.ProfileChanges(request.displayName(), request.username(),
				request.emblem(), request.matchHistoryPublic()));
	}

	/** Every field is optional; at least one must be present. */
	record UpdateProfileRequest(@Size(min = 1, max = 64) String displayName, @Size(min = 1, max = 32) String username,
			@Size(min = 1, max = 16) String emblem, Boolean matchHistoryPublic) {

		@AssertTrue(message = "Change at least one field.")
		boolean isAnyFieldPresent() {
			return displayName != null || username != null || emblem != null || matchHistoryPublic != null;
		}

	}

}
