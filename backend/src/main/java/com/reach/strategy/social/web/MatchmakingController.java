package com.reach.strategy.social.web;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.social.application.MatchmakingService;

import com.reach.strategy.platform.ratelimit.RateLimited;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's Quick Match search. Every action concerns the authenticated player's own ticket;
 * there is no id to address anyone else's. All actions are idempotent.
 */
@RateLimited("social-actions")
@RestController
@RequestMapping("/api/v1/matchmaking/quick")
class MatchmakingController {

	private final MatchmakingService matchmaking;

	MatchmakingController(MatchmakingService matchmaking) {
		this.matchmaking = matchmaking;
	}

	@GetMapping
	MatchmakingService.QuickMatchView status(CurrentPlayer me) {
		return matchmaking.status(me);
	}

	@RateLimited("matchmaking")
	@PostMapping("/join")
	MatchmakingService.QuickMatchView join(CurrentPlayer me) {
		return matchmaking.join(me);
	}

	@RateLimited("matchmaking-heartbeat")
	@PostMapping("/heartbeat")
	MatchmakingService.QuickMatchView heartbeat(CurrentPlayer me) {
		return matchmaking.heartbeat(me);
	}

	@PostMapping("/cancel")
	MatchmakingService.QuickMatchView cancel(CurrentPlayer me) {
		return matchmaking.cancel(me);
	}

	@PostMapping("/acknowledge")
	MatchmakingService.QuickMatchView acknowledge(CurrentPlayer me) {
		return matchmaking.acknowledge(me);
	}

}
