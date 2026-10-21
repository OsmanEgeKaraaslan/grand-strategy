package com.reach.strategy.diplomacy.application;

import com.reach.strategy.diplomacy.api.DiplomacyViews;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.diplomacy.api.ConferenceActivity;
import com.reach.strategy.diplomacy.domain.CallToArmsRepository;
import com.reach.strategy.diplomacy.domain.ConferenceRepository;
import com.reach.strategy.diplomacy.domain.RelationsRepository;
import com.reach.strategy.diplomacy.domain.TreatyRepository;
import com.reach.strategy.diplomacy.rules.PeaceTerms;
import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.game.api.GameControl;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.ForceWithdrawal;
import com.reach.strategy.world.api.WorldApi;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Peace conferences (ruleset v6, docs/DIPLOMACY.md §13–14). A war leader (general peace) or a
 * non-leader participant (separate peace) requests a conference; the opposing war leader accepts or
 * rejects it at any time. While a conference is active the whole game is paused: no round passes and
 * no normal command is possible, but the turn state is kept. Inside, the two principals revise an
 * immutable, numbered draft; every revision lists the nations whose approval it needs (both
 * principals and every nation that would give something up) and an approval names its revision, so
 * it can never authorize changed terms. When the last required approval arrives the treaty is
 * re-validated and settled in the same transaction. A principal may cancel at any time; there are no
 * timeouts, cooldowns or automatic acceptances.
 * <p>
 * All conference commands go through the command pipeline without the turn-order check
 * ({@link GameCommands#executeOutOfTurn}); membership, idempotency and the game lock still apply.
 * Nations without a player follow the documented placeholder ({@link PeaceTerms#npcApproves}).
 */
@Service
public class ConferenceService {

	private final GameCommands gameCommands;
	private final GameControl gameControl;
	private final DiplomacyState state;
	private final RelationsRepository relations;
	private final TreatyRepository treaties;
	private final ConferenceRepository conferences;
	private final CallToArmsRepository calls;
	private final DiplomacyService diplomacy;
	private final PeaceService peace;
	private final EliminationService elimination;
	private final DiplomacyQueries queries;
	private final WorldApi world;
	private final ForceWithdrawal withdrawal;
	private final JsonMapper jsonMapper;
	private final ApplicationEventPublisher events;

	ConferenceService(GameCommands gameCommands, GameControl gameControl, DiplomacyState state,
			RelationsRepository relations, TreatyRepository treaties, ConferenceRepository conferences,
			CallToArmsRepository calls, DiplomacyService diplomacy, PeaceService peace, EliminationService elimination,
			DiplomacyQueries queries, WorldApi world, ForceWithdrawal withdrawal,
			JsonMapper jsonMapper, ApplicationEventPublisher events) {
		this.events = events;
		this.gameCommands = gameCommands;
		this.gameControl = gameControl;
		this.state = state;
		this.relations = relations;
		this.treaties = treaties;
		this.conferences = conferences;
		this.calls = calls;
		this.diplomacy = diplomacy;
		this.peace = peace;
		this.elimination = elimination;
		this.queries = queries;
		this.world = world;
		this.withdrawal = withdrawal;
		this.jsonMapper = jsonMapper;
	}

	// --- lifecycle ------------------------------------------------------------------------------

	/** GENERAL: a war leader asks the other leader. SEPARATE: a non-leader participant asks the opposing leader. */
	public DiplomacyViews.ConferenceResult request(CurrentPlayer me, UUID gameId, UUID warId, String kind,
			UUID idempotencyKey) {
		return announce(gameId, gameCommands.executeOutOfTurn(me, gameId, idempotencyKey, "REQUEST_CONFERENCE", new Handler(gameId) {

			@Override
			public GameCommands.Outcome<DiplomacyViews.ConferenceResult> execute(GameCommands.CommandContext context) {
				DiplomacyState.Picture picture = available(gameId);
				WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
				UUID requester = DiplomacyQueries.nationOf(snapshot, context.gamePlayerId());
				RelationsRepository.WarRow war = activeWar(picture, warId);
				String side = picture.sideOf(warId, requester)
					.orElseThrow(() -> DiplomacyErrors.forbidden("NOT_A_PARTICIPANT", "Your nation does not fight in this war."));
				boolean leader = war.attackerLeader().equals(requester) || war.defenderLeader().equals(requester);
				UUID counterpart = "ATTACKER".equals(side) ? war.defenderLeader() : war.attackerLeader();
				if ("GENERAL".equals(kind) && !leader) {
					throw DiplomacyErrors.forbidden("NOT_WAR_LEADER",
							"Only a war leader can ask for the general peace; ask for a separate peace instead.");
				}
				if ("SEPARATE".equals(kind)) {
					if (!picture.rules().features().separatePeace()) {
						throw DiplomacyErrors.conflict("SEPARATE_PEACE_NOT_AVAILABLE", "This ruleset has no separate peace.");
					}
					if (leader) {
						throw DiplomacyErrors.conflict("LEADER_CANNOT_LEAVE",
								"A war leader cannot leave its coalition by separate peace; negotiate the general peace.");
					}
				}
				List<ConferenceRepository.ConferenceRow> all = conferences.all(gameId);
				if (all.stream().anyMatch(ConferenceRepository.ConferenceRow::active)) {
					throw DiplomacyErrors.conflict("CONFERENCE_IN_PROGRESS",
							"Another peace conference is in progress; wait until it ends.");
				}
				if (all.stream().anyMatch(c -> c.open() && c.warId().equals(warId) && c.requester().equals(requester)
						&& c.kind().equals(kind))) {
					throw DiplomacyErrors.conflict("CONFERENCE_ALREADY_REQUESTED", "You have already asked for this conference.");
				}
				UUID id = UUID.randomUUID();
				conferences.insert(gameId, new ConferenceRepository.ConferenceRow(id, warId, kind, requester, counterpart,
						"REQUESTED", 0, context.round(), null, null, context.now()));
				diplomacy.event(gameId, context, "CONFERENCE_REQUESTED", requester, counterpart, warId,
						Map.of("conferenceId", id, "kind", kind));
				if (npc(snapshot, counterpart)) {
					// Placeholder until AI exists: a nation without a player always agrees to talk.
					open(gameId, context, conferences.find(gameId, id).orElseThrow());
				}
				return new GameCommands.Outcome<>(result(gameId, requester, id, null, List.of()), id);
			}

		}));
	}

	/** The opposing war leader agrees to talk (normal play pauses) or refuses. */
	public DiplomacyViews.ConferenceResult answer(CurrentPlayer me, UUID gameId, UUID conferenceId, boolean accept,
			UUID idempotencyKey) {
		return announce(gameId, gameCommands.executeOutOfTurn(me, gameId, idempotencyKey, "ANSWER_CONFERENCE", new Handler(gameId) {

			@Override
			public GameCommands.Outcome<DiplomacyViews.ConferenceResult> execute(GameCommands.CommandContext context) {
				available(gameId);
				UUID nation = DiplomacyQueries.nationOf(world.snapshot(gameId), context.gamePlayerId());
				ConferenceRepository.ConferenceRow conference = conference(gameId, conferenceId);
				if (!conference.counterpart().equals(nation)) {
					throw DiplomacyErrors.forbidden("NOT_CONFERENCE_COUNTERPART", "Only the invited war leader can answer.");
				}
				if (!"REQUESTED".equals(conference.status())) {
					throw notOpen();
				}
				if (accept) {
					if (conferences.all(gameId).stream().anyMatch(ConferenceRepository.ConferenceRow::active)) {
						throw DiplomacyErrors.conflict("CONFERENCE_IN_PROGRESS",
								"Another peace conference is in progress; wait until it ends.");
					}
					open(gameId, context, conference);
				}
				else {
					conferences.close(gameId, conferenceId, "REQUESTED", "REJECTED", context.now());
					diplomacy.event(gameId, context, "CONFERENCE_REJECTED", nation, conference.requester(),
							conference.warId(), Map.of("conferenceId", conferenceId));
				}
				return new GameCommands.Outcome<>(result(gameId, nation, conferenceId, null, List.of()), conferenceId);
			}

		}));
	}

	/** Either principal ends the conference without a treaty; play resumes where it stopped. */
	public DiplomacyViews.ConferenceResult cancel(CurrentPlayer me, UUID gameId, UUID conferenceId, UUID idempotencyKey) {
		return announce(gameId, gameCommands.executeOutOfTurn(me, gameId, idempotencyKey, "CANCEL_CONFERENCE", new Handler(gameId) {

			@Override
			public GameCommands.Outcome<DiplomacyViews.ConferenceResult> execute(GameCommands.CommandContext context) {
				available(gameId);
				UUID nation = DiplomacyQueries.nationOf(world.snapshot(gameId), context.gamePlayerId());
				ConferenceRepository.ConferenceRow conference = conference(gameId, conferenceId);
				if (!conference.principal(nation)) {
					throw DiplomacyErrors.forbidden("NOT_CONFERENCE_PRINCIPAL",
							"Only the two negotiating nations can end this conference.");
				}
				if (!conference.open() || !conferences.close(gameId, conferenceId, conference.status(), "CANCELLED",
						context.now())) {
					throw notOpen();
				}
				if (conference.active()) {
					gameControl.resumeAfterConference(gameId, conferenceId, context.now());
				}
				diplomacy.event(gameId, context, "CONFERENCE_CANCELLED", nation, null, conference.warId(),
						Map.of("conferenceId", conferenceId));
				return new GameCommands.Outcome<>(result(gameId, nation, conferenceId, null, List.of()), conferenceId);
			}

		}));
	}

	private void open(UUID gameId, GameCommands.CommandContext context, ConferenceRepository.ConferenceRow conference) {
		if (!conferences.open(gameId, conference.id(), context.now())) {
			throw notOpen();
		}
		gameControl.pauseForConference(gameId, conference.id(), context.now());
		diplomacy.event(gameId, context, "CONFERENCE_OPENED", conference.counterpart(), conference.requester(),
				conference.warId(), Map.of("conferenceId", conference.id(), "kind", conference.kind()));
		// Revision 1 is the white peace; the principals revise it from there.
		addRevision(gameId, context, conference(gameId, conference.id()), conference.requester(), List.of(), 0);
	}

	// --- drafts and approvals -------------------------------------------------------------------

	/**
	 * A principal publishes a new revision of the draft (the complete list of terms). It must build on
	 * the revision the caller saw ({@code expectedRevision}); every approval of earlier revisions stays
	 * with those revisions.
	 */
	public DiplomacyViews.ConferenceResult submit(CurrentPlayer me, UUID gameId, UUID conferenceId,
			int expectedRevision, List<DiplomacyViews.Term> terms, UUID idempotencyKey) {
		List<PeaceTerms.Term> parsed = DiplomacyQueries.toRules(terms);
		return announce(gameId, gameCommands.executeOutOfTurn(me, gameId, idempotencyKey, "SUBMIT_DRAFT", new Handler(gameId) {

			@Override
			public GameCommands.Outcome<DiplomacyViews.ConferenceResult> execute(GameCommands.CommandContext context) {
				available(gameId);
				UUID nation = DiplomacyQueries.nationOf(world.snapshot(gameId), context.gamePlayerId());
				ConferenceRepository.ConferenceRow conference = conference(gameId, conferenceId);
				if (!conference.principal(nation)) {
					throw DiplomacyErrors.forbidden("NOT_CONFERENCE_PRINCIPAL",
							"Only the two negotiating nations can change the draft; others approve or reject it.");
				}
				if (!conference.active()) {
					throw notOpen();
				}
				addRevision(gameId, context, conference, nation, parsed, expectedRevision);
				return new GameCommands.Outcome<>(result(gameId, nation, conferenceId, null, List.of()), conferenceId);
			}

		}));
	}

	/**
	 * A required nation approves or rejects one revision. The last approval of the current revision
	 * settles the treaty in the same transaction (after a fresh validation; if it fails nothing is
	 * recorded and the draft must be revised).
	 */
	public DiplomacyViews.ConferenceResult decide(CurrentPlayer me, UUID gameId, UUID conferenceId, int revision,
			boolean approve, UUID idempotencyKey) {
		return announce(gameId, gameCommands.executeOutOfTurn(me, gameId, idempotencyKey, approve ? "APPROVE_DRAFT" : "REJECT_DRAFT",
				new Handler(gameId) {

					@Override
					public GameCommands.Outcome<DiplomacyViews.ConferenceResult> execute(GameCommands.CommandContext context) {
						available(gameId);
						UUID nation = DiplomacyQueries.nationOf(world.snapshot(gameId), context.gamePlayerId());
						ConferenceRepository.ConferenceRow conference = conference(gameId, conferenceId);
						if (!conference.active()) {
							throw notOpen();
						}
						if (revision != conference.currentRevision()) {
							throw DiplomacyErrors.conflict("STALE_REVISION",
									"The draft has changed; review revision " + conference.currentRevision() + " instead.");
						}
						ConferenceRepository.RevisionRow current = currentRevision(conference);
						List<UUID> required = required(current);
						if (!required.contains(nation)) {
							throw DiplomacyErrors.forbidden("APPROVAL_NOT_REQUIRED",
									"Your nation's approval is not needed for this draft.");
						}
						if (!conferences.decide(gameId, conferenceId, new ConferenceRepository.DecisionRow(revision, nation,
								approve ? "APPROVED" : "REJECTED", context.now()))) {
							throw DiplomacyErrors.conflict("ALREADY_DECIDED", "Your nation has already decided on this revision.");
						}
						DiplomacyViews.TreatyView treaty = null;
						List<DiplomacyViews.Withdrawal> withdrawals = List.of();
						if (approve && allApproved(conference, revision, required)) {
							Settled settled = settle(gameId, context, conference, current);
							treaty = settled.treaty();
							withdrawals = settled.withdrawals();
						}
						return new GameCommands.Outcome<>(result(gameId, nation, conferenceId, treaty, withdrawals),
								conferenceId);
					}

				}));
	}

	private void addRevision(UUID gameId, GameCommands.CommandContext context,
			ConferenceRepository.ConferenceRow conference, UUID author, List<PeaceTerms.Term> terms, int expected) {
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow();
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		Map<UUID, String> sides = negotiatingSides(picture, conference);
		List<PeaceTerms.Violation> violations = PeaceTerms.validate(terms, facts(gameId, snapshot, sides, picture),
				picture.rules());
		if (!violations.isEmpty()) {
			throw DiplomacyErrors.termsInvalid(violations, false);
		}
		List<UUID> required = PeaceTerms.requiredApprovers(Set.of(conference.requester(), conference.counterpart()), terms);
		int next = conference.currentRevision() + 1;
		boolean added = conferences.addRevision(gameId, conference.id(), expected,
				new ConferenceRepository.RevisionRow(next, author,
						queries.writeTerms(DiplomacyQueries.canonical(terms)), jsonMapper.writeValueAsString(required),
						context.now()));
		if (!added) {
			throw DiplomacyErrors.conflict("STALE_REVISION",
					"The draft has changed meanwhile; review the current revision before changing it.");
		}
		// Nations without a player decide at once by the documented placeholder rule.
		Map<String, UUID> controllers = snapshot.provinces()
			.stream()
			.collect(Collectors.toMap(WorldApi.ProvinceState::provinceKey, WorldApi.ProvinceState::controllerNationId));
		for (UUID nation : required) {
			if (npc(snapshot, nation)) {
				boolean approves = PeaceTerms.npcApproves(nation, terms, controllers, sides);
				conferences.decide(gameId, conference.id(),
						new ConferenceRepository.DecisionRow(next, nation, approves ? "APPROVED" : "REJECTED", context.now()));
			}
		}
	}

	private boolean allApproved(ConferenceRepository.ConferenceRow conference, int revision, List<UUID> required) {
		Set<UUID> approved = conferences.decisions(conference.id(), revision)
			.stream()
			.filter(d -> "APPROVED".equals(d.decision()))
			.map(ConferenceRepository.DecisionRow::nationId)
			.collect(Collectors.toSet());
		return approved.containsAll(required);
	}

	// --- settlement -----------------------------------------------------------------------------

	private record Settled(DiplomacyViews.TreatyView treaty, List<DiplomacyViews.Withdrawal> withdrawals) {
	}

	/**
	 * Applies the approved revision atomically: fresh validation; reparations, cessions, population
	 * transfers, tribute; then the war ends for everyone (general) or the exiting nation leaves it
	 * (separate); truces; landless nations are eliminated; occupation ends where war ended; invalid
	 * attachments end; battalions without access withdraw and sieges are re-judged; the game ends if
	 * one nation is left, otherwise play resumes.
	 */
	private Settled settle(UUID gameId, GameCommands.CommandContext context, ConferenceRepository.ConferenceRow conference,
			ConferenceRepository.RevisionRow revision) {
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow();
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		RelationsRepository.WarRow war = activeWar(picture, conference.warId());
		Map<UUID, String> sides = negotiatingSides(picture, conference);
		List<DiplomacyViews.Term> stored = queries.readTerms(revision.termsJson());
		List<PeaceTerms.Term> terms = DiplomacyQueries.toRules(stored);
		List<PeaceTerms.Violation> violations = PeaceTerms.validate(terms, facts(gameId, snapshot, sides, picture),
				picture.rules());
		if (!violations.isEmpty()) {
			throw DiplomacyErrors.termsInvalid(violations, true);
		}
		if (!conferences.close(gameId, conference.id(), "ACTIVE", "CONCLUDED", context.now())) {
			throw notOpen();
		}
		UUID treatyId = UUID.randomUUID();
		int truceUntil = context.round() + picture.rules().truceRounds();
		UUID exiting = conference.separate() ? conference.requester() : null;
		treaties.insertConferenceTreaty(gameId, new TreatyRepository.TreatyRow(treatyId, war.id(), null, context.round(),
				context.turnNumber(), revision.termsJson(), truceUntil, context.now()), conference.id(), revision.revision(),
				exiting);
		for (PeaceTerms.Term term : PeaceTerms.settlementOrder(terms)) {
			peace.apply(gameId, context, war, treatyId, term, snapshot, picture);
		}
		List<RelationsRepository.ParticipantRow> participants = picture.participantsOf(war.id());
		if (exiting == null) {
			if (!relations.endWar(gameId, war.id(), context.round(), context.turnNumber(), "PEACE_TREATY")) {
				throw DiplomacyErrors.conflict("WAR_NOT_ACTIVE", "This war has already ended.");
			}
			truces(gameId, treatyId, truceUntil, participants, null);
			calls.all(gameId)
				.stream()
				.filter(c -> c.pending() && c.warId().equals(war.id()))
				.forEach(c -> calls.close(gameId, c.id(), "CANCELLED", context.round(), context.now()));
			conferences.all(gameId)
				.stream()
				.filter(c -> "REQUESTED".equals(c.status()) && c.warId().equals(war.id()))
				.forEach(c -> conferences.close(gameId, c.id(), "REQUESTED", "CANCELLED", context.now()));
			diplomacy.event(gameId, context, "PEACE_SIGNED", conference.requester(), conference.counterpart(), war.id(),
					Map.of("treatyId", treatyId, "terms", stored.size(), "truceUntilRound", truceUntil, "conferenceId",
							conference.id()));
		}
		else {
			relations.leaveWar(gameId, war.id(), exiting, context.round(), "SEPARATE_PEACE");
			truces(gameId, treatyId, truceUntil, participants, exiting);
			diplomacy.event(gameId, context, "SEPARATE_PEACE", exiting, conference.counterpart(), war.id(),
					Map.of("treatyId", treatyId, "terms", stored.size(), "truceUntilRound", truceUntil, "conferenceId",
							conference.id()));
		}

		elimination.eliminateLandless(gameId, context);
		peace.returnControl(gameId, context, war.id());
		int detached = exiting == null ? 0 : withdrawal.endAttachmentsOf(gameId, exiting);
		if (detached > 0) {
			diplomacy.event(gameId, context, "ATTACHMENTS_ENDED", null, null, war.id(), Map.of("battalions", detached));
		}
		List<DiplomacyViews.Withdrawal> withdrawals = diplomacy.withdraw(gameId, context, conference.counterpart(), war.id());
		if (!gameControl.finishIfDecided(gameId, context.now())) {
			gameControl.resumeAfterConference(gameId, conference.id(), context.now());
		}
		TreatyRepository.TreatyRow treaty = treaties.treaties(gameId)
			.stream()
			.filter(t -> t.id().equals(treatyId))
			.findFirst()
			.orElseThrow();
		return new Settled(new DiplomacyViews.TreatyView(treaty.id(), treaty.warId(), null, treaty.signedRound(),
				treaty.signedTurn(), queries.readTerms(treaty.termsJson()), treaty.truceUntilRound()), withdrawals);
	}

	/** Truces between the two sides (general), or between the exiting nation and the other side (separate). */
	private void truces(UUID gameId, UUID treatyId, int until, List<RelationsRepository.ParticipantRow> participants,
			UUID exiting) {
		String exitingSide = exiting == null ? null
				: participants.stream().filter(p -> p.nationId().equals(exiting)).findFirst().orElseThrow().side();
		for (RelationsRepository.ParticipantRow x : participants) {
			for (RelationsRepository.ParticipantRow y : participants) {
				if (!"ATTACKER".equals(x.side()) || !"DEFENDER".equals(y.side())) {
					continue;
				}
				boolean involved = exiting == null || ("ATTACKER".equals(exitingSide) ? x.nationId().equals(exiting)
						: y.nationId().equals(exiting));
				if (involved) {
					relations.insertTruce(gameId, new RelationsRepository.TruceRow(treatyId, x.nationId(), y.nationId(), until));
				}
			}
		}
	}

	/** Computer participants react after the commit (Phase 11). */
	private <T> T announce(UUID gameId, T result) {
		events.publishEvent(new ConferenceActivity(gameId));
		return result;
	}

	// --- helpers --------------------------------------------------------------------------------

	/**
	 * Who may give or receive in this conference, with their side: every nation still fighting the war
	 * (general peace), or the exiting nation and the opposing side (separate peace).
	 */
	static Map<UUID, String> negotiatingSides(DiplomacyState.Picture picture, ConferenceRepository.ConferenceRow conference) {
		Map<UUID, String> sides = new LinkedHashMap<>();
		List<RelationsRepository.ParticipantRow> participants = picture.participantsOf(conference.warId());
		if (!conference.separate()) {
			participants.forEach(p -> sides.put(p.nationId(), p.side()));
			return sides;
		}
		String exitingSide = participants.stream()
			.filter(p -> p.nationId().equals(conference.requester()))
			.map(RelationsRepository.ParticipantRow::side)
			.findFirst()
			.orElse(null);
		if (exitingSide == null) {
			return sides;
		}
		sides.put(conference.requester(), exitingSide);
		participants.stream().filter(p -> !p.side().equals(exitingSide)).forEach(p -> sides.put(p.nationId(), p.side()));
		return sides;
	}

	private PeaceTerms.Facts facts(UUID gameId, WorldApi.WorldSnapshot snapshot, Map<UUID, String> sides,
			DiplomacyState.Picture picture) {
		return queries.conferenceFacts(snapshot, sides, picture.rules());
	}

	private DiplomacyState.Picture available(UUID gameId) {
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		if (!picture.rules().features().peaceConferences()) {
			throw DiplomacyErrors.conflict("CONFERENCES_NOT_AVAILABLE",
					"In this game's ruleset peace is proposed turn by turn, not in conferences.");
		}
		return picture;
	}

	private static RelationsRepository.WarRow activeWar(DiplomacyState.Picture picture, UUID warId) {
		RelationsRepository.WarRow war = picture.war(warId).orElseThrow(DiplomacyErrors::warNotFound);
		if (!war.active()) {
			throw DiplomacyErrors.conflict("WAR_NOT_ACTIVE", "This war has already ended.");
		}
		return war;
	}

	private ConferenceRepository.ConferenceRow conference(UUID gameId, UUID conferenceId) {
		return conferences.find(gameId, conferenceId)
			.orElseThrow(() -> DiplomacyErrors.conflict("CONFERENCE_NOT_FOUND", "Peace conference not found."));
	}

	private ConferenceRepository.RevisionRow currentRevision(ConferenceRepository.ConferenceRow conference) {
		return conferences.revisions(conference.id())
			.stream()
			.filter(r -> r.revision() == conference.currentRevision())
			.findFirst()
			.orElseThrow();
	}

	List<UUID> required(ConferenceRepository.RevisionRow revision) {
		return jsonMapper.readValue(revision.requiredJson(), new TypeReference<List<UUID>>() {
		});
	}

	private static boolean npc(WorldApi.WorldSnapshot snapshot, UUID nationId) {
		return snapshot.nations().stream().anyMatch(n -> n.id().equals(nationId) && n.npc());
	}

	private static com.reach.strategy.platform.web.ApiException notOpen() {
		return DiplomacyErrors.conflict("CONFERENCE_NOT_OPEN", "This peace conference is not open for that any more.");
	}

	private DiplomacyViews.ConferenceResult result(UUID gameId, UUID viewer, UUID conferenceId,
			DiplomacyViews.TreatyView treaty, List<DiplomacyViews.Withdrawal> withdrawals) {
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow();
		DiplomacyQueries.Ctx ctx = queries.context(gameId, picture, world.snapshot(gameId), viewer, 0);
		return new DiplomacyViews.ConferenceResult(queries.conferenceView(ctx, conference(gameId, conferenceId)), treaty,
				withdrawals);
	}

	/** Every conference command replays by showing the conference as it is now. */
	private abstract class Handler implements GameCommands.Handler<DiplomacyViews.ConferenceResult> {

		private final UUID gameId;

		Handler(UUID gameId) {
			this.gameId = gameId;
		}

		@Override
		public DiplomacyViews.ConferenceResult replay(GameCommands.CommandContext context, UUID resultRef) {
			UUID viewer = DiplomacyQueries.nationOf(world.snapshot(gameId), context.gamePlayerId());
			return result(gameId, viewer, resultRef, null, List.of());
		}

	}

}
