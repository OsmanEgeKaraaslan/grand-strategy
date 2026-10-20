package com.reach.strategy.diplomacy.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.diplomacy.domain.CallToArmsRepository;
import com.reach.strategy.diplomacy.domain.ConferenceRepository;
import com.reach.strategy.diplomacy.domain.RelationsRepository;
import com.reach.strategy.diplomacy.domain.TreatyRepository;
import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.game.api.GameControl;
import com.reach.strategy.military.api.ForceWithdrawal;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.stereotype.Component;

/**
 * National elimination (ruleset v6, docs/DIPLOMACY.md §15): after a settlement, every nation that
 * legally owns no province any more is eliminated. The deterministic policy, in nation id order:
 * <ol>
 * <li>the nation is marked eliminated (its row stays, so battle and diplomatic history keep it);</li>
 * <li>its battalions dissolve and their soldiers return as civilians to their origin cities, its
 * armies are deleted, battalions attached to it return to their owners;</li>
 * <li>its alliances end, its pending alliance proposals and calls to arms are cancelled, its open
 * peace conference requests are cancelled, tributes it pays or receives are cancelled;</li>
 * <li>it leaves every war; a war leader is succeeded by the remaining nation of its side with the
 * most provinces (ties: lowest id); a side left empty ends that war;</li>
 * <li>a human player takes no more turns.</li>
 * </ol>
 * Its treasury stays as it is and can no longer be used. Losing battles or having every province
 * occupied never eliminates a nation — only legally owning nothing does.
 */
@Component
class EliminationService {

	private final DiplomacyState state;
	private final RelationsRepository relations;
	private final TreatyRepository treaties;
	private final CallToArmsRepository calls;
	private final ConferenceRepository conferences;
	private final DiplomacyService diplomacy;
	private final WorldApi world;
	private final ForceWithdrawal withdrawal;
	private final GameControl gameControl;

	EliminationService(DiplomacyState state, RelationsRepository relations, TreatyRepository treaties,
			CallToArmsRepository calls, ConferenceRepository conferences, DiplomacyService diplomacy, WorldApi world,
			ForceWithdrawal withdrawal, GameControl gameControl) {
		this.state = state;
		this.relations = relations;
		this.treaties = treaties;
		this.calls = calls;
		this.conferences = conferences;
		this.diplomacy = diplomacy;
		this.world = world;
		this.withdrawal = withdrawal;
		this.gameControl = gameControl;
	}

	/** @return the nations eliminated now, in id order */
	List<UUID> eliminateLandless(UUID gameId, GameCommands.CommandContext context) {
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow();
		if (!picture.rules().features().elimination()) {
			return List.of();
		}
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		Map<UUID, Integer> owned = new HashMap<>();
		snapshot.provinces().forEach(p -> owned.merge(p.ownerNationId(), 1, Integer::sum));
		List<WorldApi.NationState> landless = snapshot.nations()
			.stream()
			.filter(n -> !n.eliminated() && owned.getOrDefault(n.id(), 0) == 0)
			.sorted(Comparator.comparing(WorldApi.NationState::id))
			.toList();
		List<UUID> eliminated = new ArrayList<>();
		for (WorldApi.NationState nation : landless) {
			eliminate(gameId, context, nation, owned);
			eliminated.add(nation.id());
		}
		return eliminated;
	}

	private void eliminate(UUID gameId, GameCommands.CommandContext context, WorldApi.NationState nation,
			Map<UUID, Integer> owned) {
		UUID id = nation.id();
		world.markEliminated(gameId, id, context.round());
		int soldiers = withdrawal.dissolveNation(gameId, id);

		DiplomacyState.Picture picture = state.load(gameId).orElseThrow();
		for (RelationsRepository.AllianceRow a : picture.alliances()) {
			if (!a.involves(id)) {
				continue;
			}
			if ("ACTIVE".equals(a.status())) {
				relations.closeAlliance(gameId, a.id(), "ACTIVE", "ENDED", "ELIMINATED", context.round(), context.now());
				diplomacy.event(gameId, context, "ALLIANCE_ENDED", id, a.other(id), null,
						Map.of("allianceId", a.id(), "kind", a.kind(), "reason", "ELIMINATED"));
			}
			else if ("PROPOSED".equals(a.status())) {
				relations.closeAlliance(gameId, a.id(), "PROPOSED", "CANCELLED", "ELIMINATED", context.round(),
						context.now());
			}
		}
		calls.all(gameId)
			.stream()
			.filter(c -> c.pending() && (c.ally().equals(id) || c.caller().equals(id)))
			.forEach(c -> calls.close(gameId, c.id(), "CANCELLED", context.round(), context.now()));
		conferences.all(gameId)
			.stream()
			.filter(c -> "REQUESTED".equals(c.status()) && c.principal(id))
			.forEach(c -> conferences.close(gameId, c.id(), "REQUESTED", "CANCELLED", context.now()));
		for (TreatyRepository.TributeRow t : treaties.tributes(gameId)) {
			if (t.active() && (t.payer().equals(id) || t.receiver().equals(id))
					&& treaties.cancelTribute(gameId, t.id(), "ELIMINATED", context.round())) {
				diplomacy.event(gameId, context, "TRIBUTE_CANCELLED", t.payer(), t.receiver(), null,
						Map.of("tributeId", t.id(), "reason", "ELIMINATED"));
			}
		}
		for (RelationsRepository.WarRow war : picture.wars()) {
			if (!war.active() || picture.sideOf(war.id(), id).isEmpty()) {
				continue;
			}
			String side = picture.sideOf(war.id(), id).orElseThrow();
			relations.leaveWar(gameId, war.id(), id, context.round(), "ELIMINATED");
			boolean leader = war.attackerLeader().equals(id) || war.defenderLeader().equals(id);
			List<UUID> remaining = picture.participantsOf(war.id())
				.stream()
				.filter(p -> p.side().equals(side) && !p.nationId().equals(id))
				.map(RelationsRepository.ParticipantRow::nationId)
				.sorted()
				.toList();
			if (remaining.isEmpty()) {
				relations.endWar(gameId, war.id(), context.round(), context.turnNumber(), "ELIMINATION");
				calls.all(gameId)
					.stream()
					.filter(c -> c.pending() && c.warId().equals(war.id()))
					.forEach(c -> calls.close(gameId, c.id(), "CANCELLED", context.round(), context.now()));
			}
			else if (leader) {
				UUID successor = remaining.getFirst();
				for (UUID candidate : remaining) {
					if (owned.getOrDefault(candidate, 0) > owned.getOrDefault(successor, 0)) {
						successor = candidate;
					}
				}
				relations.setLeader(gameId, war.id(), side, successor);
				diplomacy.event(gameId, context, "WAR_LEADER_CHANGED", successor, id, war.id(), Map.of("side", side));
			}
		}
		if (nation.gamePlayerId() != null) {
			gameControl.eliminatePlayer(gameId, nation.gamePlayerId(), context.round());
		}
		diplomacy.event(gameId, context, "NATION_ELIMINATED", id, null, null, Map.of("soldiersReturned", soldiers));
	}

}
