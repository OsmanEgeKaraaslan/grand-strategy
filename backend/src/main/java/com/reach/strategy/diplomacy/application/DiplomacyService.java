package com.reach.strategy.diplomacy.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.diplomacy.domain.DiplomacyEventLog;
import com.reach.strategy.diplomacy.domain.RelationsRepository;
import com.reach.strategy.diplomacy.domain.TreatyRepository;
import com.reach.strategy.diplomacy.rules.Diplomacy;
import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.ForceWithdrawal;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Service;

/**
 * War declarations and alliances (docs/DIPLOMACY.md §2–3). Every command runs through the game
 * command pipeline (game lock, membership, idempotency, active player), so two simultaneous
 * declarations cannot both succeed and a proposal is answered at most once; the database's partial
 * unique indexes back that up.
 */
@Service
public class DiplomacyService {

	private final GameCommands gameCommands;
	private final DiplomacyState state;
	private final RelationsRepository relations;
	private final TreatyRepository treaties;
	private final DiplomacyEventLog events;
	private final DiplomacyQueries queries;
	private final WorldApi world;
	private final ForceWithdrawal withdrawal;
	private final JsonMapper jsonMapper;

	DiplomacyService(GameCommands gameCommands, DiplomacyState state, RelationsRepository relations,
			TreatyRepository treaties, DiplomacyEventLog events, DiplomacyQueries queries, WorldApi world,
			ForceWithdrawal withdrawal, JsonMapper jsonMapper) {
		this.gameCommands = gameCommands;
		this.state = state;
		this.relations = relations;
		this.treaties = treaties;
		this.events = events;
		this.queries = queries;
		this.world = world;
		this.withdrawal = withdrawal;
		this.jsonMapper = jsonMapper;
	}

	// --- war ------------------------------------------------------------------------------------

