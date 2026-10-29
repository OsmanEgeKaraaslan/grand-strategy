package com.reach.strategy.identity.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.application.AccountService;
import com.reach.strategy.identity.application.PlayerProfile;
import com.reach.strategy.identity.application.PlayerService;

import com.reach.strategy.platform.ratelimit.RateLimited;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated player's own account. Only the owner reads or changes it: the account is the
 * one behind the access token, never an id from the request. Public profiles of anyone (including
 * oneself) are served by the social module. Passwords and email are managed with Supabase Auth
 * directly by the frontend; this API never sees them.
 */
@RateLimited("account")
@RestController
@RequestMapping("/api/v1/me")
class MeController {

	private final PlayerService playerService;
	private final AccountService accountService;

	MeController(PlayerService playerService, AccountService accountService) {
		this.playerService = playerService;
		this.accountService = accountService;
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

	/** Accepts the Terms of Service and Privacy Policy versions the player was shown (Phase 15A). */
	@PostMapping("/terms-acceptance")
	PlayerProfile acceptTerms(CurrentPlayer me, @Valid @RequestBody AcceptTermsRequest request) {
		return accountService.acceptTerms(me.id(), request.termsVersion(), request.privacyVersion());
	}

	/**
	 * Deletes the account. The typed confirmation guards against accidental clicks; the recent sign-in
	 * required by {@link AccountService#deleteAccount} guards against a borrowed session.
	 */
	@PostMapping("/deletion")
	AccountService.DeletedAccount delete(CurrentPlayer me, @AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody DeleteAccountRequest request) {
		return accountService.deleteAccount(me.id(), lastSignInAt(jwt));
	}

	/**
	 * The latest credential sign-in of the token's session, from Supabase's {@code amr} claim
	 * ({@code [{"method":"password","timestamp":<epoch seconds>}, ...]}). Token refreshes keep these
	 * timestamps, so only a fresh sign-in moves it forward.
	 */
	static Instant lastSignInAt(Jwt jwt) {
		if (!(jwt.getClaims().get("amr") instanceof List<?> entries)) {
			return null;
		}
		return entries.stream()
			.filter(Map.class::isInstance)
			.map(entry -> ((Map<?, ?>) entry).get("timestamp"))
			.filter(Number.class::isInstance)
			.map(timestamp -> Instant.ofEpochSecond(((Number) timestamp).longValue()))
			.max(Instant::compareTo)
			.orElse(null);
	}

	/** Every field is optional; at least one must be present. */
	record UpdateProfileRequest(@Size(min = 1, max = 64) String displayName, @Size(min = 1, max = 32) String username,
			@Size(min = 1, max = 16) String emblem, Boolean matchHistoryPublic) {

		@AssertTrue(message = "Change at least one field.")
		boolean isAnyFieldPresent() {
			return displayName != null || username != null || emblem != null || matchHistoryPublic != null;
		}

	}

	record AcceptTermsRequest(@NotBlank @Size(max = 32) String termsVersion,
			@NotBlank @Size(max = 32) String privacyVersion) {
	}

	record DeleteAccountRequest(@NotBlank @Pattern(regexp = "DELETE", message = "Type DELETE to confirm.") String confirmation) {
	}

}
