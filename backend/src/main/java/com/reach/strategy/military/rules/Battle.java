package com.reach.strategy.military.rules;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic field battle (PROVISIONAL model, docs/COMBAT_AND_SIEGE.md §2; coefficients from the
 * ruleset, Q-C1 not approved). Per phase, from start-of-phase manpower:
 * <pre>
 * strength S_t = manpower of type t / battalionManpower      (partial battalions count partially)
 * screen      = Σ S_t × screen_t;   P = 1 if no artillery, else min(1, screen / (screenPerArtillery × S_art))
 * artEff      = minEff + (1 − minEff) × P                     (unprotected guns fire less)
 * base        = Σ S_t × attack_t, artillery × artEff, cavalry × 0.6 in mountains
 * flank       = flankMax × min(1, max(0, C_own − C_enemy) / (flankEnemyShare × enemyTotal)), 0 in mountains
 * D           = base × (1 + flank) × terrain                   (terrain for the attacker only:
 *                                                              river ×0.80, mountains ×0.65 — APPROVED)
 * casualties  = D × lossPerDamage split by exposure (inf/cav 1, artillery protected 0.2 … unprotected 1.5),
 *               divided by the target type's defence, floored to whole soldiers, capped at its manpower
 * </pre>
 * Both sides fire simultaneously; up to {@code maxPhases} phases; a side whose cumulative losses
 * reach the break threshold breaks and loses. Otherwise the lower loss ratio wins, ties go to the
 * defender. All arithmetic is BigDecimal with DECIMAL64 (HALF_EVEN); men are floored.
 */
public final class Battle {

	private static final MathContext MC = MathContext.DECIMAL64;

	private Battle() {
	}

	public enum Side {

		ATTACKER, DEFENDER

	}

	/**
	 * A participating battalion as the battle sees it.
	 *
	 * @param modifiers its own nation's technology and doctrine (coalition battles, Phase 10), or null
	 * for the side's modifiers
	 */
	public record Fighter(UUID id, UnitType type, int manpower, SideModifiers modifiers) {

		public Fighter {
			if (manpower <= 0) {
				throw new IllegalArgumentException("A fighting battalion needs soldiers");
			}
		}

		public Fighter(UUID id, UnitType type, int manpower) {
			this(id, type, manpower, null);
		}

	}

	/**
	 * @param river the attackers cross a river into the province
	 * @param mountains the defenders' province is mountainous
	 */
	public record Input(List<Fighter> attackers, List<Fighter> defenders, boolean river, boolean mountains,
			SideModifiers attackerModifiers, SideModifiers defenderModifiers) {

		public Input {
			if (attackers.isEmpty() || defenders.isEmpty()) {
				throw new IllegalArgumentException("A battle needs two sides");
			}
			attackers = List.copyOf(attackers);
			defenders = List.copyOf(defenders);
			attackerModifiers = attackerModifiers == null ? SideModifiers.NONE : attackerModifiers;
			defenderModifiers = defenderModifiers == null ? SideModifiers.NONE : defenderModifiers;
		}

		/** A battle between nations without technology or doctrine (rulesets before v4). */
		public Input(List<Fighter> attackers, List<Fighter> defenders, boolean river, boolean mountains) {
			this(attackers, defenders, river, mountains, SideModifiers.NONE, SideModifiers.NONE);
		}

	}

	/**
	 * One nation's technology and doctrine as the battle uses them (COMBAT_AND_SIEGE §2.5:
	 * {@code D = base × (1 + flank) × terrainMod × techMod}). Values come from the server's research
	 * state, never from a client.
	 *
	 * @param damageBonusBps extra damage per unit type in basis points (military technology); the
	 * soldiers themselves are unchanged — a 600-man battalion stays 600 men
	 * @param riverPenaltyReductionBps share of the river attack penalty removed (doctrine)
	 * @param mountainPenaltyReductionBps share of the mountain attack penalty removed (doctrine)
	 */
	public record SideModifiers(Map<UnitType, Integer> damageBonusBps, int riverPenaltyReductionBps,
			int mountainPenaltyReductionBps) {

		public static final SideModifiers NONE = new SideModifiers(Map.of(), 0, 0);

		public SideModifiers {
			damageBonusBps = damageBonusBps == null ? Map.of() : Map.copyOf(damageBonusBps);
			if (riverPenaltyReductionBps < 0 || riverPenaltyReductionBps > 10_000 || mountainPenaltyReductionBps < 0
					|| mountainPenaltyReductionBps > 10_000
					|| damageBonusBps.values().stream().anyMatch(b -> b < 0 || b > 100_000)) {
				throw new IllegalArgumentException("Invalid battle modifiers");
			}
		}

		BigDecimal damageMultiplier(UnitType type) {
			return BigDecimal.ONE.add(BigDecimal.valueOf(damageBonusBps.getOrDefault(type, 0), 4), MC);
		}

		/** Doctrine: {@code 1 − (1 − base) × (1 − reduction)}, e.g. river 0.80 with 50% → 0.90. */
		public static BigDecimal reduced(BigDecimal baseMultiplier, int reductionBps) {
			BigDecimal penalty = BigDecimal.ONE.subtract(baseMultiplier, MC);
			BigDecimal kept = BigDecimal.ONE.subtract(BigDecimal.valueOf(reductionBps, 4), MC);
			return BigDecimal.ONE.subtract(penalty.multiply(kept, MC), MC);
		}

	}

