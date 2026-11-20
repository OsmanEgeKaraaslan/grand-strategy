package com.reach.strategy.siege.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.reach.strategy.economy.api.WorkshopsApi;
import com.reach.strategy.military.api.Hostility;
import com.reach.strategy.military.api.MilitaryModifiers;
import com.reach.strategy.military.api.MilitaryPresence;
import com.reach.strategy.military.api.WarEvents;
import com.reach.strategy.siege.domain.Fortification;
import com.reach.strategy.siege.domain.FortificationRepository;
import com.reach.strategy.siege.domain.Siege;
import com.reach.strategy.siege.domain.SiegeRepository;
import com.reach.strategy.siege.domain.SiegeRoundRepository;
import com.reach.strategy.siege.rules.ControlDecision;
import com.reach.strategy.siege.rules.SiegeMath;
import com.reach.strategy.siege.rules.SiegeRules;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies {@link ControlDecision} to provinces: starts and lifts sieges when a command moves
 * battalions, and at round end advances sieges (attrition, progress, capture, bombardment) and
 * performs simple occupation of unfortified provinces. Everything runs inside the caller's
 * transaction; participants and artillery always come from authoritative battalion state.
 */
@Component
class SiegeEngine {

	private final SiegeRulesets rulesets;
	private final FortificationRepository fortifications;
	private final SiegeRepository sieges;
	private final SiegeRoundRepository siegeRounds;
	private final WorldApi world;
	private final MilitaryPresence military;
	private final WorkshopsApi workshops;
	private final List<MilitaryModifiers> modifiers;
	private final List<Hostility> hostility;
	private final List<WarEvents> warEvents;

	SiegeEngine(SiegeRulesets rulesets, FortificationRepository fortifications, SiegeRepository sieges,
			SiegeRoundRepository siegeRounds, WorldApi world, MilitaryPresence military, WorkshopsApi workshops,
			List<MilitaryModifiers> modifiers, List<Hostility> hostility, List<WarEvents> warEvents) {
		this.hostility = List.copyOf(hostility);
		this.warEvents = List.copyOf(warEvents);
		this.modifiers = List.copyOf(modifiers);
		this.rulesets = rulesets;
		this.fortifications = fortifications;
		this.sieges = sieges;
		this.siegeRounds = siegeRounds;
		this.world = world;
		this.military = military;
		this.workshops = workshops;
	}

