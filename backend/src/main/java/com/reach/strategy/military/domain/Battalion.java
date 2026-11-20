package com.reach.strategy.military.domain;

import java.time.Instant;
import java.util.UUID;

import com.reach.strategy.military.rules.Movement;
import com.reach.strategy.military.rules.UnitType;

/**
 * The basic military unit. Location and movement eligibility belong to the battalion itself
 * (Q-M1), never to its army, so regrouping cannot reset them. A battalion with zero manpower is
 * destroyed: the row is deleted, and its dead never return to the civilian population.
 */
public class Battalion {

	private UUID id;
	private UUID gameId;
	private UUID nationId;
	private UnitType unitType;
	private int manpower;

	/** Reinforcement refills a battalion up to this (Phase 6). */
	private int maxManpower;

	/** Where it was first raised; the per-origin breakdown of its soldiers is in {@link BattalionCohort}. */
	private UUID originCityId;
	private UUID provinceStateId;
	private UUID armyId;
	private int equipmentLevel;
	private long recruitmentCostMg;
	private int recruitedRound;
	private int recruitedTurnNumber;
	private Integer lastMovedTurnNumber;
	private Integer lastReinforcedTurnNumber;
	private Integer lastMovedRound;
	private Integer lastReinforcedRound;

	/** An ally commanding this battalion (attached forces, Phase 10), or null for its owner. */
	private UUID commanderNationId;

	/** Replacements received during {@code lastReinforcedTurnNumber}; counts toward the per-turn limit. */
	private int reinforcedThisTurn;
	private Instant createdAt;

	private Battalion() {
	}

	/** A battalion as stored (see {@link BattalionRepository}). */
	static Battalion restore(UUID id, UUID gameId, UUID nationId, UnitType unitType, int manpower, int maxManpower,
			UUID originCityId, UUID provinceStateId, UUID armyId, int equipmentLevel, long recruitmentCostMg,
			int recruitedRound, int recruitedTurnNumber, Integer lastMovedTurnNumber, Integer lastReinforcedTurnNumber,
			Integer lastMovedRound, Integer lastReinforcedRound, UUID commanderNationId, int reinforcedThisTurn,
			Instant createdAt) {
		Battalion b = new Battalion();
		b.id = id;
		b.gameId = gameId;
		b.nationId = nationId;
		b.unitType = unitType;
		b.manpower = manpower;
		b.maxManpower = maxManpower;
		b.originCityId = originCityId;
		b.provinceStateId = provinceStateId;
		b.armyId = armyId;
		b.equipmentLevel = equipmentLevel;
		b.recruitmentCostMg = recruitmentCostMg;
		b.recruitedRound = recruitedRound;
		b.recruitedTurnNumber = recruitedTurnNumber;
		b.lastMovedTurnNumber = lastMovedTurnNumber;
		b.lastReinforcedTurnNumber = lastReinforcedTurnNumber;
		b.lastMovedRound = lastMovedRound;
		b.lastReinforcedRound = lastReinforcedRound;
		b.commanderNationId = commanderNationId;
		b.reinforcedThisTurn = reinforcedThisTurn;
		b.createdAt = createdAt;
		return b;
	}

	/**
	 * A new full-strength battalion in its origin city's province.
	 *
	 * @param recruitedTurnNumber the turn it may not move in (0 for starting forces: they are ready)
	 */
	public static Battalion recruit(UUID gameId, UUID nationId, UnitType type, int manpower, UUID originCityId,
			UUID provinceStateId, long costMg, int round, int recruitedTurnNumber, Instant now) {
		Battalion battalion = new Battalion();
		battalion.id = UUID.randomUUID();
		battalion.gameId = gameId;
		battalion.nationId = nationId;
		battalion.unitType = type;
		battalion.manpower = manpower;
		battalion.maxManpower = manpower;
		battalion.originCityId = originCityId;
		battalion.provinceStateId = provinceStateId;
		battalion.recruitmentCostMg = costMg;
		battalion.recruitedRound = round;
		battalion.recruitedTurnNumber = recruitedTurnNumber;
		battalion.createdAt = now;
		return battalion;
	}

	public Movement.Eligibility eligibility(Movement.Clock clock) {
		return Movement.eligibility(recruitedTurnNumber, recruitedRound, lastMovedTurnNumber, lastMovedRound,
				lastReinforcedTurnNumber, lastReinforcedRound, clock);
	}