	/** Damage dealt and losses suffered by both sides in one phase. */
	public record Phase(int number, SideLog attacker, SideLog defender) {
	}

	/**
	 * @param protection artillery protection P (1 without artillery)
	 * @param damage D dealt to the enemy
	 * @param lossesByType men lost this phase per unit type
	 */
	public record SideLog(BigDecimal protection, BigDecimal artilleryEffectiveness, BigDecimal flankBonus,
			BigDecimal terrainMultiplier, BigDecimal damage, Map<UnitType, Integer> lossesByType, int manpowerAfter) {
	}

	/**
	 * @param losses men lost per battalion over the whole battle (0 for unharmed battalions)
	 * @param broken the losing side if it broke, or null if the battle went the full distance
	 * @param attackerBroke the attacker reached the break threshold (both sides can break in one phase)
	 * @param defenderBroke the defender reached the break threshold
	 */
	public record Result(Side winner, Side broken, boolean attackerBroke, boolean defenderBroke, int attackerInitial,
			int defenderInitial, int attackerLosses, int defenderLosses, Map<UUID, Integer> losses, List<Phase> phases) {

		public int survivors(Fighter fighter) {
			return fighter.manpower() - losses.getOrDefault(fighter.id(), 0);
		}

	}

	public static Result resolve(Input input, MilitaryRules rules) {
		MilitaryRules.CombatRules combat = rules.combat();
		Map<UUID, Integer> remaining = new HashMap<>();
		input.attackers().forEach(f -> remaining.put(f.id(), f.manpower()));
		input.defenders().forEach(f -> remaining.put(f.id(), f.manpower()));
		List<Fighter> attackers = sorted(input.attackers());
		List<Fighter> defenders = sorted(input.defenders());
		int attackerInitial = total(attackers, remaining);
		int defenderInitial = total(defenders, remaining);

		List<Phase> phases = new ArrayList<>();
		Side broken = null;
		Side winner = null;
		boolean attackerBrokeAtEnd = false;
		boolean defenderBrokeAtEnd = false;
		for (int number = 1; number <= combat.maxPhases(); number++) {
			Map<UnitType, BigDecimal> a = strengths(attackers, remaining, rules);
			Map<UnitType, BigDecimal> d = strengths(defenders, remaining, rules);
			Firepower attackFire = firepower(attackers, remaining, a, d, true, input, rules);
			Firepower defenceFire = firepower(defenders, remaining, d, a, false, input, rules);
			// Simultaneous fire: both sides' damage is computed before any losses are applied.
			Map<UnitType, Integer> defenderLosses = casualties(attackFire.damage(), d, defenders, remaining, rules);
			Map<UnitType, Integer> attackerLosses = casualties(defenceFire.damage(), a, attackers, remaining, rules);
			apply(attackerLosses, attackers, remaining);
			apply(defenderLosses, defenders, remaining);
			phases.add(new Phase(number, attackFire.log(attackerLosses, total(attackers, remaining)),
					defenceFire.log(defenderLosses, total(defenders, remaining))));

			int attackerLost = attackerInitial - total(attackers, remaining);
			int defenderLost = defenderInitial - total(defenders, remaining);
			boolean attackerBroke = brokeAt(attackerLost, attackerInitial, combat);
			boolean defenderBroke = brokeAt(defenderLost, defenderInitial, combat);
			if (attackerBroke || defenderBroke) {
				attackerBrokeAtEnd = attackerBroke;
				defenderBrokeAtEnd = defenderBroke;
				if (attackerBroke && !defenderBroke) {
					broken = Side.ATTACKER;
					winner = Side.DEFENDER;
				}
				else if (defenderBroke && !attackerBroke) {
					broken = Side.DEFENDER;
					winner = Side.ATTACKER;
				}
				else {
					winner = lowerLossRatio(attackerLost, attackerInitial, defenderLost, defenderInitial);
					broken = winner == Side.ATTACKER ? Side.DEFENDER : Side.ATTACKER;
				}
				break;
			}
		}
		int attackerLost = attackerInitial - total(attackers, remaining);
		int defenderLost = defenderInitial - total(defenders, remaining);
		if (winner == null) {
			winner = lowerLossRatio(attackerLost, attackerInitial, defenderLost, defenderInitial);
		}
		Map<UUID, Integer> losses = new LinkedHashMap<>();
		for (Fighter f : attackers) {
			losses.put(f.id(), f.manpower() - remaining.get(f.id()));
		}
		for (Fighter f : defenders) {
			losses.put(f.id(), f.manpower() - remaining.get(f.id()));
		}
		return new Result(winner, broken, attackerBrokeAtEnd, defenderBrokeAtEnd, attackerInitial, defenderInitial,
				attackerLost, defenderLost, losses, List.copyOf(phases));
	}

