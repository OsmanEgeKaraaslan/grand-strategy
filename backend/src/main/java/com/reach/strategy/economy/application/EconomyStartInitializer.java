package com.reach.strategy.economy.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.economy.domain.Building;
import com.reach.strategy.economy.domain.BuildingRepository;
import com.reach.strategy.economy.domain.NationEconomy;
import com.reach.strategy.economy.domain.NationEconomyRepository;
import com.reach.strategy.economy.rules.EconomyRules;
import com.reach.strategy.game.api.GameStartInitializer;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting economy of a new game from its ruleset (Q-W3): every nation's treasury, and the
 * starting workshops in each capital. Runs inside the start transaction.
 */
@Component
class EconomyStartInitializer implements GameStartInitializer {

	private final EconomyRulesets rulesets;
	private final WorldApi world;
	private final NationEconomyRepository economies;
	private final BuildingRepository buildings;
	private final Clock clock;

	EconomyStartInitializer(EconomyRulesets rulesets, WorldApi world, NationEconomyRepository economies,
			BuildingRepository buildings, Clock clock) {
		this.rulesets = rulesets;
		this.world = world;
		this.economies = economies;
		this.buildings = buildings;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void initialize(UUID gameId, UUID mapId, String rulesetVersion) {
		EconomyRules rules = rulesets.rules(rulesetVersion);
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		Map<UUID, String> capitalByNationTemplate = world.mapSummary(mapId)
			.nations()
			.stream()
			.collect(Collectors.toMap(WorldApi.NationOption::id, WorldApi.NationOption::capitalProvinceKey));
		Map<String, UUID> cityByProvince = snapshot.provinces()
			.stream()
			.collect(Collectors.toMap(WorldApi.ProvinceState::provinceKey, WorldApi.ProvinceState::cityId));

		snapshot.nations().stream().sorted((a, b) -> a.id().compareTo(b.id())).forEach(nation -> {
			economies.save(NationEconomy.opening(gameId, nation.id(), rules.startingTreasuryMg()));
			UUID capitalCity = cityByProvince.get(capitalByNationTemplate.get(nation.nationTemplateId()));
			for (int i = 0; i < rules.startingCapitalWorkshops(); i++) {
				buildings.save(Building.startingWorkshop(gameId, capitalCity, 1, clock.instant()));
			}
		});
	}

}