	/** Mid-turn: start sieges a hostile force has established, lift those it abandoned or lost. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void presenceChanged(UUID gameId, Set<UUID> provinceStateIds, int round, int turnNumber, Instant now) {
		presenceChanged(provinceStateIds, round, turnNumber, now, load(gameId));
	}

	/** The same, with the snapshot and relations the calling command already read (see SiegeGate). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void presenceChanged(UUID gameId, Set<UUID> provinceStateIds, int round, int turnNumber, Instant now,
			WorldApi.WorldSnapshot snapshot, Hostility.Relations relations) {
		presenceChanged(provinceStateIds, round, turnNumber, now, load(gameId, snapshot, relations));
	}

	private void presenceChanged(Set<UUID> provinceStateIds, int round, int turnNumber, Instant now, Ctx ctx) {
		ctx.provincesInKeyOrder().filter(p -> provinceStateIds.contains(p.provinceStateId())).forEach(p -> {
			switch (decide(ctx, p)) {
				case ControlDecision.StartSiege start -> start(ctx, p, start.besiegerNationId(), round, turnNumber, now);
				case ControlDecision.LiftSiege lift -> ctx.activeSiege(p).lift(round);
				default -> {
					// Occupation waits for the round end; a continuing siege has nothing to do mid-turn.
				}
			}
		});
		sieges.flush();
	}

	/** Round end, step SIEGES: every province in key order, each siege at most once per round. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void roundEnd(UUID gameId, int round, int turnNumber, Instant now) {
		Ctx ctx = load(gameId);
		ctx.provincesInKeyOrder().forEach(p -> {
			switch (decide(ctx, p)) {
				case ControlDecision.Occupy occupy -> world.setController(gameId, p.provinceKey(), occupy.nationId());
				case ControlDecision.StartSiege start ->
					advance(ctx, start(ctx, p, start.besiegerNationId(), round, turnNumber, now), p, round);
				case ControlDecision.ContinueSiege cont -> advance(ctx, ctx.activeSiege(p), p, round);
				case ControlDecision.LiftSiege lift -> ctx.activeSiege(p).lift(round);
				case ControlDecision.None none -> {
				}
			}
		});
		sieges.flush();
		fortifications.flush();
	}

	private ControlDecision.Action decide(Ctx ctx, WorldApi.ProvinceState p) {
		Map<UUID, Integer> manpower = new java.util.TreeMap<>();
		ctx.presenceIn(p).forEach(b -> manpower.merge(b.nationId(), b.manpower(), Integer::sum));
		Fortification fortress = ctx.fortificationOf(p);
		Siege active = ctx.activeSiegeOrNull(p);
		return ControlDecision.decide(p.controllerNationId(), manpower, fortress == null ? 0 : fortress.effectiveLevel(),
				active == null ? null : active.besiegerNationId(), ctx.relations()::atWar, ctx.relations().coalitions());
	}

	private Siege start(Ctx ctx, WorldApi.ProvinceState p, UUID besieger, int round, int turnNumber, Instant now) {
		Fortification fortress = ctx.fortificationOf(p);
		SiegeRules rules = rulesets.rules(fortress.rulesetVersion());
		Siege siege = sieges.saveAndFlush(Siege.start(ctx.gameId(), p.provinceStateId(), p.cityId(), besieger,
				p.controllerNationId(), fortress.effectiveLevel(), SiegeMath.required(fortress.effectiveLevel(), rules),
				round, turnNumber, now));
		ctx.active().put(p.provinceStateId(), siege);
		return siege;
	}

	/**
	 * One round of siege: civilians lost (≈5%, floored), progress from the besiegers' artillery as it
	 * stands now, and the fall when the requirement is reached.
	 */
	private void advance(Ctx ctx, Siege siege, WorldApi.ProvinceState p, int round) {
		if (!siege.processableIn(round)) {
			return;
		}
		Fortification fortress = ctx.fortificationOf(p);
		SiegeRules rules = rulesets.rules(fortress.rulesetVersion());
		int artillery = ctx.presenceIn(p)
			.stream()
			.filter(b -> b.nationId().equals(siege.besiegerNationId()) && b.artillery())
			.mapToInt(MilitaryPresence.Presence::manpower)
			.sum();
		int population = p.population();
		int lost = SiegeMath.attrition(population, rules);
		if (lost > 0) {
			world.changePopulation(ctx.gameId(), p.cityId(), -lost);
		}
		// Siege Engineering (doctrine) of the besieger as it stands at this round end.
		int bonus = modifiers.isEmpty() ? 0
				: modifiers.getFirst().of(ctx.gameId(), siege.besiegerNationId()).siegeArtilleryBonusBps();
		int added = SiegeMath.progressPerRound(artillery, rules, bonus);
		boolean fallen = siege.advance(round, added, lost);
		siegeRounds.insert(ctx.gameId(), siege.id(),
				new SiegeRoundRepository.Row(round, artillery, added, siege.progress(), population, lost));
		if (fallen) {
			capture(ctx, siege, fortress, p, artillery > 0, rules, round);
		}
	}

