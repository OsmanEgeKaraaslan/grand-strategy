package com.reach.strategy.social.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.social.application.InvitationService;

import com.reach.strategy.platform.ratelimit.RateLimited;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lobby invitations of the authenticated player: the inviter is always the caller. */
@RateLimited("social-actions")
@RestController
@RequestMapping("/api/v1")
class InvitationController {

	private final InvitationService invitations;

	InvitationController(InvitationService invitations) {
		this.invitations = invitations;
	}

	@GetMapping("/invitations")
	InvitationService.InvitationsView incoming(CurrentPlayer me) {
		return invitations.incoming(me);
	}

	/** Host only: the invitations sent for one lobby. */
	@GetMapping("/games/{gameId}/invitations")
	List<InvitationService.InvitationView> sent(CurrentPlayer me, @PathVariable UUID gameId) {
		return invitations.sent(me, gameId);
	}

	@RateLimited("invitations")
	@PostMapping("/invitations")
	InvitationService.InvitationView invite(CurrentPlayer me, @Valid @RequestBody InviteRequest request) {
		return invitations.invite(me, request.gameId(), request.username());
	}

	@PostMapping("/invitations/{invitationId}/accept")
	InvitationService.InvitationView accept(CurrentPlayer me, @PathVariable UUID invitationId) {
		return invitations.accept(me, invitationId);
	}

	@PostMapping("/invitations/{invitationId}/decline")
	InvitationService.InvitationView decline(CurrentPlayer me, @PathVariable UUID invitationId) {
		return invitations.decline(me, invitationId);
	}

	@PostMapping("/invitations/{invitationId}/cancel")
	InvitationService.InvitationView cancel(CurrentPlayer me, @PathVariable UUID invitationId) {
		return invitations.cancel(me, invitationId);
	}

	record InviteRequest(@NotNull UUID gameId, @NotBlank @Size(max = 32) String username) {
	}

}
