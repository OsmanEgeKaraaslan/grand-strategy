package com.reach.strategy.military.application;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.reach.strategy.military.api.ForceWithdrawal;
import com.reach.strategy.military.api.Hostility;
import com.reach.strategy.military.api.SiegeGate;
import com.reach.strategy.military.domain.MilitaryStore;
import com.reach.strategy.military.rules.Cohorts;
import com.reach.strategy.military.rules.Forces;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdrawal of battalions that stand where their nation may no longer be (docs/DIPLOMACY.md §6.3):
 * after a peace treaty former enemies have no right to each other's territory, and after an
 * alliance ends neither does the former partner. Deterministic: battalions in id order, each to the
 * nearest province its nation controls without enemy battalions (breadth-first over the borders,
 * ties by province key), judged on positions before anyone withdraws — so an army's battalions,
 * which always stand together, all reach the same province. Nobody is deleted, split or
 * reinforced; cohorts and movement state are unchanged.
 */
@Service
class WithdrawalService implements ForceWithdrawal {

	private final WorldApi world;
	private final MilitaryStore store;
	private final List<Hostility> hostility;
	private final List<SiegeGate> siegeGates;

	WithdrawalService(WorldApi world, MilitaryStore store, List<Hostility> hostility, List<SiegeGate> siegeGates) {
		this.world = world;
		this.store = store;
		this.hostility = List.copyOf(hostility);
		this.siegeGates = List.copyOf(siegeGates);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public List<Withdrawn> withdrawWithoutAccess(UUID gameId, int round, int turnNumber, Instant now) {
		MilitaryService.State state = MilitaryService.State.load(world, List.of(), hostility, gameId);
		Hostility.Relations relations = state.relations();
		Map<UUID, WorldApi.ProvinceState> byId = state.snapshot()
			.provinces()
			.stream()
			.collect(Collectors.toMap(WorldApi.ProvinceState::provinceStateId, p -> p));
		Forces before = store.load(gameId);
		List<Forces.Unit> all = before.units();
		Map<UUID, Set<UUID>> nationsByProvince = new HashMap<>();
		all.forEach(b -> nationsByProvince.computeIfAbsent(b.provinceStateId(), id -> new HashSet<>()).add(b.nationId()));

		List<Withdrawn> withdrawn = new ArrayList<>();
		List<Forces.Unit> moved = new ArrayList<>();
		for (Forces.Unit battalion : all) {
			WorldApi.ProvinceState here = byId.get(battalion.provinceStateId());
			if (relations.mayEnter(battalion.nationId(), here.ownerNationId(), here.controllerNationId())) {
				continue;
			}
			UUID nation = battalion.nationId();
			Predicate<WorldApi.ProvinceState> noEnemies = p -> nationsByProvince
				.getOrDefault(p.provinceStateId(), Set.of())
				.stream()
				.noneMatch(other -> relations.atWar(other, nation));
			Optional<WorldApi.ProvinceState> target = nearest(state, here.provinceKey(),
					p -> nation.equals(p.controllerNationId()) && noEnemies.test(p))
				.or(() -> nearest(state, here.provinceKey(),
						p -> relations.mayEnter(nation, p.ownerNationId(), p.controllerNationId()) && noEnemies.test(p)));
			if (target.isEmpty()) {
				// Unreachable on a connected map while the nation still holds a province: it stays put.
				continue;
			}
			// The army is kept: its battalions all withdraw to the same province.
			moved.add(battalion.relocatedTo(target.get().provinceStateId(), battalion.armyId()));
			withdrawn.add(new Withdrawn(battalion.id(), nation, here.provinceKey(), target.get().provinceKey()));
		}
		store.persist(gameId, before, before.update(moved));
		Set<UUID> everywhere = new HashSet<>(byId.keySet());
		siegeGates.forEach(gate -> gate.presenceChanged(gameId, everywhere, round, turnNumber, now));
		return withdrawn;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public int endInvalidAttachments(UUID gameId) {
		Hostility.Relations relations = MilitaryService.State.load(world, List.of(), hostility, gameId).relations();
		Set<UUID> eliminated = new HashSet<>();
		world.snapshot(gameId).nations().stream().filter(WorldApi.NationState::eliminated).forEach(n -> eliminated.add(n.id()));
		Forces before = store.load(gameId);
		List<Forces.Unit> ended = before.units()
			.stream()
			.filter(b -> b.commanderNationId() != null)
			.filter(b -> eliminated.contains(b.commanderNationId())
					|| !relations.mayCommand(b.nationId(), b.commanderNationId()))
			.map(Forces.Unit::detached)
			.toList();
		store.persist(gameId, before, before.update(ended));
		return ended.size();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public int endAttachmentsOf(UUID gameId, UUID nationId) {
		Forces before = store.load(gameId);
		List<Forces.Unit> ended = before.units()
			.stream()
			.filter(b -> b.commanderNationId() != null)
			.filter(b -> b.nationId().equals(nationId) || b.commanderNationId().equals(nationId))
			.map(Forces.Unit::detached)
			.toList();
		store.persist(gameId, before, before.update(ended));
		return ended.size();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public int dissolveNation(UUID gameId, UUID nationId) {
		int returned = 0;
		Forces before = store.load(gameId);
		Forces after = before;
		for (Forces.Unit battalion : before.units()) {
			if (nationId.equals(battalion.commanderNationId())) {
				after = after.update(battalion.detached());
			}
			if (!battalion.nationId().equals(nationId)) {
				continue;
			}
			// Soldiers go home to the city they were raised in, in origin city order.
			for (Cohorts.Cohort cohort : before.cohortsOf(battalion.id())
				.stream()
				.sorted(java.util.Comparator.comparing(c -> c.originCityId().toString()))
				.toList()) {
				world.changePopulation(gameId, cohort.originCityId(), cohort.soldiers());
				returned += cohort.soldiers();
			}
			after = after.remove(battalion.id());
		}
		for (Forces.Formation army : before.formations()) {
			if (army.nationId().equals(nationId)) {
				after = after.disband(army.id());
			}
		}
		store.persist(gameId, before, after);
		return returned;
	}

	/** Breadth-first from {@code start} (excluded); within one distance the lowest province key wins. */
	private static Optional<WorldApi.ProvinceState> nearest(MilitaryService.State state, String start,
			Predicate<WorldApi.ProvinceState> wanted) {
		Set<String> seen = new HashSet<>(Set.of(start));
		Deque<String> frontier = new ArrayDeque<>(List.of(start));
		while (!frontier.isEmpty()) {
			List<String> next = new ArrayList<>();
			for (String key : frontier) {
				for (String neighbour : state.topology().neighbours(key)) {
					if (seen.add(neighbour)) {
						next.add(neighbour);
					}
				}
			}
			next.sort(String::compareTo);
			for (String key : next) {
				WorldApi.ProvinceState province = state.province(key);
				if (wanted.test(province)) {
					return Optional.of(province);
				}
			}
			frontier = new ArrayDeque<>(next);
		}
		return Optional.empty();
	}

}
