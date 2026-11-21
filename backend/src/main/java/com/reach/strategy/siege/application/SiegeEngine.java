package com.reach.strategy.siege.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.economy.api.WorkshopsApi;
import com.reach.strategy.military.api.Hostility;
import com.reach.strategy.military.api.MilitaryModifiers;
import com.reach.strategy.military.api.MilitaryPresence;
import com.reach.strategy.military.api.WarEvents;
import com.reach.strategy.siege.domain.SiegeRoundRepository;
import com.reach.strategy.siege.domain.SiegeStore;
import com.reach.strategy.siege.rules.ControlDecision;
import com.reach.strategy.siege.rules.SiegeMath;
import com.reach.strategy.siege.rules.SiegeRules;
import com.reach.strategy.siege.rules.Strongholds;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies {@link ControlDecision} to provinces: starts and lifts sieges when a command moves
 * battalions, and at round end advances sieges (attrition, progress, capture, bombardment) and
 * performs simple occupation of unfortified provinces. Everything runs inside the caller's
 * transaction; participants and artillery always come from authoritative battalion state.
 * Fortresses and sieges change as {@link Strongholds} values; the changes are written before any
 * other module is called, so every module always reads the current state.
 */
@Component
class SiegeEngine {

	private final SiegeRulesets rulesets;
	private final SiegeStore store;
	private final SiegeRoundRepository siegeRounds;
	private final WorldApi world;
	private final MilitaryPresence military;
	private final WorkshopsApi workshops;
	private final List<MilitaryModifiers> modifiers;
	private final List<Hostility> hostility;
	private final List<WarEvents> warEvents;

