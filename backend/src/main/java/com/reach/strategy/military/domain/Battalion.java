package com.reach.strategy.military.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import com.reach.strategy.military.rules.Movement;
import com.reach.strategy.military.rules.UnitType;

import org.springframework.data.domain.Persistable;

/**
 * The basic military unit. Location and movement eligibility belong to the battalion itself
 * (Q-M1), never to its army, so regrouping cannot reset them. A battalion with zero manpower is
 * destroyed: the row is deleted, and its dead never return to the civilian population.
 */
@Entity
@Table(name = "battalion")
public class Battalion implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "game_id", nullable = false, updatable = false)
	private UUID gameId;

	@Column(name = "nation_id", nullable = false, updatable = false)
	private UUID nationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "unit_type", nullable = false, updatable = false)
	private UnitType unitType;

	@Column(nullable = false)
	private int manpower;

	/** Reinforcement refills a battalion up to this (Phase 6). */
	@Column(name = "max_manpower", nullable = false, updatable = false)
	private int maxManpower;

	/** Where it was first raised; the per-origin breakdown of its soldiers is in {@link BattalionCohort}. */
	@Column(name = "origin_city_id", nullable = false, updatable = false)
	private UUID originCityId;

	@Column(name = "province_state_id", nullable = false)
	private UUID provinceStateId;

	@Column(name = "army_id")
	private UUID armyId;

	@Column(name = "equipment_level", nullable = false)
	private int equipmentLevel;

	@Column(name = "recruitment_cost_mg", nullable = false, updatable = false)
	private long recruitmentCostMg;

	@Column(name = "recruited_round", nullable = false, updatable = false)
	private int recruitedRound;

	@Column(name = "recruited_turn_number", nullable = false, updatable = false)
	private int recruitedTurnNumber;

	@Column(name = "last_moved_turn_number")
	private Integer lastMovedTurnNumber;

	@Column(name = "last_reinforced_turn_number")
	private Integer lastReinforcedTurnNumber;

	/** Replacements received during {@code lastReinforcedTurnNumber}; counts toward the per-turn limit. */
	@Column(name = "reinforced_this_turn", nullable = false)
	private int reinforcedThisTurn;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew = true;

	protected Battalion() {
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

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		isNew = false;
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

}
