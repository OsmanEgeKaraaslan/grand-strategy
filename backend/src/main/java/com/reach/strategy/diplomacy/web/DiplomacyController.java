package com.reach.strategy.diplomacy.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.reach.strategy.diplomacy.application.DiplomacyQueries;
import com.reach.strategy.diplomacy.application.DiplomacyService;
import com.reach.strategy.diplomacy.application.DiplomacyViews;
import com.reach.strategy.diplomacy.application.PeaceService;
import com.reach.strategy.diplomacy.rules.Diplomacy;
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

/**
 * Diplomacy of a started game. Command bodies carry intents only (whom to declare war on, which
 * terms to offer); war score, treasuries, ownership, populations, settlement and tribute state are
 * always computed by the server. Every command needs an {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/api/v1/games/{gameId}/diplomacy")
class DiplomacyController {

	private final DiplomacyQueries queries;
	private final DiplomacyService diplomacy;
	private final PeaceService peace;

	DiplomacyController(DiplomacyQueries queries, DiplomacyService diplomacy, PeaceService peace) {
		this.queries = queries;
		this.diplomacy = diplomacy;
		this.peace = peace;
	}

	// --- reads ----------------------------------------------------------------------------------

	@GetMapping
	DiplomacyViews.Overview overview(CurrentPlayer me, @PathVariable UUID gameId) {
		return queries.overview(me, gameId);
	}

	@GetMapping("/wars/{warId}")
	DiplomacyViews.WarDetail war(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID warId) {
		return queries.war(me, gameId, warId);
	}

	@GetMapping("/peace-proposals")
	List<DiplomacyViews.ProposalView> proposals(CurrentPlayer me, @PathVariable UUID gameId) {
		return queries.proposals(me, gameId);
	}

	@GetMapping("/peace-proposals/{proposalId}")
	DiplomacyViews.ProposalView proposal(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID proposalId) {
		return queries.proposal(me, gameId, proposalId);
	}

	@GetMapping("/treaties")
	List<DiplomacyViews.TreatyView> treaties(CurrentPlayer me, @PathVariable UUID gameId) {
		return queries.treaties(me, gameId);
	}

	@GetMapping("/tributes")
	List<DiplomacyViews.TributeView> tributes(CurrentPlayer me, @PathVariable UUID gameId) {
		return queries.tributes(me, gameId);
	}

	@GetMapping("/events")
	List<DiplomacyViews.EventView> events(CurrentPlayer me, @PathVariable UUID gameId) {
		return queries.events(me, gameId);
	}

	/** Validation only (no command, nothing changes): would these terms be accepted now? */
	@PostMapping("/peace-proposals/check")
	DiplomacyViews.TermsCheck check(CurrentPlayer me, @PathVariable UUID gameId,
			@Valid @RequestBody ProposalRequest request) {
		return queries.check(me, gameId, request.warId(), request.terms().stream().map(TermRequest::toTerm).toList());
	}

	// --- war and alliances ----------------------------------------------------------------------

	@PostMapping("/wars")
	ResponseEntity<DiplomacyViews.WarResult> declareWar(CurrentPlayer me, @PathVariable UUID gameId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody DeclareWarRequest request) {
		DiplomacyViews.WarResult result = diplomacy.declareWar(me, gameId, request.targetNationId(),
				required(idempotencyKey));
		return ResponseEntity.created(URI.create("/api/v1/games/" + gameId + "/diplomacy/wars/" + result.war().id()))
			.body(result);
	}

	@PostMapping("/alliances")
	ResponseEntity<DiplomacyViews.AllianceResult> proposeAlliance(CurrentPlayer me, @PathVariable UUID gameId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody AllianceRequest request) {
		DiplomacyViews.AllianceResult result = diplomacy.proposeAlliance(me, gameId, request.nationId(),
				Diplomacy.AllianceKind.valueOf(request.kind()), required(idempotencyKey));
		return ResponseEntity.status(HttpStatus.CREATED).body(result);
	}

	@PostMapping("/alliances/{allianceId}/accept")
	DiplomacyViews.AllianceResult acceptAlliance(CurrentPlayer me, @PathVariable UUID gameId,
			@PathVariable UUID allianceId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
		return diplomacy.acceptAlliance(me, gameId, allianceId, required(idempotencyKey));
	}

	@PostMapping("/alliances/{allianceId}/reject")
	DiplomacyViews.AllianceResult rejectAlliance(CurrentPlayer me, @PathVariable UUID gameId,
			@PathVariable UUID allianceId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
		return diplomacy.rejectAlliance(me, gameId, allianceId, required(idempotencyKey));
	}

	/** Ends an active alliance, or withdraws the caller's own pending proposal. */
	@PostMapping("/alliances/{allianceId}/terminate")
	DiplomacyViews.AllianceResult terminateAlliance(CurrentPlayer me, @PathVariable UUID gameId,
			@PathVariable UUID allianceId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
		return diplomacy.endAlliance(me, gameId, allianceId, required(idempotencyKey));
	}

	// --- peace ----------------------------------------------------------------------------------

	@PostMapping("/peace-proposals")
	ResponseEntity<DiplomacyViews.ProposalResult> propose(CurrentPlayer me, @PathVariable UUID gameId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody ProposalRequest request) {
		DiplomacyViews.ProposalResult result = peace.propose(me, gameId, request.warId(),
				request.terms().stream().map(TermRequest::toTerm).toList(), required(idempotencyKey));
		return ResponseEntity
			.created(URI.create("/api/v1/games/" + gameId + "/diplomacy/peace-proposals/" + result.proposal().id()))
			.body(result);
	}

	@PostMapping("/peace-proposals/{proposalId}/accept")
	DiplomacyViews.PeaceResult accept(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID proposalId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
		return peace.accept(me, gameId, proposalId, required(idempotencyKey));
	}

	@PostMapping("/peace-proposals/{proposalId}/reject")
	DiplomacyViews.ProposalResult reject(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID proposalId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
		return peace.reject(me, gameId, proposalId, required(idempotencyKey));
	}

	@PostMapping("/peace-proposals/{proposalId}/cancel")
	DiplomacyViews.ProposalResult cancel(CurrentPlayer me, @PathVariable UUID gameId, @PathVariable UUID proposalId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
		return peace.cancel(me, gameId, proposalId, required(idempotencyKey));
	}

	@PostMapping("/peace-proposals/{proposalId}/counter")
	ResponseEntity<DiplomacyViews.ProposalResult> counter(CurrentPlayer me, @PathVariable UUID gameId,
			@PathVariable UUID proposalId,
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
			@Valid @RequestBody CounterRequest request) {
		DiplomacyViews.ProposalResult result = peace.counter(me, gameId, proposalId,
				request.terms().stream().map(TermRequest::toTerm).toList(), required(idempotencyKey));
		return ResponseEntity.status(HttpStatus.CREATED).body(result);
	}

	private static UUID required(UUID idempotencyKey) {
		if (idempotencyKey == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
					"The Idempotency-Key header (a UUID) is required for game commands.");
		}
		return idempotencyKey;
	}

	record DeclareWarRequest(@NotNull UUID targetNationId) {
	}

	record AllianceRequest(@NotNull UUID nationId, @NotNull @Pattern(regexp = "NORMAL|DEFENSIVE") String kind) {
	}

	record ProposalRequest(@NotNull UUID warId, @NotNull @Size(max = 50) List<@Valid @NotNull TermRequest> terms) {
	}

	record CounterRequest(@NotNull @Size(max = 50) List<@Valid @NotNull TermRequest> terms) {
	}

	/**
	 * One term as a client describes it. Tribute rate and duration come from the ruleset and cannot be
	 * chosen; gold is integer milligold.
	 */
	record TermRequest(@NotNull @Pattern(regexp = "PROVINCE|GOLD|TRIBUTE|POPULATION") String type,
			@NotNull UUID fromNationId, @NotNull UUID toNationId, @Size(max = 64) String provinceKey, Long amountMg,
			UUID sourceCityId, UUID destinationCityId, Integer people) {

		DiplomacyViews.Term toTerm() {
			return new DiplomacyViews.Term(type, fromNationId, toNationId, provinceKey, amountMg, sourceCityId,
					destinationCityId, people, null, null);
		}

	}

}