	/**
	 * Declares war on another nation. Allies of the defender join it at once (the defensive
	 * response, PROVISIONAL Q-D6); a tribute between nations now at war again is cancelled. Battalion
	 * movement is untouched: declaring war spends nothing and resets nothing.
	 */
	public DiplomacyViews.WarResult declareWar(CurrentPlayer me, UUID gameId, UUID targetNationId, UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "DECLARE_WAR", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.WarResult> execute(GameCommands.CommandContext context) {
				DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
				WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
				UUID attacker = DiplomacyQueries.nationOf(snapshot, context.gamePlayerId());
				List<UUID> nations = snapshot.nations().stream().map(WorldApi.NationState::id).toList();
				Diplomacy.Standing standing = picture.standing();
				List<Diplomacy.WarBlocker> blockers = Diplomacy.warBlockers(attacker, targetNationId, nations, standing,
						context.round());
				if (!blockers.isEmpty()) {
					throw DiplomacyErrors.war(blockers.getFirst());
				}
				if (eliminated(snapshot, targetNationId)) {
					throw DiplomacyErrors.conflict("NATION_ELIMINATED", "That nation has been eliminated.");
				}
				UUID warId = UUID.randomUUID();
				relations.insertWar(gameId, new RelationsRepository.WarRow(warId, attacker, targetNationId, "ACTIVE",
						context.round(), context.turnNumber(), null, null, null), context.now());
				relations.insertParticipant(gameId,
						new RelationsRepository.ParticipantRow(warId, attacker, "ATTACKER", context.round(), "DECLARED"));
				relations.insertParticipant(gameId, new RelationsRepository.ParticipantRow(warId, targetNationId,
						"DEFENDER", context.round(), "ATTACKED"));
				relations.insertFront(gameId, warId, attacker, targetNationId);
				event(gameId, context, "WAR_DECLARED", attacker, targetNationId, warId, Map.of());
				// Ruleset v6: allies join only by answering a call to arms; before v6 they join at once (Q-D6).
				List<UUID> joiners = picture.rules().features().callsToArms() ? List.of()
						: Diplomacy.defensiveJoiners(attacker, targetNationId, standing, context.round());
				for (UUID ally : joiners) {
					relations.insertParticipant(gameId, new RelationsRepository.ParticipantRow(warId, ally, "DEFENDER",
							context.round(), "DEFENSIVE_RESPONSE"));
					relations.insertFront(gameId, warId, attacker, ally);
					event(gameId, context, "JOINED_WAR", ally, attacker, warId, Map.of("defending", targetNationId));
				}
				List<UUID> defenders = new ArrayList<>(List.of(targetNationId));
				defenders.addAll(joiners);
				for (UUID defender : defenders) {
					endPeacefulTies(gameId, context, attacker, defender, warId);
				}
				return new GameCommands.Outcome<>(result(gameId, attacker, context, warId, joiners), warId);
			}

			@Override
			public DiplomacyViews.WarResult replay(GameCommands.CommandContext context, UUID resultRef) {
				WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
				UUID attacker = DiplomacyQueries.nationOf(snapshot, context.gamePlayerId());
				DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
				List<UUID> joiners = picture.participantsOf(resultRef)
					.stream()
					.filter(p -> "DEFENSIVE_RESPONSE".equals(p.joinReason()))
					.map(RelationsRepository.ParticipantRow::nationId)
					.toList();
				return result(gameId, attacker, context, resultRef, joiners);
			}

		});
	}

	/**
	 * Two nations now at war keep no peaceful obligations towards each other: a running tribute
	 * between them is cancelled (PROVISIONAL) and a pending alliance proposal is withdrawn.
	 */
	void endPeacefulTies(UUID gameId, GameCommands.CommandContext context, UUID x, UUID y, UUID warId) {
		for (TreatyRepository.TributeRow t : treaties.tributes(gameId)) {
			boolean between = (t.payer().equals(x) && t.receiver().equals(y))
					|| (t.payer().equals(y) && t.receiver().equals(x));
			if (between && t.active() && treaties.cancelTribute(gameId, t.id(), "WAR_RENEWED", context.round())) {
				event(gameId, context, "TRIBUTE_CANCELLED", t.payer(), t.receiver(), warId,
						Map.of("tributeId", t.id(), "reason", "WAR_RENEWED"));
			}
		}
		for (RelationsRepository.AllianceRow a : relations.alliances(gameId)) {
			if ("PROPOSED".equals(a.status()) && a.involves(x) && a.involves(y)) {
				relations.closeAlliance(gameId, a.id(), "PROPOSED", "CANCELLED", "WAR", context.round(), context.now());
			}
		}
	}

	private DiplomacyViews.WarResult result(UUID gameId, UUID viewer, GameCommands.CommandContext context, UUID warId,
			List<UUID> joiners) {
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		DiplomacyQueries.Ctx ctx = queries.context(gameId, picture, world.snapshot(gameId), viewer, context.round());
		return new DiplomacyViews.WarResult(queries.warView(ctx, picture.war(warId).orElseThrow()), joiners);
	}

	// --- alliances ------------------------------------------------------------------------------

	/** Proposes an alliance; it becomes active only when the other nation accepts on its own turn. */
	public DiplomacyViews.AllianceResult proposeAlliance(CurrentPlayer me, UUID gameId, UUID targetNationId,
			Diplomacy.AllianceKind kind, UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "PROPOSE_ALLIANCE", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.AllianceResult> execute(GameCommands.CommandContext context) {
				DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
				WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
				UUID proposer = DiplomacyQueries.nationOf(snapshot, context.gamePlayerId());
				List<UUID> nations = snapshot.nations().stream().map(WorldApi.NationState::id).toList();
				List<Diplomacy.AllianceBlocker> blockers = Diplomacy.allianceBlockers(proposer, targetNationId, nations,
						picture.standing(), true);
				if (!blockers.isEmpty()) {
					throw DiplomacyErrors.alliance(blockers.getFirst());
				}
				WorldApi.NationState target = snapshot.nations().stream().filter(n -> n.id().equals(targetNationId)).findFirst().orElseThrow();
				if (target.eliminated()) {
					throw DiplomacyErrors.conflict("NATION_ELIMINATED", "That nation has been eliminated.");
				}
				if (target.npc()) {
					// PROVISIONAL until AI exists (Phase 11): nations without a player make no alliances.
					throw DiplomacyErrors.conflict("NPC_NOT_NEGOTIABLE",
							"This nation is controlled by the game and does not make alliances yet.");
				}
				UUID id = UUID.randomUUID();
				relations.insertAlliance(gameId, new RelationsRepository.AllianceRow(id, kind.name(), proposer,
						targetNationId, proposer, "PROPOSED", context.round(), null, null, null), context.now());
				return new GameCommands.Outcome<>(allianceResult(gameId, proposer, context, id, List.of()), id);
			}

			@Override
			public DiplomacyViews.AllianceResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return allianceReplay(gameId, context, resultRef);
			}

		});
	}

	/** The recipient accepts on its own turn; the standing is re-checked at that moment. */
	public DiplomacyViews.AllianceResult acceptAlliance(CurrentPlayer me, UUID gameId, UUID allianceId,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "ACCEPT_ALLIANCE", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.AllianceResult> execute(GameCommands.CommandContext context) {
				DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
				WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
				UUID nation = DiplomacyQueries.nationOf(snapshot, context.gamePlayerId());
				RelationsRepository.AllianceRow alliance = proposalFor(picture, allianceId, nation);
				List<UUID> nations = snapshot.nations().stream().map(WorldApi.NationState::id).toList();
				List<Diplomacy.AllianceBlocker> blockers = Diplomacy.allianceBlockers(alliance.proposer(), nation,
						nations, picture.standing(), false);
				if (!blockers.isEmpty()) {
					throw DiplomacyErrors.alliance(blockers.getFirst());
				}
				if (!relations.activateAlliance(gameId, allianceId, context.round(), context.now())) {
					throw notPending();
				}
				event(gameId, context, "ALLIANCE_FORMED", alliance.proposer(), nation, null,
						Map.of("allianceId", allianceId, "kind", alliance.kind()));
				return new GameCommands.Outcome<>(allianceResult(gameId, nation, context, allianceId, List.of()),
						allianceId);
			}

			@Override
			public DiplomacyViews.AllianceResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return allianceReplay(gameId, context, resultRef);
			}

		});
	}

	public DiplomacyViews.AllianceResult rejectAlliance(CurrentPlayer me, UUID gameId, UUID allianceId,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "REJECT_ALLIANCE", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.AllianceResult> execute(GameCommands.CommandContext context) {
				DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
				UUID nation = DiplomacyQueries.nationOf(world.snapshot(gameId), context.gamePlayerId());
				proposalFor(picture, allianceId, nation);
				if (!relations.closeAlliance(gameId, allianceId, "PROPOSED", "REJECTED", "REJECTED", context.round(),
						context.now())) {
					throw notPending();
				}
				return new GameCommands.Outcome<>(allianceResult(gameId, nation, context, allianceId, List.of()),
						allianceId);
			}

			@Override
			public DiplomacyViews.AllianceResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return allianceReplay(gameId, context, resultRef);
			}

		});
	}

	/**
	 * Ends an active alliance (either member), or withdraws one's own pending proposal. An alliance
	 * cannot end while both members fight on the same side of a war. The former partners lose
	 * military access at once: battalions in each other's territory withdraw home.
	 */
	public DiplomacyViews.AllianceResult endAlliance(CurrentPlayer me, UUID gameId, UUID allianceId,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "END_ALLIANCE", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.AllianceResult> execute(GameCommands.CommandContext context) {
				DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
				UUID nation = DiplomacyQueries.nationOf(world.snapshot(gameId), context.gamePlayerId());
				RelationsRepository.AllianceRow alliance = picture.alliances()
					.stream()
					.filter(a -> a.id().equals(allianceId) && a.involves(nation))
					.findFirst()
					.orElseThrow(DiplomacyErrors::allianceNotFound);
				if ("PROPOSED".equals(alliance.status())) {
					if (!alliance.proposer().equals(nation)) {
						throw DiplomacyErrors.forbidden("NOT_ALLIANCE_PROPOSER",
								"Only the proposing nation can withdraw an alliance proposal; reject it instead.");
					}
					relations.closeAlliance(gameId, allianceId, "PROPOSED", "CANCELLED", "WITHDRAWN", context.round(),
							context.now());
					return new GameCommands.Outcome<>(allianceResult(gameId, nation, context, allianceId, List.of()),
							allianceId);
				}
				List<Diplomacy.TerminationBlocker> blockers = Diplomacy.terminationBlockers(
						"ACTIVE".equals(alliance.status()),
						picture.fightingTogether(alliance.nationA(), alliance.nationB()));
				if (blockers.contains(Diplomacy.TerminationBlocker.NOT_ACTIVE)) {
					throw DiplomacyErrors.conflict("ALLIANCE_NOT_ACTIVE", "This alliance is no longer active.");
				}
				if (!blockers.isEmpty()) {
					throw DiplomacyErrors.conflict("ALLIES_AT_WAR_TOGETHER",
							"You and your ally fight on the same side of a war; make peace before ending the alliance.");
				}
				relations.closeAlliance(gameId, allianceId, "ACTIVE", "ENDED", "TERMINATED", context.round(), context.now());
				event(gameId, context, "ALLIANCE_ENDED", nation, alliance.other(nation), null,
						Map.of("allianceId", allianceId, "kind", alliance.kind()));
				List<DiplomacyViews.Withdrawal> moved = withdraw(gameId, context, nation, null);
				return new GameCommands.Outcome<>(allianceResult(gameId, nation, context, allianceId, moved),
						allianceId);
			}

			@Override
			public DiplomacyViews.AllianceResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return allianceReplay(gameId, context, resultRef);
			}

		});
	}

	private static RelationsRepository.AllianceRow proposalFor(DiplomacyState.Picture picture, UUID allianceId,
			UUID recipient) {
		RelationsRepository.AllianceRow alliance = picture.alliances()
			.stream()
			.filter(a -> a.id().equals(allianceId) && a.involves(recipient))
			.findFirst()
			.orElseThrow(DiplomacyErrors::allianceNotFound);
		if (!"PROPOSED".equals(alliance.status())) {
			throw notPending();
		}
		if (alliance.proposer().equals(recipient)) {
			throw DiplomacyErrors.forbidden("NOT_ALLIANCE_RECIPIENT", "Only the invited nation can answer this proposal.");
		}
		return alliance;
	}

	private static ApiException notPending() {
		return DiplomacyErrors.conflict("ALLIANCE_NOT_PENDING", "This alliance proposal is no longer pending.");
	}

	private DiplomacyViews.AllianceResult allianceResult(UUID gameId, UUID viewer, GameCommands.CommandContext context,
			UUID allianceId, List<DiplomacyViews.Withdrawal> withdrawals) {
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		DiplomacyQueries.Ctx ctx = queries.context(gameId, picture, world.snapshot(gameId), viewer, context.round());
		RelationsRepository.AllianceRow row = relations.alliance(gameId, allianceId).orElseThrow();
		return new DiplomacyViews.AllianceResult(queries.allianceView(ctx, row), withdrawals);
	}

	private DiplomacyViews.AllianceResult allianceReplay(UUID gameId, GameCommands.CommandContext context,
			UUID resultRef) {
		UUID viewer = DiplomacyQueries.nationOf(world.snapshot(gameId), context.gamePlayerId());
		return allianceResult(gameId, viewer, context, resultRef, List.of());
	}

	static boolean eliminated(WorldApi.WorldSnapshot snapshot, UUID nationId) {
		return snapshot.nations().stream().anyMatch(n -> n.id().equals(nationId) && n.eliminated());
	}

	// --- shared ---------------------------------------------------------------------------------

	/** Withdraws every battalion that lost access and records it publicly. */
	List<DiplomacyViews.Withdrawal> withdraw(UUID gameId, GameCommands.CommandContext context, UUID actor, UUID warId) {
		// Attachments the new relations no longer allow end first, so their battalions withdraw with their owners.
		int detached = withdrawal.endInvalidAttachments(gameId);
		if (detached > 0) {
			event(gameId, context, "ATTACHMENTS_ENDED", actor, null, warId, Map.of("battalions", detached));
		}
		List<DiplomacyViews.Withdrawal> moved = withdrawal
			.withdrawWithoutAccess(gameId, context.round(), context.turnNumber(), context.now())
			.stream()
			.map(w -> new DiplomacyViews.Withdrawal(w.battalionId(), w.nationId(), w.fromProvinceKey(), w.toProvinceKey()))
			.toList();
		if (!moved.isEmpty()) {
			event(gameId, context, "FORCES_WITHDRAWN", actor, null, warId, Map.of("battalions", moved.size()));
		}
		return moved;
	}

	void event(UUID gameId, GameCommands.CommandContext context, String kind, UUID actor, UUID target, UUID warId,
			Map<String, ?> details) {
		events.record(gameId, context.round(), context.turnNumber(), kind, actor, target, warId,
				jsonMapper.writeValueAsString(new LinkedHashMap<>(details)), context.now());
	}

}
