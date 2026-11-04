package com.reach.strategy.world.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;
import com.reach.strategy.world.domain.MapTemplate;
import com.reach.strategy.world.domain.MapTemplateReader;
import com.reach.strategy.world.domain.WorldStateRepository;
import com.reach.strategy.world.rules.MapDefinition;
import com.reach.strategy.world.rules.MapGeometry;
import com.reach.strategy.world.rules.MapValidator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorldService implements WorldApi {

	private final String defaultMapKey;
	private final MapTemplateReader templates;
	private final WorldStateRepository worldState;
	// Templates are immutable once published, so caching them for the process lifetime is safe.
	private final Map<UUID, MapTemplate> cache = new ConcurrentHashMap<>();

	WorldService(MapTemplateReader templates, WorldStateRepository worldState,
			@Value("${strategy.world.default-map:grand-campaign}") String defaultMapKey) {
		this.defaultMapKey = defaultMapKey;
		this.templates = templates;
		this.worldState = worldState;
	}

	@Override
	public UUID defaultMapId() {
		return mapId(defaultMapKey)
			.orElseThrow(() -> new IllegalStateException("Default map " + defaultMapKey + " is missing"));
	}

	@Override
	public Optional<UUID> mapId(String key) {
		return templates.findLatestId(key);
	}

	@Override
	public List<MapSummary> maps() {
		return templates.allMapIds().stream().map(this::mapSummary).toList();
	}

	@Override
	public MapSummary mapSummary(UUID mapId) {
		MapDefinition map = template(mapId).definition();
		List<NationOption> nations = map.nations()
			.stream()
			.map(nation -> new NationOption(nation.id(), nation.key(), nation.name(), nation.color(),
					nation.capitalProvinceKey(), nation.startProvinceKeys().size(), nation.tier().name(), nation.playable()))
			.toList();
		return new MapSummary(map.id(), map.key(), map.name(), map.maxPlayers(), nations);
	}

	public MapTemplate template(UUID mapId) {
		return cache.computeIfAbsent(mapId, id -> templates.load(id)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MAP_NOT_FOUND", "Map not found.")));
	}

	/** Validates every stored map; returns problems per map key (empty map = all valid). */
	public Map<String, List<String>> validateAllMaps() {
		Map<String, List<String>> problems = new HashMap<>();
		for (UUID mapId : templates.allMapIds()) {
			MapTemplate template = template(mapId);
			List<String> found = new ArrayList<>(MapValidator.validate(template.definition()));
			Map<String, String> outlines = new HashMap<>();
			template.geometry().provinces().forEach((key, shape) -> outlines.put(key, shape.svgPath()));
			found.addAll(MapGeometry.validate(template.definition(), outlines));
			template.definition()
				.provinces()
				.stream()
				.filter(province -> !template.geometry().provinces().containsKey(province.key()))
				.forEach(province -> found.add("Province has no geometry: " + province.key()));
			if (!found.isEmpty()) {
				problems.put(template.definition().key() + " v" + template.definition().version(), found);
			}
		}
		return problems;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void instantiate(UUID gameId, UUID mapId, Map<UUID, UUID> gamePlayerByNation) {
		instantiate(gameId, mapId, gamePlayerByNation, java.util.Set.of());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void instantiate(UUID gameId, UUID mapId, Map<UUID, UUID> gamePlayerByNation,
			java.util.Set<UUID> computerGamePlayers) {
		MapDefinition map = template(mapId).definition();
		Map<UUID, MapDefinition.Nation> nationsById = new HashMap<>();
		map.nations().forEach(nation -> nationsById.put(nation.id(), nation));
		if (!nationsById.keySet().containsAll(gamePlayerByNation.keySet())
				|| new HashSet<>(gamePlayerByNation.values()).size() != gamePlayerByNation.size()) {
			throw new IllegalArgumentException("Players must choose distinct nations of the map");
		}
		if (worldState.exists(gameId)) {
			throw new IllegalStateException("World of game " + gameId + " already exists");
		}

		Map<String, UUID> nationIdByKey = new HashMap<>();
		List<WorldStateRepository.NationRow> nations = new ArrayList<>();
		for (MapDefinition.Nation nation : map.nations()) {
			UUID id = UUID.randomUUID();
			nationIdByKey.put(nation.key(), id);
			UUID gamePlayer = gamePlayerByNation.get(nation.id());
			// Nations without a player are NPC-controlled; computer seats are AI-controlled (Phase 11).
			String control = gamePlayer == null ? "NPC" : computerGamePlayers.contains(gamePlayer) ? "AI" : "HUMAN";
			nations.add(new WorldStateRepository.NationRow(id, gameId, nation.id(), gamePlayer, nation.name(),
					nation.color(), null, control));
		}

		Map<String, String> owners = map.startingOwners();
		List<WorldStateRepository.ProvinceRow> provinces = new ArrayList<>();
		List<WorldStateRepository.CityRow> cities = new ArrayList<>();
		for (MapDefinition.Province province : map.provinces()) {
			UUID owner = nationIdByKey.get(owners.get(province.key()));
			UUID provinceStateId = UUID.randomUUID();
			// At the start the legal owner also holds military control.
			provinces.add(new WorldStateRepository.ProvinceRow(provinceStateId, gameId, province.id(), owner, owner));
			cities.add(new WorldStateRepository.CityRow(UUID.randomUUID(), gameId, provinceStateId, province.cityName(),
					province.startPopulation()));
		}

		worldState.insertNations(nations);
		worldState.insertProvinces(provinces);
		worldState.insertCities(cities);
	}

	@Override
	@Transactional(readOnly = true)
	public WorldSnapshot snapshot(UUID gameId) {
		List<NationState> nations = worldState.nations(gameId)
			.stream()
			.map(row -> new NationState(row.id(), row.nationTemplateId(), row.name(), row.color(), row.gamePlayerId(),
					row.control(), row.eliminatedRound()))
			.toList();
		List<ProvinceState> provinces = worldState.provinces(gameId)
			.stream()
			.map(row -> new ProvinceState(row.provinceKey(), row.provinceStateId(), row.ownerNationId(),
					row.controllerNationId(), row.cityId(), row.cityName(), row.population()))
			.toList();
		return new WorldSnapshot(worldState.mapIdOf(gameId).orElse(null), nations, provinces);
	}

	@Override
	public Topology topology(UUID mapId) {
		MapDefinition map = template(mapId).definition();
		List<ProvinceInfo> provinces = map.provinces()
			.stream()
			.map(p -> new ProvinceInfo(p.key(), p.name(), Terrain.valueOf(p.terrain().name())))
			.toList();
		List<Border> borders = map.adjacencies()
			.stream()
			.map(edge -> new Border(edge.a(), edge.b(), edge.crossing() == MapDefinition.Crossing.RIVER))
			.toList();
		return new Topology(provinces, borders);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public int changePopulation(UUID gameId, UUID cityId, int delta) {
		return worldState.changePopulation(gameId, cityId, delta);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void transferOwnership(UUID gameId, String provinceKey, UUID fromNationId, UUID toNationId) {
		worldState.transferOwnership(gameId, provinceKey, fromNationId, toNationId);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void markEliminated(UUID gameId, UUID nationId, int round) {
		worldState.markEliminated(gameId, nationId, round);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void handOverToComputer(UUID gameId, UUID gamePlayerId) {
		worldState.handOverToComputer(gameId, gamePlayerId);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void setController(UUID gameId, String provinceKey, UUID controllerNationId) {
		worldState.setController(gameId, provinceKey, controllerNationId);
	}

}
