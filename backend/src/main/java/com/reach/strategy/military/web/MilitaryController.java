package com.reach.strategy.military.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.application.BattalionService;
import com.reach.strategy.military.application.MilitaryService;
import com.reach.strategy.military.application.MilitaryViews;
import com.reach.strategy.military.rules.UnitType;
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

/**
 * Recruitment, movement, regrouping and battle reports of a started game. Members only; the
 * request bodies carry intents (unit type, battalion ids, destination) — never strengths, costs or
 * results, which the server decides.
 */
@RestController
@RequestMapping("/api/v1/games/{gameId}")
class MilitaryController {

	private final MilitaryService military;

	private final BattalionService battalions;

	MilitaryController(MilitaryService military, BattalionService battalions) {
		this.military = military;
		this.battalions = battalions;
	}

	@GetMapping("/military")
	MilitaryViews.Overview overview(CurrentPlayer me, @PathVariable UUID gameId) {
		return military.overview(me, gameId);
	}

	@GetMapping("/provinces/{provinceKey}/military")
	MilitaryViews.ProvinceMilitary province(CurrentPlayer me, @PathVariable UUID gameId,
			@PathVariable String provinceKey) {
		return military.province(me, gameId, provinceKey);
	}

	@GetMapping("/battles")
	List<MilitaryViews.BattleSummary> battles(CurrentPlayer me, @PathVariable UUID gameId) {
		return military.battles(me, gameId);
	}

	@GetMapping("/battles/{battleId}")
	MilitaryViews.BattleView battle(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID battleId) {
		return military.battle(me, gameId, battleId);
	}

	@PostMapping("/cities/{cityId}/recruitments")
	ResponseEntity<MilitaryViews.RecruitResult> recruit(CurrentPlayer me, @PathVariable UUID gameId,
			@PathVariable UUID cityId, @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody RecruitRequest request) {
		MilitaryViews.RecruitResult result = military.recruit(me, gameId, cityId, request.unitType(),
				required(idempotencyKey));
		return ResponseEntity.created(URI.create("/api/v1/games/" + gameId + "/military")).body(result);
	}

	@PostMapping("/moves")
	MilitaryViews.MoveResult move(CurrentPlayer me, @PathVariable UUID gameId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody MoveRequest request) {
		return military.move(me, gameId, request.battalionIds(), request.toProvinceKey(), required(idempotencyKey));
	}

	@PostMapping("/armies")
	ResponseEntity<MilitaryViews.ArmyResult> regroup(CurrentPlayer me, @PathVariable UUID gameId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody RegroupRequest request) {
		MilitaryViews.ArmyResult result = military.regroup(me, gameId, request.name(), request.battalionIds(),
				required(idempotencyKey));
		return ResponseEntity.created(URI.create("/api/v1/games/" + gameId + "/military")).body(result);
	}

	/** One own battalion: soldiers by origin city, reinforcement quote and disband preview. */
	@GetMapping("/battalions/{battalionId}")
	MilitaryViews.BattalionDetails battalion(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID battalionId) {
		return battalions.details(me, gameId, battalionId);
	}

	/** The body names only how many soldiers; cost and civilians are decided by the server. */
	@PostMapping("/battalions/{battalionId}/reinforcements")
	ResponseEntity<MilitaryViews.ReinforceResult> reinforce(CurrentPlayer me, @PathVariable UUID gameId,
			@PathVariable UUID battalionId, @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody ReinforceRequest request) {
		MilitaryViews.ReinforceResult result = battalions.reinforce(me, gameId, battalionId, request.soldiers(),
				required(idempotencyKey));
		return ResponseEntity.created(URI.create("/api/v1/games/" + gameId + "/battalions/" + battalionId)).body(result);
	}

	@PostMapping("/battalions/{battalionId}/disband")
	MilitaryViews.DisbandResult disband(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID battalionId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
		return battalions.disband(me, gameId, battalionId, required(idempotencyKey));
	}

	private static UUID required(UUID idempotencyKey) {
		if (idempotencyKey == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
					"The Idempotency-Key header (a UUID) is required for game commands.");
		}
		return idempotencyKey;
	}

	record RecruitRequest(@NotNull UnitType unitType) {
	}

	record ReinforceRequest(@NotNull @Min(1) @Max(1000) Integer soldiers) {
	}

	record MoveRequest(@NotEmpty @Size(max = 100) List<@NotNull UUID> battalionIds,
			@NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9-]+") String toProvinceKey) {
	}

	record RegroupRequest(@NotBlank @Size(max = 40) String name,
			@NotEmpty @Size(max = 100) List<@NotNull UUID> battalionIds) {
	}

}
