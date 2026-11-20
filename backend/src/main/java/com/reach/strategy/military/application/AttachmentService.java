package com.reach.strategy.military.application;

import com.reach.strategy.military.api.MilitaryViews;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.Hostility;
import com.reach.strategy.military.api.MilitaryModifiers;
import com.reach.strategy.military.domain.MilitaryStore;
import com.reach.strategy.military.rules.Forces;
import com.reach.strategy.military.rules.MilitaryRules;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Attached battalions (Phase 10, docs/COMBAT_AND_SIEGE.md §7): an owner places selected battalions
 * under an ally's operational command and may take them back at any time on its own turn. The
 * commander may move and attack with them, nothing else; ownership, upkeep, casualties and cohorts
 * stay with the owner. Attaching or detaching never resets movement: eligibility is per battalion
 * and, from ruleset v6, per global round, so a battalion moved on its owner's turn cannot move again
 * on the commander's turn of the same round.
 */
@Service
public class AttachmentService {

	private final GameCommands gameCommands;
	private final MilitaryRulesets rulesets;
	private final WorldApi world;
	private final MilitaryStore store;
	private final List<MilitaryModifiers> modifiers;
	private final List<Hostility> hostility;

	AttachmentService(GameCommands gameCommands, MilitaryRulesets rulesets, WorldApi world,
			MilitaryStore store, List<MilitaryModifiers> modifiers, List<Hostility> hostility) {
		this.gameCommands = gameCommands;
		this.rulesets = rulesets;
		this.world = world;
		this.store = store;
		this.modifiers = List.copyOf(modifiers);
		this.hostility = List.copyOf(hostility);
	}

	public MilitaryViews.AttachmentResult attach(CurrentPlayer me, UUID gameId, List<UUID> battalionIds,
			UUID commanderNationId, UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "ATTACH_BATTALIONS", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<MilitaryViews.AttachmentResult> execute(GameCommands.CommandContext context) {
				MilitaryService.State state = MilitaryService.State.load(world, modifiers, hostility, gameId);
				UUID owner = state.nationOfPlayer(context.gamePlayerId());
				if (!state.relations().coalitions()) {
					throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_AVAILABLE",
							"This game's ruleset has no attached battalions.");
				}
				WorldApi.NationState commander = state.snapshot()
					.nations()
					.stream()
					.filter(n -> n.id().equals(commanderNationId))
					.findFirst()
					.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NATION_NOT_FOUND", "Nation not found in this game."));
				if (commander.id().equals(owner)) {
					throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CANNOT_TARGET_SELF",
							"Your battalions are already under your command.");
				}
				if (commander.eliminated() || !state.relations().mayCommand(owner, commander.id())) {
					throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_ALLOWED",
							"Battalions can only be placed under an ally's command (an alliance or the same side of a war).");
				}
				Forces before = store.load(gameId);
				List<Forces.Unit> selected = ownBattalions(before, battalionIds, owner);
				if (selected.stream().anyMatch(b -> b.commanderNationId() != null)) {
					throw new ApiException(HttpStatus.CONFLICT, "BATTALION_ATTACHED",
							"A selected battalion is already under an ally's command; detach it first.");
				}
				WorldApi.ProvinceState province = state.province(state.keyOf(selected.getFirst().provinceStateId()));
				if (!state.relations().mayEnter(commander.id(), province.ownerNationId(), province.controllerNationId())) {
					throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NO_MILITARY_ACCESS",
							"Your ally has no military access to this province.");
				}
				Set<UUID> previousArmies = new HashSet<>();
				selected.forEach(b -> {
					if (b.armyId() != null) {
						previousArmies.add(b.armyId());
					}
				});
				List<Forces.Unit> attached = selected.stream().map(b -> b.attachedTo(commander.id())).toList();
				store.persist(gameId, before, before.update(attached).withoutEmptyFormations(previousArmies));
				return new GameCommands.Outcome<>(result(state, owner, attached, context), attached.getFirst().id());
			}

			@Override
			public MilitaryViews.AttachmentResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return replayResult(gameId, battalionIds, context);
			}

		});
	}

	/** The owner takes its battalions back under its own command. */
	public MilitaryViews.AttachmentResult detach(CurrentPlayer me, UUID gameId, List<UUID> battalionIds,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "DETACH_BATTALIONS", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<MilitaryViews.AttachmentResult> execute(GameCommands.CommandContext context) {
				MilitaryService.State state = MilitaryService.State.load(world, modifiers, hostility, gameId);
				UUID owner = state.nationOfPlayer(context.gamePlayerId());
				Forces before = store.load(gameId);
				List<Forces.Unit> selected = ownBattalions(before, battalionIds, owner);
				if (selected.stream().anyMatch(b -> b.commanderNationId() == null)) {
					throw new ApiException(HttpStatus.CONFLICT, "BATTALION_NOT_ATTACHED",
							"A selected battalion is not under an ally's command.");
				}
				List<Forces.Unit> detached = selected.stream().map(Forces.Unit::detached).toList();
				store.persist(gameId, before, before.update(detached));
				return new GameCommands.Outcome<>(result(state, owner, detached, context), detached.getFirst().id());
			}

			@Override
			public MilitaryViews.AttachmentResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return replayResult(gameId, battalionIds, context);
			}

		});
	}

	private static List<Forces.Unit> ownBattalions(Forces forces, List<UUID> battalionIds, UUID owner) {
		Set<UUID> ids = new HashSet<>(battalionIds);
		List<Forces.Unit> selected = new ArrayList<>(forces.units(ids));
		if (ids.isEmpty() || selected.size() != ids.size()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "BATTALION_NOT_FOUND", "Battalion not found in this game.");
		}
		if (selected.stream().anyMatch(b -> !b.nationId().equals(owner))) {
			throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_BATTALION",
					"Only the owner can attach or detach its battalions.");
		}
		if (selected.stream().map(Forces.Unit::provinceStateId).distinct().count() != 1) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BATTALIONS_NOT_COLOCATED",
					"All selected battalions must be in the same province.");
		}
		selected.sort(Comparator.comparing(b -> b.id().toString()));
		return selected;
	}

	private MilitaryViews.AttachmentResult result(MilitaryService.State state, UUID viewer, List<Forces.Unit> selected,
			GameCommands.CommandContext context) {
		MilitaryRules rules = rulesets.rules(context.rulesetVersion());
		return new MilitaryViews.AttachmentResult(selected.stream()
			.map(b -> MilitaryService.view(b, state, viewer, MilitaryService.clock(rules, context), rules))
			.toList());
	}

	private MilitaryViews.AttachmentResult replayResult(UUID gameId, List<UUID> battalionIds,
			GameCommands.CommandContext context) {
		MilitaryService.State state = MilitaryService.State.load(world, modifiers, hostility, gameId);
		UUID viewer = state.nationOfPlayer(context.gamePlayerId());
		List<Forces.Unit> current = new ArrayList<>(store.units(gameId)
			.stream()
			.filter(b -> battalionIds.contains(b.id()))
			.toList());
		current.sort(Comparator.comparing(b -> b.id().toString()));
		return result(state, viewer, current, context);
	}

}
