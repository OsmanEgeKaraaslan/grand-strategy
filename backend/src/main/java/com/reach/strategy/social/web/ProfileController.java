package com.reach.strategy.social.web;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.social.application.ProfileService;

import com.reach.strategy.platform.ratelimit.RateLimited;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public player profiles by username (signed-in players only, like every other endpoint). */
@RestController
@RequestMapping("/api/v1/profiles")
class ProfileController {

	private final ProfileService profiles;

	ProfileController(ProfileService profiles) {
		this.profiles = profiles;
	}

	@RateLimited("profile-reads")
	@GetMapping("/{username}")
	ProfileService.ProfileView profile(CurrentPlayer me, @PathVariable String username) {
		return profiles.profile(me, username);
	}

}