	// --- damage -------------------------------------------------------------------------------

	private record Firepower(BigDecimal protection, BigDecimal artilleryEffectiveness, BigDecimal flank,
			BigDecimal terrain, BigDecimal damage) {

		SideLog log(Map<UnitType, Integer> lossesSuffered, int manpowerAfter) {
			return new SideLog(protection, artilleryEffectiveness, flank, terrain, damage, Map.copyOf(lossesSuffered),
					manpowerAfter);
		}

	}

	/**
	 * Damage a side deals. The screen, artillery protection and the cavalry flank are properties of
	 * the whole side; technology and doctrine belong to each battalion's nation, so a coalition side
	 * adds up each nation's contribution with its own modifiers (Phase 10). A side of one nation uses
	 * exactly the single-nation formula.
	 */
	private static Firepower firepower(List<Fighter> fighters, Map<UUID, Integer> remaining,
			Map<UnitType, BigDecimal> own, Map<UnitType, BigDecimal> enemy, boolean attacking, Input input,
			MilitaryRules rules) {
		MilitaryRules.CombatRules combat = rules.combat();
		BigDecimal protection = protection(own, rules);
		BigDecimal artEff = combat.artilleryMinEffectiveness()
			.add(BigDecimal.ONE.subtract(combat.artilleryMinEffectiveness()).multiply(protection, MC), MC);
		BigDecimal cavalryTerrain = input.mountains() ? combat.cavalryMountainFactor() : BigDecimal.ONE;
		SideModifiers sideMods = attacking ? input.attackerModifiers() : input.defenderModifiers();
		Map<SideModifiers, List<Fighter>> groups = new LinkedHashMap<>();
		for (Fighter f : fighters) {
			groups.computeIfAbsent(f.modifiers() == null ? sideMods : f.modifiers(), m -> new ArrayList<>()).add(f);
		}
		BigDecimal flank = flank(own, enemy, input, combat);
		if (groups.size() > 1) {
			BigDecimal weighted = BigDecimal.ZERO;
			BigDecimal baseTotal = BigDecimal.ZERO;
			for (var group : groups.entrySet()) {
				Map<UnitType, BigDecimal> strengths = strengths(group.getValue(), remaining, rules);
				BigDecimal groupBase = base(strengths, group.getKey(), artEff, cavalryTerrain, rules);
				baseTotal = baseTotal.add(groupBase, MC);
				weighted = weighted.add(groupBase.multiply(terrain(group.getKey(), attacking, input, combat), MC), MC);
			}
			BigDecimal damage = weighted.multiply(BigDecimal.ONE.add(flank, MC), MC);
			BigDecimal terrain = baseTotal.signum() == 0 ? BigDecimal.ONE : weighted.divide(baseTotal, MC);
			return new Firepower(protection, artEff, flank, terrain, damage);
		}
		SideModifiers mods = groups.isEmpty() ? sideMods : groups.keySet().iterator().next();
		BigDecimal base = base(own, mods, artEff, cavalryTerrain, rules);
		BigDecimal terrain = terrain(mods, attacking, input, combat);
		BigDecimal damage = base.multiply(BigDecimal.ONE.add(flank, MC), MC).multiply(terrain, MC);
		return new Firepower(protection, artEff, flank, terrain, damage);
	}

