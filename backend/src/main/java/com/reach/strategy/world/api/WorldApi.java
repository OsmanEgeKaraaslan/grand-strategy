package com.reach.strategy.world.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The world module's public interface for other modules: map catalogue and topology, game-world
 * instantiation, read access to a game's world state, and the few world mutations other modules
 * may perform (population, military control) inside their own command or round transaction.
 */
public interface WorldApi {

	/** The map new games are created on (Phase 3 has exactly one published map). */
	UUID defaultMapId();

	/** Basic facts about a map, including its selectable nations. */
	MapSummary mapSummary(UUID mapId);

	/** Provinces, terrain and the adjacency graph of a map — no geometry. */
	Topology topology(UUID mapId);

	/**
	 * Creates the independent, mutable world of one game from its map template: one nation per
	 * assignment, every province owned and controlled by its starting nation, one city per
	 * province. Must run inside the caller's transaction (all-or-nothing with the game start).
	 *
	 * @param gamePlayerByNation the game_player id that plays each nation template; must cover
	 * every nation of the map (no unplayed nations until neutral land exists, Q-W4)
	 */
	void instantiate(UUID gameId, UUID mapId, Map<UUID, UUID> gamePlayerByNation);

	/** Current world state of a started game. */
	WorldSnapshot snapshot(UUID gameId);

	/**
	 * Adds (positive) or removes (negative) civilians, e.g. recruitment. Must run inside the
	 * caller's transaction.
	 *
	 * @return the new population
	 * @throws IllegalStateException if the population would become negative
	 */
	int changePopulation(UUID gameId, UUID cityId, int delta);

	/**
	 * Changes only the military controller of a province (occupation, liberation, return of control
	 * after peace). Legal ownership is never changed here (Q-W1). Must run inside the caller's transaction.
	 */
	void setController(UUID gameId, String provinceKey, UUID controllerNationId);

	/**
	 * Legal ownership transfer by an accepted peace treaty (Phase 9) — the only way ownership ever
	 * changes. The new owner also becomes the controller; city, buildings and population stay with the
	 * province. Must run inside the caller's transaction.
	 *
	 * @throws IllegalStateException unless {@code fromNationId} owns the province right now
	 */
	void transferOwnership(UUID gameId, String provinceKey, UUID fromNationId, UUID toNationId);

	record MapSummary(UUID id, String name, int maxPlayers, List<NationOption> nations) {

		public Optional<NationOption> nation(UUID nationTemplateId) {
			return nations.stream().filter(nation -> nation.id().equals(nationTemplateId)).findFirst();
		}

	}

	record NationOption(UUID id, String key, String name, String color, String capitalProvinceKey, int provinceCount) {
	}

	record WorldSnapshot(UUID mapId, List<NationState> nations, List<ProvinceState> provinces) {

		public Optional<ProvinceState> province(String provinceKey) {
			return provinces.stream().filter(p -> p.provinceKey().equals(provinceKey)).findFirst();
		}

		public Optional<ProvinceState> provinceOfCity(UUID cityId) {
			return provinces.stream().filter(p -> p.cityId().equals(cityId)).findFirst();
		}

	}

	record NationState(UUID id, UUID nationTemplateId, String name, String color, UUID gamePlayerId) {
	}

	/** Owner and controller are separate: they differ while a province is occupied (Q-W1). */
	record ProvinceState(String provinceKey, UUID provinceStateId, UUID ownerNationId, UUID controllerNationId,
			UUID cityId, String cityName, int population) {
	}

	/** Terrain as the rules see it. */
	enum Terrain {

		PLAINS, MOUNTAINS

	}

	record ProvinceInfo(String key, String name, Terrain terrain) {
	}

	/** Undirected border; {@code river} marks a river crossing. */
	record Border(String a, String b, boolean river) {
	}

	record Topology(List<ProvinceInfo> provinces, List<Border> borders) {

		public Optional<ProvinceInfo> province(String key) {
			return provinces.stream().filter(p -> p.key().equals(key)).findFirst();
		}

		public Optional<Border> border(String a, String b) {
			return borders.stream()
				.filter(edge -> (edge.a().equals(a) && edge.b().equals(b)) || (edge.a().equals(b) && edge.b().equals(a)))
				.findFirst();
		}

		/** Neighbours of a province in key order (deterministic). */
		public List<String> neighbours(String key) {
			return borders.stream()
				.filter(edge -> edge.a().equals(key) || edge.b().equals(key))
				.map(edge -> edge.a().equals(key) ? edge.b() : edge.a())
				.sorted()
				.toList();
		}

	}

}
