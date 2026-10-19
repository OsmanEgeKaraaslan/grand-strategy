package com.reach.strategy.research.web;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.research.application.ResearchService;
import com.reach.strategy.research.application.ResearchViews;
import com.reach.strategy.research.rules.InstitutionType;
import com.reach.strategy.research.rules.ResearchRules;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Research of a started game. The overview is private to the caller's nation; command bodies carry
 * intents (what to build or unlock), never points, costs, levels or modifiers.
 */
@RestController
@RequestMapping("/api/v1/games/{gameId}")
class ResearchController {

	private final ResearchService research;

	ResearchController(ResearchService research) {
		this.research = research;
	}

	@GetMapping("/research")
	ResearchViews.Overview overview(CurrentPlayer me, @PathVariable UUID gameId) {
		return research.overview(me, gameId);
	}

	@GetMapping("/cities/{cityId}/institutions")
	ResearchViews.CityInstitutions city(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID cityId) {
		return research.city(me, gameId, cityId);
	}

	@PostMapping("/cities/{cityId}/institutions")
	ResponseEntity<ResearchViews.InstitutionResult> build(CurrentPlayer me, @PathVariable UUID gameId,
			@PathVariable UUID cityId, @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody BuildRequest request) {
		ResearchViews.InstitutionResult result = research.buildInstitution(me, gameId, cityId, request.type(),
				required(idempotencyKey));
		return ResponseEntity.created(URI.create("/api/v1/games/" + gameId + "/cities/" + cityId + "/institutions"))
			.body(result);
	}

	@PostMapping("/research/technologies")
	ResearchViews.UnlockResult unlockTechnology(CurrentPlayer me, @PathVariable UUID gameId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody TechnologyRequest request) {
		return research.unlockTechnology(me, gameId, request.track(), request.targetLevel(), required(idempotencyKey));
	}

	@PostMapping("/research/doctrines")
	ResearchViews.UnlockResult unlockDoctrine(CurrentPlayer me, @PathVariable UUID gameId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody DoctrineRequest request) {
		return research.unlockDoctrine(me, gameId, request.doctrine(), required(idempotencyKey));
	}

	private static UUID required(UUID idempotencyKey) {
		if (idempotencyKey == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
					"The Idempotency-Key header (a UUID) is required for game commands.");
		}
		return idempotencyKey;
	}

	record BuildRequest(@NotNull InstitutionType type) {
	}

	record TechnologyRequest(@NotNull ResearchRules.Track track, @NotNull @Min(2) @Max(20) Integer targetLevel) {
	}

	record DoctrineRequest(@NotBlank @Size(max = 32) @Pattern(regexp = "[A-Z_]+") String doctrine) {
	}

}
