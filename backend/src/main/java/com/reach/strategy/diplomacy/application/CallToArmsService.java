package com.reach.strategy.diplomacy.application;

import com.reach.strategy.diplomacy.api.DiplomacyViews;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.diplomacy.domain.CallToArmsRepository;
import com.reach.strategy.diplomacy.domain.RelationsRepository;
import com.reach.strategy.diplomacy.rules.Diplomacy;
import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.stereotype.Service;

/**
 * Defensive calls to arms (ruleset v6, docs/DIPLOMACY.md §12): nobody is forced into a war. The
 * attacked war leader calls an ally; on its own turn the ally honours the call (it joins the
 * defending side and fights every attacking nation) or declines (the alliance ends — no reputation
 * or opinion effects exist yet). Calls stay pending until answered or until the war ends; there is
 * no deadline. Both commands go through the normal command pipeline, so a future AI can answer
 * through the same service.
 */
@Service
public class CallToArmsService {

	private final GameCommands gameCommands;
	private final DiplomacyState state;
	private final RelationsRepository relations;
	private final CallToArmsRepository calls;
	private final DiplomacyService diplomacy;
	private final DiplomacyQueries queries;
	private final WorldApi world;
	private final DiplomacyDraft.Factory drafts;

	CallToArmsService(GameCommands gameCommands, DiplomacyState state, RelationsRepository relations,
			CallToArmsRepository calls, DiplomacyService diplomacy, DiplomacyQueries queries, WorldApi world,
			DiplomacyDraft.Factory drafts) {
		this.drafts = drafts;
		this.gameCommands = gameCommands;
		this.state = state;
		this.relations = relations;
		this.calls = calls;
		this.diplomacy = diplomacy;
		this.queries = queries;
		this.world = world;
	}

