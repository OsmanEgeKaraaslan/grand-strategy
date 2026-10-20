package com.reach.strategy.world.web;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.reach.strategy.world.api.WorldApi;
import com.reach.strategy.world.application.WorldService;
import com.reach.strategy.world.domain.MapTemplate;
import com.reach.strategy.world.rules.MapDefinition;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Static map templates for rendering. Contains no per-game state. */
@RestController
@RequestMapping("/api/v1/maps")
class MapController {

	private final WorldService world;

	MapController(WorldService world) {
		this.world = world;
	}

	/** Published maps that new games can be created on. */
	@GetMapping
	List<MapChoice> maps() {
		return world.maps()
			.stream()
			.map(m -> new MapChoice(m.id(), m.key(), m.name(), m.maxPlayers(), m.nations().size(),
					(int) m.nations().stream().filter(WorldApi.NationOption::playable).count()))
			.toList();
	}

	@GetMapping("/{mapId}")
	ResponseEntity<MapView> map(@PathVariable UUID mapId) {
		MapTemplate template = world.template(mapId);
		// A map id identifies an immutable template version, so clients may cache it for long.
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate()).body(MapView.of(template));
	}

	record MapView(UUID id, String key, int version, String name, String viewBox, List<ProvinceView> provinces,
			List<RiverView> rivers, List<NationView> nations) {

		static MapView of(MapTemplate template) {
			MapDefinition map = template.definition();
			List<ProvinceView> provinces = map.provinces().stream().map(province -> {
				MapTemplate.ProvinceShape shape = template.geometry().provinces().get(province.key());
				List<NeighbourView> neighbours = map.adjacencies()
					.stream()
					.filter(edge -> edge.touches(province.key()))
					.map(edge -> new NeighbourView(edge.other(province.key()), edge.crossing().name()))
					.sorted((a, b) -> a.provinceKey().compareTo(b.provinceKey()))
					.toList();
				return new ProvinceView(province.key(), province.name(), province.terrain().name(), province.cityName(),
						shape.svgPath(), shape.labelX(), shape.labelY(), shape.cityX(), shape.cityY(), neighbours);
			}).toList();
			List<RiverView> rivers = template.geometry()
				.rivers()
				.stream()
				.map(river -> new RiverView(river.a(), river.b(), river.svgPath()))
				.toList();
			List<NationView> nations = map.nations()
				.stream()
				.map(nation -> new NationView(nation.id(), nation.key(), nation.name(), nation.color(),
						nation.capitalProvinceKey(), nation.startProvinceKeys(), nation.tier().name(), nation.playable()))
				.toList();
			return new MapView(map.id(), map.key(), map.version(), map.name(), template.geometry().viewBox(), provinces,
					rivers, nations);
		}

	}

	record ProvinceView(String key, String name, String terrain, String cityName, String svgPath, int labelX, int labelY,
			int cityX, int cityY, List<NeighbourView> neighbours) {
	}

	/** @param maxPlayers human players the map supports; the other nations are NPC-controlled */
	record MapChoice(UUID id, String key, String name, int maxPlayers, int nations, int playableNations) {
	}

	record NeighbourView(String provinceKey, String crossing) {
	}

	record RiverView(String a, String b, String svgPath) {
	}

	record NationView(UUID id, String key, String name, String color, String capitalProvinceKey,
			List<String> startProvinceKeys, String tier, boolean playable) {
	}

}
