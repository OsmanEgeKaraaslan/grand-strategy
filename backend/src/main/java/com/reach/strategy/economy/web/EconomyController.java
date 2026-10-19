package com.reach.strategy.economy.web;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.reach.strategy.economy.application.EconomyService;
import com.reach.strategy.economy.application.EconomyViews;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.platform.web.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Treasury, city economies and workshop construction of a started game. Members only. */
@RestController
@RequestMapping("/api/v1/games/{gameId}")
class EconomyController {

	private final EconomyService economy;

	EconomyController(EconomyService economy) {
		this.economy = economy;
	}

	@GetMapping("/economy")
	EconomyViews.Overview overview(CurrentPlayer me, @PathVariable UUID gameId) {
		return economy.overview(me, gameId);
	}

	@GetMapping("/cities/{cityId}/economy")
	EconomyViews.City city(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID cityId) {
		return economy.city(me, gameId, cityId);
	}

	/**
	 * Orders a workshop. The body names only the tier: price, duration and the treasury effect are
	 * decided by the server. {@code Idempotency-Key} makes retries safe.
	 */
	@PostMapping("/cities/{cityId}/workshops")
	ResponseEntity<EconomyViews.BuildResult> buildWorkshop(CurrentPlayer me, @PathVariable UUID gameId,
			@PathVariable UUID cityId, @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody BuildWorkshopRequest request) {
		if (idempotencyKey == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
					"The Idempotency-Key header (a UUID) is required for game commands.");
		}
		EconomyViews.BuildResult result = economy.buildWorkshop(me, gameId, cityId, request.tier(), idempotencyKey);
		return ResponseEntity
			.created(URI.create("/api/v1/games/" + gameId + "/cities/" + cityId + "/economy"))
			.body(result);
	}

	/** Repairs a damaged workshop. No body: price and duration come from the ruleset. */
	@PostMapping("/cities/{cityId}/workshops/{buildingId}/repair")
	EconomyViews.RepairResult repairWorkshop(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID cityId,
			@PathVariable UUID buildingId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
		if (idempotencyKey == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
					"The Idempotency-Key header (a UUID) is required for game commands.");
		}
		return economy.repairWorkshop(me, gameId, cityId, buildingId, idempotencyKey);
	}

	/** Upgrades an active Tier 1 workshop to Tier 2 (needs civilian technology). No body. */
	@PostMapping("/cities/{cityId}/workshops/{buildingId}/upgrade")
	EconomyViews.UpgradeResult upgradeWorkshop(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID cityId,
			@PathVariable UUID buildingId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
		if (idempotencyKey == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
					"The Idempotency-Key header (a UUID) is required for game commands.");
		}
		return economy.upgradeWorkshop(me, gameId, cityId, buildingId, idempotencyKey);
	}

	record BuildWorkshopRequest(@NotNull @Min(1) @Max(2) Integer tier) {
	}

}