	/** techMod per unit type: the soldiers are unchanged, each one simply hits harder. */
	private static BigDecimal base(Map<UnitType, BigDecimal> own, SideModifiers mods, BigDecimal artEff,
			BigDecimal cavalryTerrain, MilitaryRules rules) {
		return own.get(UnitType.INFANTRY).multiply(rules.unit(UnitType.INFANTRY).attack(), MC)
			.multiply(mods.damageMultiplier(UnitType.INFANTRY), MC)
			.add(own.get(UnitType.CAVALRY).multiply(rules.unit(UnitType.CAVALRY).attack(), MC).multiply(cavalryTerrain, MC)
				.multiply(mods.damageMultiplier(UnitType.CAVALRY), MC), MC)
			.add(own.get(UnitType.ARTILLERY).multiply(rules.unit(UnitType.ARTILLERY).attack(), MC).multiply(artEff, MC)
				.multiply(mods.damageMultiplier(UnitType.ARTILLERY), MC), MC);
	}

	/** Cavalry flank of the whole side (none in mountains). */
	private static BigDecimal flank(Map<UnitType, BigDecimal> own, Map<UnitType, BigDecimal> enemy, Input input,
			MilitaryRules.CombatRules combat) {
		BigDecimal enemyTotal = sum(enemy);
		if (input.mountains() || enemyTotal.signum() == 0) {
			return BigDecimal.ZERO;
		}
		BigDecimal excess = own.get(UnitType.CAVALRY).subtract(enemy.get(UnitType.CAVALRY), MC).max(BigDecimal.ZERO);
		BigDecimal ratio = excess.divide(enemyTotal.multiply(combat.flankEnemyShare(), MC), MC).min(BigDecimal.ONE);
		return combat.flankMaxBonus().multiply(ratio, MC);
	}

	/** Terrain multiplier of an attacking nation (river, mountains; reduced by its doctrines). */
	private static BigDecimal terrain(SideModifiers mods, boolean attacking, Input input,
			MilitaryRules.CombatRules combat) {
		BigDecimal terrain = BigDecimal.ONE;
		if (attacking && input.river()) {
			terrain = terrain.multiply(SideModifiers.reduced(combat.riverAttackMultiplier(), mods.riverPenaltyReductionBps()),
					MC);
		}
		if (attacking && input.mountains()) {
			terrain = terrain.multiply(
					SideModifiers.reduced(combat.mountainAttackMultiplier(), mods.mountainPenaltyReductionBps()), MC);
		}
		return terrain;
	}

	/** P: how well the infantry/cavalry screen covers the artillery (1 = fully protected). */
	private static BigDecimal protection(Map<UnitType, BigDecimal> side, MilitaryRules rules) {
		BigDecimal artillery = side.get(UnitType.ARTILLERY);
		if (artillery.signum() == 0) {
			return BigDecimal.ONE;
		}
		BigDecimal screen = BigDecimal.ZERO;
		for (UnitType type : UnitType.values()) {
			screen = screen.add(side.get(type).multiply(rules.unit(type).screen(), MC), MC);
		}
		return screen.divide(rules.combat().screenPerArtillery().multiply(artillery, MC), MC).min(BigDecimal.ONE);
	}

	// --- casualties ---------------------------------------------------------------------------