	SiegeEngine(SiegeRulesets rulesets, SiegeStore store, SiegeRoundRepository siegeRounds, WorldApi world,
			MilitaryPresence military, WorkshopsApi workshops, List<MilitaryModifiers> modifiers, List<Hostility> hostility,
			List<WarEvents> warEvents) {
		this.hostility = List.copyOf(hostility);
		this.warEvents = List.copyOf(warEvents);
		this.modifiers = List.copyOf(modifiers);
		this.rulesets = rulesets;
		this.store = store;
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
				case ControlDecision.LiftSiege lift -> ctx.put(ctx.activeSiege(p).lifted(round));
				default -> {
					// Occupation waits for the round end; a continuing siege has nothing to do mid-turn.
				}
			}
		});
		ctx.write();
	}

	/** Round end, step SIEGES: every province in key order, each siege at most once per round. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void roundEnd(UUID gameId, int round, int turnNumber, Instant now) {
		Ctx ctx = load(gameId);
		ctx.provincesInKeyOrder().forEach(p -> {
			switch (decide(ctx, p)) {
				case ControlDecision.Occupy occupy -> {
					ctx.write();
					world.setController(gameId, p.provinceKey(), occupy.nationId());
				}
				case ControlDecision.StartSiege start ->
					advance(ctx, start(ctx, p, start.besiegerNationId(), round, turnNumber, now), p, round);
				case ControlDecision.ContinueSiege cont -> advance(ctx, ctx.activeSiege(p), p, round);
				case ControlDecision.LiftSiege lift -> ctx.put(ctx.activeSiege(p).lifted(round));
				case ControlDecision.None none -> {
				}
			}
		});
		ctx.write();
	}

	private ControlDecision.Action decide(Ctx ctx, WorldApi.ProvinceState p) {
		Map<UUID, Integer> manpower = new java.util.TreeMap<>();
		ctx.presenceIn(p).forEach(b -> manpower.merge(b.nationId(), b.manpower(), Integer::sum));
		Strongholds.Fortress fortress = ctx.fortificationOf(p);
		Strongholds.Siege active = ctx.activeSiegeOrNull(p);
		return ControlDecision.decide(p.controllerNationId(), manpower, fortress == null ? 0 : fortress.effectiveLevel(),
				active == null ? null : active.besiegerNationId(), ctx.relations()::atWar, ctx.relations().coalitions());
	}

	private Strongholds.Siege start(Ctx ctx, WorldApi.ProvinceState p, UUID besieger, int round, int turnNumber,
			Instant now) {
		Strongholds.Fortress fortress = ctx.fortificationOf(p);
		SiegeRules rules = rulesets.rules(fortress.rulesetVersion());
		Strongholds.Siege siege = Strongholds.Siege.start(UUID.randomUUID(), p.provinceStateId(), p.cityId(), besieger,
				p.controllerNationId(), fortress.effectiveLevel(), SiegeMath.required(fortress.effectiveLevel(), rules),
				round, turnNumber, now);
		ctx.put(siege);
		return siege;
	}

	/**
	 * One round of siege: civilians lost (≈5%, floored), progress from the besiegers' artillery as it
	 * stands now, and the fall when the requirement is reached.
	 */
	private void advance(Ctx ctx, Strongholds.Siege siege, WorldApi.ProvinceState p, int round) {
		if (!siege.processableIn(round)) {
			return;
		}
		Strongholds.Fortress fortress = ctx.fortificationOf(p);
		SiegeRules rules = rulesets.rules(fortress.rulesetVersion());
		int artillery = ctx.presenceIn(p)
			.stream()
			.filter(b -> b.nationId().equals(siege.besiegerNationId()) && b.artillery())
			.mapToInt(MilitaryPresence.Presence::manpower)
			.sum();
		int population = p.population();
		int lost = SiegeMath.attrition(population, rules);
		ctx.write();
		if (lost > 0) {
			world.changePopulation(ctx.gameId(), p.cityId(), -lost);
		}
		// Siege Engineering (doctrine) of the besieger as it stands at this round end.
		int bonus = modifiers.isEmpty() ? 0
				: modifiers.getFirst().of(ctx.gameId(), siege.besiegerNationId()).siegeArtilleryBonusBps();
		int added = SiegeMath.progressPerRound(artillery, rules, bonus);
		Strongholds.Siege advanced = siege.advanced(round, added, lost);
		ctx.put(advanced);
		ctx.write();
		siegeRounds.insert(ctx.gameId(), siege.id(),
				new SiegeRoundRepository.Row(round, artillery, added, advanced.progress(), population, lost));
		if (advanced.fallen()) {
			capture(ctx, advanced, fortress, p, artillery > 0, rules, round);
		}
	}

	/**
	 * The city falls (COMBAT_AND_SIEGE §5.6): with artillery the fortress is DAMAGED and one ACTIVE
	 * workshop too; control (never ownership) passes to the besieger. Runs once: the siege leaves
	 * ACTIVE in the same transaction.
	 */
	private void capture(Ctx ctx, Strongholds.Siege siege, Strongholds.Fortress fortress, WorldApi.ProvinceState p,
			boolean withArtillery, SiegeRules rules, int round) {
		UUID damagedWorkshop = null;
		if (withArtillery) {
			ctx.put(fortress.bombarded(SiegeMath.integrityAfterBombardment(fortress.integrity(), rules)));
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
			ctx.put(fortress.upgradeCancelled());
		}
		ctx.put(siege.succeeded(round, withArtillery, damagedWorkshop));
		ctx.write();
		world.setController(ctx.gameId(), p.provinceKey(), siege.besiegerNationId());
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
		Strongholds loaded = Strongholds.of(store.fortresses(gameId), store.activeSieges(gameId));
		return new Ctx(gameId, snapshot, presence, relations, loaded);
	}

	/** The state one call works on; {@link #write()} persists what changed since the last write. */
	private final class Ctx {

		private final UUID gameId;
		private final WorldApi.WorldSnapshot snapshot;
		private final Map<UUID, List<MilitaryPresence.Presence>> presence;
		private final Hostility.Relations relations;
		private Strongholds written;
		private Strongholds current;
		private final Map<UUID, UUID> fortressByCity = new HashMap<>();
		private final Map<UUID, UUID> siegeByProvince = new HashMap<>();

		Ctx(UUID gameId, WorldApi.WorldSnapshot snapshot, Map<UUID, List<MilitaryPresence.Presence>> presence,
				Hostility.Relations relations, Strongholds loaded) {
			this.gameId = gameId;
			this.snapshot = snapshot;
			this.presence = presence;
			this.relations = relations;
			this.written = loaded;
			this.current = loaded;
			loaded.fortresses().forEach(f -> fortressByCity.put(f.cityId(), f.id()));
			loaded.sieges().forEach(s -> siegeByProvince.put(s.provinceStateId(), s.id()));
		}

		UUID gameId() {
			return gameId;
		}

		Hostility.Relations relations() {
			return relations;
		}

		java.util.stream.Stream<WorldApi.ProvinceState> provincesInKeyOrder() {
			return snapshot.provinces().stream().sorted(Comparator.comparing(WorldApi.ProvinceState::provinceKey));
		}

		List<MilitaryPresence.Presence> presenceIn(WorldApi.ProvinceState p) {
			return presence.getOrDefault(p.provinceStateId(), List.of());
		}

		Strongholds.Fortress fortificationOf(WorldApi.ProvinceState p) {
			UUID id = fortressByCity.get(p.cityId());
			return id == null ? null : current.fortress(id).orElseThrow();
		}

		Strongholds.Siege activeSiegeOrNull(WorldApi.ProvinceState p) {
			UUID id = siegeByProvince.get(p.provinceStateId());
			Strongholds.Siege siege = id == null ? null : current.siege(id).orElseThrow();
			return siege != null && siege.status() == Strongholds.Siege.Status.ACTIVE ? siege : null;
		}

		Strongholds.Siege activeSiege(WorldApi.ProvinceState p) {
			Strongholds.Siege siege = activeSiegeOrNull(p);
			if (siege == null) {
				throw new IllegalStateException("No active siege in " + p.provinceKey());
			}
			return siege;
		}

		void put(Strongholds.Siege siege) {
			current = current.with(siege);
			siegeByProvince.put(siege.provinceStateId(), siege.id());
		}

		void put(Strongholds.Fortress fortress) {
			current = current.with(fortress);
		}

		void write() {
			if (current != written) {
				store.persist(gameId, written, current);
				written = current;
			}
		}

	}

	/** Provinces of a game whose city is under an active siege (for read models). */
	@Transactional(readOnly = true)
	public Set<UUID> besiegedProvinces(UUID gameId) {
		return new HashSet<>(store.activeSieges(gameId).stream().map(Strongholds.Siege::provinceStateId).toList());
	}

}
