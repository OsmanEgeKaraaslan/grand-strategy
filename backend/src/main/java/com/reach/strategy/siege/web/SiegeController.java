package com.reach.strategy.siege.web;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.siege.application.SiegeService;
import com.reach.strategy.siege.application.SiegeViews;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fortifications and sieges of a started game. Members only; command bodies carry intents (the
 * level to build), never prices, durations, progress or artillery strength.
 */
@RestController
@Validated
@RequestMapping("/api/v1/games/{gameId}")
class SiegeController {

	private final SiegeService sieges;

	SiegeController(SiegeService sieges) {
		this.sieges = sieges;
	}

	/** All fortresses, active sieges and recently ended sieges. */
	@GetMapping("/sieges")
	SiegeViews.Overview overview(CurrentPlayer me, @PathVariable UUID gameId) {
		return sieges.overview(me, gameId);
	}

	@GetMapping("/sieges/{siegeId}")
	SiegeViews.SiegeDetails siege(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID siegeId) {
		return sieges.siege(me, gameId, siegeId);
	}

	@GetMapping("/provinces/{provinceKey}/fortification")
	SiegeViews.ProvinceFortification province(CurrentPlayer me, @PathVariable UUID gameId,
			@PathVariable @Size(max = 64) @Pattern(regexp = "[a-z0-9-]+") String provinceKey) {
		return sieges.province(me, gameId, provinceKey);
	}

	/** Builds the first fortress level or raises it by one; {@code targetLevel} must be the next level. */
	@PostMapping("/cities/{cityId}/fortress")
	ResponseEntity<SiegeViews.WorksResult> upgrade(CurrentPlayer me, @PathVariable UUID gameId,
			@PathVariable UUID cityId, @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody UpgradeRequest request) {
		SiegeViews.WorksResult result = sieges.upgrade(me, gameId, cityId, request.targetLevel(),
				required(idempotencyKey));
		return ResponseEntity.created(URI.create("/api/v1/games/" + gameId + "/sieges")).body(result);
	}

	@PostMapping("/cities/{cityId}/fortress/repair")
	ResponseEntity<SiegeViews.WorksResult> repair(CurrentPlayer me, @PathVariable UUID gameId,
			@PathVariable UUID cityId, @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
		SiegeViews.WorksResult result = sieges.repair(me, gameId, cityId, required(idempotencyKey));
		return ResponseEntity.created(URI.create("/api/v1/games/" + gameId + "/sieges")).body(result);
	}

	private static UUID required(UUID idempotencyKey) {
		if (idempotencyKey == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
					"The Idempotency-Key header (a UUID) is required for game commands.");
		}
		return idempotencyKey;
	}

	record UpgradeRequest(@NotNull @Min(1) @Max(10) Integer targetLevel) {
	}

}
