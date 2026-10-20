package com.reach.strategy.military.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.military.api.MilitaryModifiers;

/** Read models and command results of the military module (all amounts in milligold). */
public final class MilitaryViews {

	private MilitaryViews() {
	}

	/**
	 * Everything the game screen needs: all battalions on the map (no fog of war yet), the caller's
	 * upkeep and recruitment options, and recent battles.
	 */
	public record Overview(UUID gameId, int round, int turnNumber, boolean yourTurn, UUID nationId, Rules rules,
			List<BattalionView> battalions, List<ArmyView> armies, UpkeepView upkeep, List<CityRecruitment> recruitment,
			List<BattleSummary> recentBattles, Modifiers modifiers) {
	}

	/**
	 * The caller's technology and doctrine as the battle and upkeep rules apply them now.
	 *
	 * @param riverAttackMultiplier the caller's damage when attacking across a river (base 0.80)
	 * @param mountainAttackMultiplier idem into mountains (base 0.65)
	 */
	public record Modifiers(int militaryLevel, List<String> doctrines, Map<String, Integer> damageBonusBps,
			java.math.BigDecimal baseRiverAttackMultiplier, java.math.BigDecimal riverAttackMultiplier,
			java.math.BigDecimal baseMountainAttackMultiplier, java.math.BigDecimal mountainAttackMultiplier,
			int upkeepReductionBps, int siegeArtilleryBonusBps) {
	}

	public record Rules(String version, int battalionManpower, int minResidualPopulation, List<UnitOption> units) {
	}

	public record UnitOption(String unitType, long recruitCostMg, long upkeepMg) {
	}

	/**
	 * @param eligibility READY, RECRUITED_THIS_TURN or ALREADY_MOVED for the caller's battalions;
	 * null for other nations'
	 * @param upkeepMg this battalion's upkeep at the next round end
	 */
	/**
	 * @param eligibility movement eligibility, shown only to the nation commanding the battalion
	 * @param commanderNationId the ally it is attached to (Phase 10), or null when its owner commands it
	 */
	public record BattalionView(UUID id, UUID nationId, String unitType, int manpower, int maxManpower,
			String provinceKey, UUID armyId, UUID originCityId, int recruitedRound, String eligibility, long upkeepMg,
			UUID commanderNationId) {
	}

	public record ArmyView(UUID id, UUID nationId, String name, String provinceKey, List<UUID> battalionIds) {
	}

	public record UpkeepView(long totalMg, Map<String, Long> byUnitTypeMg, int battalions, int soldiers) {
	}

	/** Recruitment options of one city the caller owns. */
	public record CityRecruitment(UUID cityId, String provinceKey, String cityName, int population,
			List<RecruitOption> options) {
	}

	/** @param blockers reasons it is not allowed now (Recruitment.Blocker names); empty if allowed */
	public record RecruitOption(String unitType, long costMg, long upkeepMg, int populationCost,
			int populationAfter, boolean allowed, List<String> blockers) {
	}

	/** One province's forces and where the caller's battalions there could move. */
	public record ProvinceMilitary(String provinceKey, UUID ownerNationId, UUID controllerNationId, boolean occupied,
			List<BattalionView> battalions, List<MoveTarget> moveTargets) {
	}

	/**
	 * @param hostile the destination holds battalions of a nation at war with the caller: moving there attacks
	 * @param accessible whether the caller may enter at all (own territory, an enemy at war, or military access)
	 */
	public record MoveTarget(String provinceKey, String terrain, boolean river, UUID controllerNationId,
			boolean hostile, int hostileBattalions, boolean accessible) {
	}

	/** The battalions after an attach or detach command. */
	public record AttachmentResult(List<BattalionView> battalions) {
	}

	public record RecruitResult(BattalionView battalion, long treasuryMg, int cityPopulation) {
	}

	/**
	 * @param outcome MOVED (no battle), ATTACK_WON (entered the province) or ATTACK_REPELLED (stayed)
	 * @param battle the battle fought, if any
	 */
	public record MoveResult(String outcome, String toProvinceKey, List<BattalionView> battalions,
			BattleView battle) {
	}

	public record ArmyResult(ArmyView army) {
	}

	/** One of the caller's battalions in detail: where its soldiers come from, and what it can do now. */
	public record BattalionDetails(BattalionView battalion, List<CohortView> cohorts, ReinforcementView reinforcement,
			DisbandView disband) {
	}

	/** Soldiers of one origin city in a battalion. */
	public record CohortView(UUID originCityId, String cityName, String provinceKey, int soldiers) {
	}

