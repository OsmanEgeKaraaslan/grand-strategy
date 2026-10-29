package com.reach.strategy.social.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.social.application.FriendService;

import com.reach.strategy.platform.ratelimit.RateLimited;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Player search, friend requests, friendships and blocks of the authenticated player. */
@RateLimited("social-actions")
@RestController
@Validated
@RequestMapping("/api/v1")
class FriendController {

	private final FriendService friends;

	FriendController(FriendService friends) {
		this.friends = friends;
	}

	@RateLimited("player-search")
	@GetMapping("/players/search")
	List<FriendService.PlayerCard> search(CurrentPlayer me, @RequestParam("q") @Size(max = 32) String query) {
		return friends.search(me, query);
	}

	@GetMapping("/friends")
	FriendService.FriendsView overview(CurrentPlayer me) {
		return friends.overview(me);
	}

	@RateLimited("friend-requests")
	@PostMapping("/friends/requests")
	FriendService.RequestView request(CurrentPlayer me, @Valid @RequestBody UsernameRequest request) {
		return friends.request(me, request.username());
	}

	@PostMapping("/friends/requests/{requestId}/accept")
	FriendService.RequestView accept(CurrentPlayer me, @PathVariable UUID requestId) {
		return friends.accept(me, requestId);
	}

	@PostMapping("/friends/requests/{requestId}/decline")
	FriendService.RequestView decline(CurrentPlayer me, @PathVariable UUID requestId) {
		return friends.decline(me, requestId);
	}

	@PostMapping("/friends/requests/{requestId}/cancel")
	FriendService.RequestView cancel(CurrentPlayer me, @PathVariable UUID requestId) {
		return friends.cancel(me, requestId);
	}

	@DeleteMapping("/friends/{username}")
	ResponseEntity<Void> unfriend(CurrentPlayer me, @PathVariable @Size(max = 32) String username) {
		friends.unfriend(me, username);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/blocks")
	FriendService.PlayerCard block(CurrentPlayer me, @Valid @RequestBody UsernameRequest request) {
		return friends.block(me, request.username());
	}

	@DeleteMapping("/blocks/{username}")
	ResponseEntity<Void> unblock(CurrentPlayer me, @PathVariable @Size(max = 32) String username) {
		friends.unblock(me, username);
		return ResponseEntity.noContent().build();
	}

	record UsernameRequest(@NotBlank @Size(max = 32) String username) {
	}

}
