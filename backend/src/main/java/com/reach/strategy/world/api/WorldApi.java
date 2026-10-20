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

	/** The map new games are created on unless the host picks another (configuration `strategy.world.default-map`). */
	UUID defaultMapId();

	/** The latest version of a published map by key, if it exists. */
	Optional<UUID> mapId(String key);

	/** Every published map, for choosing one when creating a game. */
	List<MapSummary> maps();

	/** Basic facts about a map, including its selectable nations. */
	MapSummary mapSummary(UUID mapId);

	/** Provinces, terrain and the adjacency graph of a map — no geometry. */
	Topology topology(UUID mapId);

	/**
	 * Creates the independent, mutable world of one game from its map template: every nation of the
	 * map, every province owned and controlled by its starting nation, one city per province. Nations
	 * in {@code gamePlayerByNation} are HUMAN-controlled, all others NPC-controlled (Phase 10). Must run
	 * inside the caller's transaction (all-or-nothing with the game start).
	 *
	 * @param gamePlayerByNation the game_player id that plays each chosen nation template
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
	 * Marks a nation eliminated in {@code round} (Phase 10: it legally owns no province any more). The
	 * row stays for history. Must run inside the caller's transaction.
	 */
	void markEliminated(UUID gameId, UUID nationId, int round);

	/**
	 * Legal ownership transfer by an accepted peace treaty (Phase 9) — the only way ownership ever
	 * changes. The new owner also becomes the controller; city, buildings and population stay with the
	 * province. Must run inside the caller's transaction.
	 *
	 * @throws IllegalStateException unless {@code fromNationId} owns the province right now
	 */
	void transferOwnership(UUID gameId, String provinceKey, UUID fromNationId, UUID toNationId);

	record MapSummary(UUID id, String key, String name, int maxPlayers, List<NationOption> nations) {

		public Optional<NationOption> nation(UUID nationTemplateId) {
			return nations.stream().filter(nation -> nation.id().equals(nationTemplateId)).findFirst();
		}

	}

	/**
	 * @param tier starting profile: POWER, MAJOR, REGIONAL or MINOR
	 * @param playable whether a human may choose it
	 */
	record NationOption(UUID id, String key, String name, String color, String capitalProvinceKey, int provinceCount,
			String tier, boolean playable) {
	}

	record WorldSnapshot(UUID mapId, List<NationState> nations, List<ProvinceState> provinces) {

		public Optional<ProvinceState> province(String provinceKey) {
			return provinces.stream().filter(p -> p.provinceKey().equals(provinceKey)).findFirst();
		}

		public Optional<ProvinceState> provinceOfCity(UUID cityId) {
			return provinces.stream().filter(p -> p.cityId().equals(cityId)).findFirst();
		}

	}

	/**
	 * @param gamePlayerId null for an NPC-controlled nation
	 * @param control HUMAN or NPC
	 * @param eliminatedRound round in which it was eliminated, or null
	 */
	record NationState(UUID id, UUID nationTemplateId, String name, String color, UUID gamePlayerId, String control,
			Integer eliminatedRound) {

		public NationState(UUID id, UUID nationTemplateId, String name, String color, UUID gamePlayerId) {
			this(id, nationTemplateId, name, color, gamePlayerId, gamePlayerId == null ? "NPC" : "HUMAN", null);
		}

		public boolean eliminated() {
			return eliminatedRound != null;
		}

		public boolean npc() {
			return gamePlayerId == null;
		}

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