	/** The defending war leader asks one of its allies to join the war. */
	public DiplomacyViews.CallResult call(CurrentPlayer me, UUID gameId, UUID warId, UUID allyNationId,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "CALL_TO_ARMS", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.CallResult> execute(GameCommands.CommandContext context) {
				DiplomacyState.Picture picture = available(gameId);
				UUID caller = nationOf(gameId, context);
				RelationsRepository.WarRow war = picture.war(warId).orElseThrow(DiplomacyErrors::warNotFound);
				if (!war.active()) {
					throw DiplomacyErrors.conflict("WAR_NOT_ACTIVE", "This war has already ended.");
				}
				if (!war.defenderLeader().equals(caller)) {
					throw DiplomacyErrors.forbidden("NOT_DEFENDING_LEADER",
							"Only the attacked war leader can call its allies to arms.");
				}
				RelationsRepository.AllianceRow alliance = picture.alliances()
					.stream()
					.filter(a -> "ACTIVE".equals(a.status()) && a.involves(caller) && a.involves(allyNationId)
							&& !caller.equals(allyNationId))
					.findFirst()
					.orElseThrow(() -> DiplomacyErrors.conflict("NOT_ALLIED", "You have no active alliance with that nation."));
				if (picture.sideOf(warId, allyNationId).isPresent()) {
					throw DiplomacyErrors.conflict("ALREADY_IN_WAR", "That nation already fights in this war.");
				}
				boolean pending = calls.all(gameId)
					.stream()
					.anyMatch(c -> c.pending() && c.warId().equals(warId) && c.ally().equals(allyNationId));
				if (pending) {
					throw DiplomacyErrors.conflict("CALL_ALREADY_PENDING", "That ally has already been called to this war.");
				}
				UUID id = UUID.randomUUID();
				DiplomacyDraft draft = drafts.begin(gameId, context.now());
				draft.insertCall(new CallToArmsRepository.CallRow(id, warId, caller, allyNationId, alliance.id(), "PENDING",
						context.round(), null, context.now()));
				draft.write();
				diplomacy.event(gameId, context, "CALL_TO_ARMS", caller, allyNationId, warId, Map.of("callId", id));
				return new GameCommands.Outcome<>(result(gameId, id, caller), id);
			}

			@Override
			public DiplomacyViews.CallResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return result(gameId, resultRef, nationOf(gameId, context));
			}

		});
	}

	/**
	 * The called ally answers on its own turn. Honouring joins the defending side (re-checked now: it
	 * must not be allied with, in truce with or already fighting any attacking nation); declining
	 * ends the alliance, and battalions that lose access withdraw.
	 */
	public DiplomacyViews.CallResult answer(CurrentPlayer me, UUID gameId, UUID callId, boolean honor,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "ANSWER_CALL", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.CallResult> execute(GameCommands.CommandContext context) {
				DiplomacyState.Picture picture = available(gameId);
				UUID nation = nationOf(gameId, context);
				CallToArmsRepository.CallRow call = calls.find(gameId, callId)
					.filter(c -> c.ally().equals(nation) || c.caller().equals(nation))
					.orElseThrow(() -> DiplomacyErrors.conflict("CALL_NOT_FOUND", "Call to arms not found."));
				if (!call.ally().equals(nation)) {
					throw DiplomacyErrors.forbidden("NOT_CALLED_NATION", "Only the called ally can answer.");
				}
				if (!call.pending()) {
					throw DiplomacyErrors.conflict("CALL_NOT_PENDING", "This call to arms has already been answered.");
				}
				RelationsRepository.WarRow war = picture.war(call.warId()).orElseThrow();
				if (!war.active()) {
					throw DiplomacyErrors.conflict("WAR_NOT_ACTIVE", "This war has already ended.");
				}
				DiplomacyDraft draft = drafts.begin(gameId, context.now());
				if (honor) {
					honor(draft, gameId, context, picture, call, war);
				}
				else {
					decline(draft, gameId, context, picture, call);
				}
				draft.write();
				return new GameCommands.Outcome<>(result(gameId, callId, nation), callId);
			}

			@Override
			public DiplomacyViews.CallResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return result(gameId, resultRef, nationOf(gameId, context));
			}

		});
	}

	/**
	 * Joins the side the call names. Before ruleset v8 a truce with any nation of the opposing side
	 * forbids it; from v8 a truce forbids only declaring war, so a valid call can be honoured anyway
	 * (the truce gives way to the war). Alliances, other wars and fighting together still forbid it, and
	 * an invitation to attack requires the normal alliance to still be in force.
	 */
	private void honor(DiplomacyDraft draft, UUID gameId, GameCommands.CommandContext context,
			DiplomacyState.Picture picture,
			CallToArmsRepository.CallRow call, RelationsRepository.WarRow war) {
		Diplomacy.Standing standing = picture.standing();
		String side = call.side();
		String enemySide = "ATTACKER".equals(side) ? "DEFENDER" : "ATTACKER";
		String enemyWords = "ATTACKER".equals(enemySide) ? "the attacking side" : "the defending side";
		if (picture.sideOf(war.id(), call.ally()).isPresent()) {
			throw DiplomacyErrors.conflict("ALREADY_IN_WAR", "You already fight in this war.");
		}
		if (call.offensive() && picture.alliances()
			.stream()
			.noneMatch(a -> a.id().equals(call.allianceId()) && "ACTIVE".equals(a.status()))) {
			throw DiplomacyErrors.conflict("NOT_ALLIED", "Your alliance with the inviting nation has ended.");
		}
		boolean truceBlocks = !picture.rules().features().truceAllowsCallsToArms();
		List<UUID> attackers = picture.participantsOf(war.id())
			.stream()
			.filter(p -> enemySide.equals(p.side()))
			.map(RelationsRepository.ParticipantRow::nationId)
			.toList();
		for (UUID enemy : attackers) {
			if (standing.alliance(call.ally(), enemy) != null) {
				throw DiplomacyErrors.conflict("ALLIED_WITH_ENEMY",
						"You are allied with a nation of " + enemyWords + "; end that alliance first.");
			}
			if (truceBlocks && standing.inTruce(call.ally(), enemy, context.round())) {
				throw DiplomacyErrors.conflict("TRUCE_IN_EFFECT", "A truce binds you to a nation of " + enemyWords + ".");
			}
			if (standing.atWar(call.ally(), enemy)) {
				throw DiplomacyErrors.conflict("ALREADY_AT_WAR",
						"You already fight a nation of " + enemyWords + " in another war.");
			}
			if (standing.coBelligerents().contains(Diplomacy.Pair.of(call.ally(), enemy))) {
				throw DiplomacyErrors.conflict("FIGHTING_TOGETHER",
						"You fight on the same side as a nation of " + enemyWords + " in another war.");
			}
		}
		if (!draft.closeCall(call.id(), "HONORED", context.round())) {
			throw DiplomacyErrors.conflict("CALL_NOT_PENDING", "This call to arms has already been answered.");
		}
		draft.insertParticipant(new RelationsRepository.ParticipantRow(war.id(), call.ally(), side, context.round(),
				"CALL_TO_ARMS"));
		for (UUID enemy : attackers) {
			// Fronts are stored attacker first.
			if ("DEFENDER".equals(side)) {
				draft.insertFront(war.id(), enemy, call.ally());
			}
			else {
				draft.insertFront(war.id(), call.ally(), enemy);
			}
			diplomacy.endPeacefulTies(draft, gameId, context, enemy, call.ally(), war.id());
		}
		diplomacy.event(gameId, context, "CALL_HONORED", call.ally(), call.caller(), war.id(), Map.of("callId", call.id()));
		diplomacy.event(gameId, context, "JOINED_WAR", call.ally(), "DEFENDER".equals(side) ? war.attackerLeader()
				: war.defenderLeader(), war.id(), Map.of("DEFENDER".equals(side) ? "defending" : "attackingWith", call.caller()));
	}

	private void decline(DiplomacyDraft draft, UUID gameId, GameCommands.CommandContext context,
			DiplomacyState.Picture picture, CallToArmsRepository.CallRow call) {
		if (!draft.closeCall(call.id(), "DECLINED", context.round())) {
			throw DiplomacyErrors.conflict("CALL_NOT_PENDING", "This call to arms has already been answered.");
		}
		diplomacy.event(gameId, context, "CALL_DECLINED", call.ally(), call.caller(), call.warId(),
				Map.of("callId", call.id()));
		if (call.offensive()) {
			// Nobody owes an ally an attack: declining an invitation leaves the alliance as it is.
			return;
		}
		// Declining a defensive call breaks the alliance (no other consequence yet).
		if (draft.closeAlliance(call.allianceId(), "ACTIVE", "ENDED", "CALL_DECLINED", context.round())) {
			RelationsRepository.AllianceRow alliance = picture.alliances()
				.stream()
				.filter(a -> a.id().equals(call.allianceId()))
				.findFirst()
				.orElseThrow();
			diplomacy.event(gameId, context, "ALLIANCE_ENDED", call.ally(), call.caller(), null,
					Map.of("allianceId", call.allianceId(), "kind", alliance.kind(), "reason", "CALL_DECLINED"));
			draft.write();
			diplomacy.withdraw(gameId, context, call.ally(), null);
		}
	}

	private DiplomacyState.Picture available(UUID gameId) {
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		if (!picture.rules().features().callsToArms()) {
			throw DiplomacyErrors.conflict("CALLS_TO_ARMS_NOT_AVAILABLE",
					"In this game's ruleset allies join a defensive war automatically.");
		}
		return picture;
	}

	private UUID nationOf(UUID gameId, GameCommands.CommandContext context) {
		return DiplomacyQueries.nationOf(world.snapshot(gameId), context.gamePlayerId());
	}

	private DiplomacyViews.CallResult result(UUID gameId, UUID callId, UUID viewer) {
		return new DiplomacyViews.CallResult(queries.callView(calls.find(gameId, callId).orElseThrow(), viewer));
	}

}