	private static Map<UnitType, Integer> casualties(BigDecimal damage, Map<UnitType, BigDecimal> target,
			List<Fighter> fighters, Map<UUID, Integer> remaining, MilitaryRules rules) {
		MilitaryRules.CombatRules combat = rules.combat();
		BigDecimal protection = protection(target, rules);
		Map<UnitType, BigDecimal> exposure = new EnumMap<>(UnitType.class);
		exposure.put(UnitType.INFANTRY, target.get(UnitType.INFANTRY));
		exposure.put(UnitType.CAVALRY, target.get(UnitType.CAVALRY));
		exposure.put(UnitType.ARTILLERY, target.get(UnitType.ARTILLERY)
			.multiply(combat.artilleryProtectedExposure()
				.add(combat.artilleryUnprotectedExtraExposure().multiply(BigDecimal.ONE.subtract(protection), MC), MC), MC));
		BigDecimal totalExposure = sum(exposure);
		Map<UnitType, Integer> losses = new EnumMap<>(UnitType.class);
		if (totalExposure.signum() == 0) {
			return losses;
		}
		BigDecimal points = damage.multiply(combat.lossPerDamage(), MC);
		for (UnitType type : UnitType.values()) {
			int manpower = fighters.stream().filter(f -> f.type() == type).mapToInt(f -> remaining.get(f.id())).sum();
			if (manpower == 0) {
				continue;
			}
			int lost = points.multiply(exposure.get(type), MC)
				.divide(totalExposure, MC)
				.divide(rules.unit(type).defence(), MC)
				.setScale(0, RoundingMode.FLOOR)
				.intValueExact();
			losses.put(type, Math.min(lost, manpower));
		}
		return losses;
	}

	/**
	 * Splits each type's losses over its battalions in proportion to their manpower: floors first,
	 * then the remaining soldiers go to the largest fractional remainders, ties by battalion id.
	 */
	private static void apply(Map<UnitType, Integer> losses, List<Fighter> fighters, Map<UUID, Integer> remaining) {
		for (var entry : losses.entrySet()) {
			List<Fighter> ofType = fighters.stream()
				.filter(f -> f.type() == entry.getKey() && remaining.get(f.id()) > 0)
				.toList();
			long typeManpower = ofType.stream().mapToLong(f -> remaining.get(f.id())).sum();
			long toDistribute = entry.getValue();
			if (typeManpower == 0 || toDistribute == 0) {
				continue;
			}
			Map<UUID, Long> share = new HashMap<>();
			Map<UUID, Long> remainder = new HashMap<>();
			long assigned = 0;
			for (Fighter f : ofType) {
				long numerator = toDistribute * remaining.get(f.id());
				share.put(f.id(), numerator / typeManpower);
				remainder.put(f.id(), numerator % typeManpower);
				assigned += numerator / typeManpower;
			}
			List<Fighter> byRemainder = new ArrayList<>(ofType);
			byRemainder.sort(Comparator.<Fighter>comparingLong(f -> remainder.get(f.id()))
				.reversed()
				.thenComparing(f -> f.id().toString()));
			for (int i = 0; i < toDistribute - assigned; i++) {
				UUID id = byRemainder.get(i).id();
				share.merge(id, 1L, Long::sum);
			}
			for (Fighter f : ofType) {
				remaining.put(f.id(), (int) (remaining.get(f.id()) - share.get(f.id())));
			}
		}
	}

	// --- helpers ------------------------------------------------------------------------------

	private static Map<UnitType, BigDecimal> strengths(List<Fighter> fighters, Map<UUID, Integer> remaining,
			MilitaryRules rules) {
		Map<UnitType, BigDecimal> strengths = new EnumMap<>(UnitType.class);
		for (UnitType type : UnitType.values()) {
			int manpower = fighters.stream().filter(f -> f.type() == type).mapToInt(f -> remaining.get(f.id())).sum();
			strengths.put(type, BigDecimal.valueOf(manpower).divide(BigDecimal.valueOf(rules.battalionManpower()), MC));
		}
		return strengths;
	}

	private static BigDecimal sum(Map<UnitType, BigDecimal> values) {
		BigDecimal sum = BigDecimal.ZERO;
		for (BigDecimal value : values.values()) {
			sum = sum.add(value, MC);
		}
		return sum;
	}

	private static int total(List<Fighter> fighters, Map<UUID, Integer> remaining) {
		return fighters.stream().mapToInt(f -> remaining.get(f.id())).sum();
	}

	private static List<Fighter> sorted(List<Fighter> fighters) {
		return fighters.stream().sorted(Comparator.comparing(f -> f.id().toString())).toList();
	}

	private static boolean brokeAt(int lost, int initial, MilitaryRules.CombatRules combat) {
		return (long) lost * 10_000 >= (long) initial * combat.breakThresholdBps();
	}

	/** Compared exactly by cross-multiplication; a tie favours the defender. */
	private static Side lowerLossRatio(int attackerLost, int attackerInitial, int defenderLost, int defenderInitial) {
		return (long) attackerLost * defenderInitial < (long) defenderLost * attackerInitial ? Side.ATTACKER : Side.DEFENDER;
	}

}
