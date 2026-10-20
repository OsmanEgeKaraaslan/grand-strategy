package com.reach.strategy.economy.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.economy.domain.Building;
import com.reach.strategy.economy.domain.BuildingRepository;
import com.reach.strategy.economy.rules.CityEconomy;
import com.reach.strategy.economy.rules.EconomyRules;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.stereotype.Component;

/**
 * Loads one game's economic inputs (cities with population, owner, controller and buildings) and
 * runs the pure calculation for every city. Shared by round processing and queries, so the
 * income a player is shown is exactly the income they will be paid.
 */
@Component
class EconomyState {

	private final WorldApi world;
	private final BuildingRepository buildings;

	EconomyState(WorldApi world, BuildingRepository buildings) {
		this.world = world;
		this.buildings = buildings;
	}

	record City(WorldApi.ProvinceState province, List<Building> buildings, CityEconomy.Income income) {

		UUID cityId() {
			return province.cityId();
		}

		boolean occupied() {
			return !province.ownerNationId().equals(province.controllerNationId());
		}

	}

	record Snapshot(WorldApi.WorldSnapshot world, List<City> cities) {

		City city(UUID cityId) {
			return cities.stream().filter(city -> city.cityId().equals(cityId)).findFirst().orElse(null);
		}

		/** Income the nation would collect at the next round end (collectible income of cities it owns). */
		long collectibleIncomeOf(UUID nationId) {
			long total = 0;
			for (City city : cities) {
				total = Math.addExact(total,
						CityEconomy.collectedBy(nationId, city.province().ownerNationId(), city.income()));
			}
			return total;
		}

		WorldApi.NationState nationOfPlayer(UUID gamePlayerId) {
			return world.nations()
				.stream()
				.filter(nation -> gamePlayerId.equals(nation.gamePlayerId()))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("No nation for game player " + gamePlayerId));
		}

	}

	Snapshot load(UUID gameId, EconomyRules rules) {
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		Map<UUID, List<Building>> byCity = buildings.findByGameIdOrderById(gameId)
			.stream()
			.collect(Collectors.groupingBy(Building::cityId));
		List<City> cities = snapshot.provinces()
			.stream()
			.sorted(Comparator.comparing(WorldApi.ProvinceState::provinceKey))
			.map(province -> {
				List<Building> cityBuildings = byCity.getOrDefault(province.cityId(), List.of())
					.stream()
					.sorted(Comparator.comparing(Building::createdAt).thenComparing(Building::id))
					.toList();
				CityEconomy.Production production = CityEconomy.production(province.population(),
						cityBuildings.stream().map(Building::asWorkshop).toList(), rules);
				return new City(province, cityBuildings,
						CityEconomy.income(production, province.ownerNationId(), province.controllerNationId()));
			})
			.toList();
		return new Snapshot(snapshot, cities);
	}

}