	/**
	 * Server quote for reinforcing from the city of the battalion's current province.
	 *
	 * @param turnAllowance replacements still allowed this player turn (500 minus already received)
	 * @param maxSoldiers the most that can be added right now (0 when blocked)
	 * @param recruitCostMg price of a full battalion; replacements cost it pro rata, rounded up
	 */
	public record ReinforcementView(UUID cityId, String cityName, int cityPopulation, int missing, int turnAllowance,
			int maxSoldiers, long recruitCostMg, int battalionManpower, long costOfMaxMg, long upkeepAfterMaxMg,
			boolean allowed, List<String> blockers) {
	}

	/** @param fallback the origin city is no longer controlled by the nation: soldiers go to the local city */
	public record ReturnView(UUID cityId, String cityName, String provinceKey, int soldiers, boolean fallback) {
	}

	public record DisbandView(boolean allowed, List<String> blockers, int soldiers, List<ReturnView> returns,
			long upkeepSavedMg) {
	}

	public record ReinforceResult(BattalionView battalion, int soldiersAdded, long costMg, long treasuryMg, UUID cityId,
			int cityPopulation) {
	}

	public record DisbandResult(UUID battalionId, String unitType, int soldiersReturned, List<ReturnView> returns,
			long upkeepSavedMg) {
	}

	public record BattleSummary(UUID id, int round, int turnNumber, String provinceKey, UUID attackerNationId,
			UUID defenderNationId, String winner, int attackerLosses, int defenderLosses, Instant createdAt) {
	}

	public record BattleView(UUID id, int round, int turnNumber, String provinceKey, UUID attackerNationId,
			UUID defenderNationId, String winner, String rulesetVersion, Instant createdAt, BattleDetails details) {
	}

	/**
	 * The stored battle document: inputs, every phase and the outcome — enough to explain or re-run it.
	 *
	 * @param broken ATTACKER, DEFENDER or null when nobody broke
	 * @param retreatProvinceKey where defeated defenders went, null if they held or were destroyed
	 */
	public record BattleDetails(String fromProvinceKey, String provinceKey, boolean river, boolean mountains,
			List<Participant> attackers, List<Participant> defenders, List<PhaseView> phases, int attackerInitial,
			int defenderInitial, int attackerLosses, int defenderLosses, String broken, String winner,
			String retreatProvinceKey, boolean defendersDestroyed, Boolean attackerBroke, Boolean defenderBroke,
			MilitaryModifiers.NationModifiers attackerModifiers,
			MilitaryModifiers.NationModifiers defenderModifiers, List<NationSide> nations) {

		public BattleDetails(String fromProvinceKey, String provinceKey, boolean river, boolean mountains,
				List<Participant> attackers, List<Participant> defenders, List<PhaseView> phases, int attackerInitial,
				int defenderInitial, int attackerLosses, int defenderLosses, String broken, String winner,
				String retreatProvinceKey, boolean defendersDestroyed, Boolean attackerBroke, Boolean defenderBroke,
				MilitaryModifiers.NationModifiers attackerModifiers, MilitaryModifiers.NationModifiers defenderModifiers) {
			this(fromProvinceKey, provinceKey, river, mountains, attackers, defenders, phases, attackerInitial,
					defenderInitial, attackerLosses, defenderLosses, broken, winner, retreatProvinceKey, defendersDestroyed,
					attackerBroke, defenderBroke, attackerModifiers, defenderModifiers, null);
		}

	}

	/**
	 * One nation's part in a battle (Phase 10: several nations may fight on a side).
	 *
	 * @param side ATTACKER or DEFENDER
	 * @param retreatProvinceKey where its defeated battalions retreated, or null
	 * @param modifiers its technology and doctrine as applied to its battalions
	 */
	public record NationSide(UUID nationId, String side, int manpowerBefore, int losses, String retreatProvinceKey,
			boolean destroyed, MilitaryModifiers.NationModifiers modifiers) {
	}

	/** @param fate SURVIVED, RETREATED or DESTROYED */
	public record Participant(UUID battalionId, String unitType, int manpowerBefore, int losses, int manpowerAfter,
			String fate, UUID nationId) {

		public Participant(UUID battalionId, String unitType, int manpowerBefore, int losses, int manpowerAfter,
				String fate) {
			this(battalionId, unitType, manpowerBefore, losses, manpowerAfter, fate, null);
		}

	}

	public record PhaseView(int number, SideView attacker, SideView defender) {
	}

	/** Numbers of one side in one phase; damage is what it dealt, losses what it suffered. */
	public record SideView(BigDecimal protection, BigDecimal artilleryEffectiveness, BigDecimal flankBonus,
			BigDecimal terrainMultiplier, BigDecimal damage, Map<String, Integer> losses, int manpowerAfter) {
	}

}
