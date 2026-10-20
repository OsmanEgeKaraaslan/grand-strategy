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
		Map<UUID, WorldApi.NationOption> templates = world.mapSummary(mapId)
			.nations()
			.stream()
			.collect(Collectors.toMap(WorldApi.NationOption::id, n -> n));
		Map<String, UUID> cityByProvince = snapshot.provinces()
			.stream()
			.collect(Collectors.toMap(WorldApi.ProvinceState::provinceKey, WorldApi.ProvinceState::cityId));

		snapshot.nations().stream().sorted((a, b) -> a.id().compareTo(b.id())).forEach(nation -> {
			// Ruleset v6: values per faction tier (major realms start richer, small states poorer).
			WorldApi.NationOption template = templates.get(nation.nationTemplateId());
			economies.save(NationEconomy.opening(gameId, nation.id(), rules.startingTreasuryMg(template.tier())));
			UUID capitalCity = cityByProvince.get(template.capitalProvinceKey());
			for (int i = 0; i < rules.startingCapitalWorkshops(template.tier()); i++) {
				buildings.save(Building.startingWorkshop(gameId, capitalCity, 1, clock.instant()));
			}
		});
	}

}