	/** Spends this round's (or turn's) movement; the province stays the same if the attack failed. */
	public void moved(UUID provinceStateId, Movement.Clock clock) {
		this.provinceStateId = provinceStateId;
		this.lastMovedTurnNumber = clock.turnNumber();
		if (clock.roundBased()) {
			this.lastMovedRound = clock.round();
		}
	}

	/** Who gives this battalion orders: its owner, or the ally it is attached to. */
	public UUID commander() {
		return commanderNationId == null ? nationId : commanderNationId;
	}

	public UUID commanderNationId() {
		return commanderNationId;
	}

	/** Places the battalion under an ally's command; it leaves its (national) army. Movement is unchanged. */
	public void attachTo(UUID commander) {
		this.commanderNationId = commander;
		this.armyId = null;
	}

	public void detach() {
		this.commanderNationId = null;
	}

	public Integer lastMovedRound() {
		return lastMovedRound;
	}

	public Movement.Eligibility eligibility(int currentTurnNumber) {
		return Movement.eligibility(recruitedTurnNumber, lastMovedTurnNumber, lastReinforcedTurnNumber,
				currentTurnNumber);
	}

	/** Spends this turn's movement; {@code provinceStateId} stays the same if the attack failed. */
	public void moved(UUID provinceStateId, int turnNumber) {
		this.provinceStateId = provinceStateId;
		this.lastMovedTurnNumber = turnNumber;
	}

	/** Retreat after a lost defence: relocates without spending the battalion's own movement. */
	public void retreatTo(UUID provinceStateId) {
		this.provinceStateId = provinceStateId;
		this.armyId = null;
	}

	/**
	 * Withdrawal after losing military access (peace, end of an alliance): relocates without spending
	 * movement and keeps the army, whose battalions all withdraw to the same province.
	 */
	public void withdrawTo(UUID provinceStateId) {
		this.provinceStateId = provinceStateId;
	}

	/** @return whether soldiers remain (false: the caller deletes the battalion) */
	public boolean suffer(int losses) {
		if (losses < 0 || losses > manpower) {
			throw new IllegalArgumentException("Invalid losses " + losses + " for " + manpower + " soldiers");
		}
		manpower -= losses;
		return manpower > 0;
	}

	/** Soldiers this battalion has already received in the given player turn. */
	public int reinforcedInTurn(int turnNumber) {
		return lastReinforcedTurnNumber != null && lastReinforcedTurnNumber == turnNumber ? reinforcedThisTurn : 0;
	}

	/**
	 * Adds replacements (already validated and paid). Spends the battalion's movement and attack for
	 * this turn (PROVISIONAL tactical rule); it still defends.
	 */
	public void reinforce(int soldiers, Movement.Clock clock) {
		reinforce(soldiers, clock.turnNumber());
		if (clock.roundBased()) {
			lastReinforcedRound = clock.round();
		}
	}

	public void reinforce(int soldiers, int turnNumber) {
		if (soldiers <= 0 || manpower + soldiers > maxManpower) {
			throw new IllegalArgumentException("Cannot add " + soldiers + " to " + manpower + "/" + maxManpower);
		}
		reinforcedThisTurn = reinforcedInTurn(turnNumber) + soldiers;
		lastReinforcedTurnNumber = turnNumber;
		manpower += soldiers;
	}

	public void assignArmy(UUID armyId) {
		this.armyId = armyId;
	}

	public UUID id() {
		return id;
	}

	public UUID gameId() {
		return gameId;
	}

	public UUID nationId() {
		return nationId;
	}

	public UnitType unitType() {
		return unitType;
	}

	public int manpower() {
		return manpower;
	}

	public int maxManpower() {
		return maxManpower;
	}

	public UUID originCityId() {
		return originCityId;
	}

	public UUID provinceStateId() {
		return provinceStateId;
	}

	public UUID armyId() {
		return armyId;
	}

	public int equipmentLevel() {
		return equipmentLevel;
	}

	public long recruitmentCostMg() {
		return recruitmentCostMg;
	}

	public int recruitedRound() {
		return recruitedRound;
	}

	public int recruitedTurnNumber() {
		return recruitedTurnNumber;
	}

	public Integer lastMovedTurnNumber() {
		return lastMovedTurnNumber;
	}

	Integer lastReinforcedTurnNumber() {
		return lastReinforcedTurnNumber;
	}

	Integer lastReinforcedRound() {
		return lastReinforcedRound;
	}

	int reinforcedThisTurn() {
		return reinforcedThisTurn;
	}

	Instant createdAt() {
		return createdAt;
	}

}