	/**
	 * The city falls (COMBAT_AND_SIEGE §5.6): with artillery the fortress is DAMAGED and one ACTIVE
	 * workshop too; control (never ownership) passes to the besieger. Runs once: the siege leaves
	 * ACTIVE in the same transaction.
	 */
	private void capture(Ctx ctx, Siege siege, Fortification fortress, WorldApi.ProvinceState p, boolean withArtillery,
			SiegeRules rules, int round) {
		UUID damagedWorkshop = null;
		if (withArtillery) {
			fortress.bombarded(SiegeMath.integrityAfterBombardment(fortress.integrity(), rules));
			List<SiegeMath.WorkshopCandidate> candidates = workshops.workshops(ctx.gameId(), p.cityId())
				.stream()
				.map(w -> new SiegeMath.WorkshopCandidate(w.buildingId(), w.tier(), "ACTIVE".equals(w.status())))
				.toList();
			for (UUID buildingId : SiegeMath.workshopsToDamage(candidates, rules)) {
				workshops.damage(ctx.gameId(), buildingId);
				if (damagedWorkshop == null) {
					damagedWorkshop = buildingId;
				}
			}
		}
		else {
			fortress.cancelUpgrade();
		}
		fortifications.save(fortress);
		world.setController(ctx.gameId(), p.provinceKey(), siege.besiegerNationId());
		siege.succeed(round, withArtillery, damagedWorkshop);
		WarEvents.SiegeWon won = new WarEvents.SiegeWon(siege.id(), round, siege.besiegerNationId(),
				siege.defenderNationId(), siege.cityId());
		warEvents.forEach(events -> events.siegeWon(ctx.gameId(), won));
	}

	private Ctx load(UUID gameId) {
		return load(gameId, world.snapshot(gameId),
				hostility.isEmpty() ? Hostility.Relations.TOTAL_WAR : hostility.getFirst().relations(gameId));
	}

	private Ctx load(UUID gameId, WorldApi.WorldSnapshot snapshot, Hostility.Relations relations) {
		Map<UUID, List<MilitaryPresence.Presence>> presence = military.presence(gameId)
			.stream()
			.collect(Collectors.groupingBy(MilitaryPresence.Presence::provinceStateId));
		Map<UUID, Fortification> forts = fortifications.findByGameIdOrderById(gameId)
			.stream()
			.collect(Collectors.toMap(Fortification::cityId, Function.identity()));
		Map<UUID, Siege> active = sieges.findByGameIdAndStatusOrderById(gameId, Siege.Status.ACTIVE)
			.stream()
			.collect(Collectors.toMap(Siege::provinceStateId, Function.identity()));
		return new Ctx(gameId, snapshot, presence, forts, new java.util.HashMap<>(active), relations);
	}

	private record Ctx(UUID gameId, WorldApi.WorldSnapshot snapshot, Map<UUID, List<MilitaryPresence.Presence>> presence,
			Map<UUID, Fortification> forts, Map<UUID, Siege> active, Hostility.Relations relations) {

		java.util.stream.Stream<WorldApi.ProvinceState> provincesInKeyOrder() {
			return snapshot.provinces().stream().sorted(Comparator.comparing(WorldApi.ProvinceState::provinceKey));
		}

		List<MilitaryPresence.Presence> presenceIn(WorldApi.ProvinceState p) {
			return presence.getOrDefault(p.provinceStateId(), List.of());
		}

		Fortification fortificationOf(WorldApi.ProvinceState p) {
			return forts.get(p.cityId());
		}

		Siege activeSiegeOrNull(WorldApi.ProvinceState p) {
			Siege siege = active.get(p.provinceStateId());
			return siege != null && siege.status() == Siege.Status.ACTIVE ? siege : null;
		}

		Siege activeSiege(WorldApi.ProvinceState p) {
			Siege siege = activeSiegeOrNull(p);
			if (siege == null) {
				throw new IllegalStateException("No active siege in " + p.provinceKey());
			}
			return siege;
		}

	}

	/** Provinces of a game whose city is under an active siege (for read models). */
	@Transactional(readOnly = true)
	public Set<UUID> besiegedProvinces(UUID gameId) {
		return new HashSet<>(sieges.findByGameIdAndStatusOrderById(gameId, Siege.Status.ACTIVE)
			.stream()
			.map(Siege::provinceStateId)
			.toList());
	}

}
