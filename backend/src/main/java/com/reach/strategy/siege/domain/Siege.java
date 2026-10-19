package com.reach.strategy.siege.domain;

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

import org.springframework.data.domain.Persistable;

/**
 * One siege of one city (COMBAT_AND_SIEGE §5.5). Participants are not stored: they are the
 * besieger's battalions in the province at each round end.
 */
@Entity
@Table(name = "siege")
public class Siege implements Persistable<UUID> {

	public enum Status {

		ACTIVE, SUCCEEDED, LIFTED

	}

	public enum EndReason {

		CAPTURED,

		/** No besieging battalion left: withdrawn, destroyed, or beaten by a relief army. */
		BESIEGERS_GONE

	}

	@Id
	private UUID id;

	@Column(name = "game_id", nullable = false, updatable = false)
	private UUID gameId;

	@Column(name = "province_state_id", nullable = false, updatable = false)
	private UUID provinceStateId;

	@Column(name = "city_id", nullable = false, updatable = false)
	private UUID cityId;

	@Column(name = "besieger_nation_id", nullable = false, updatable = false)
	private UUID besiegerNationId;

	@Column(name = "defender_nation_id", nullable = false, updatable = false)
	private UUID defenderNationId;

	@Column(name = "fortress_level", nullable = false, updatable = false)
	private int fortressLevel;

	@Column(name = "required_progress", nullable = false, updatable = false)
	private int requiredProgress;

	@Column(nullable = false)
	private int progress;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(name = "started_round", nullable = false, updatable = false)
	private int startedRound;

	@Column(name = "started_turn_number", nullable = false, updatable = false)
	private int startedTurnNumber;

	@Column(name = "last_processed_round")
	private Integer lastProcessedRound;

	@Column(name = "ended_round")
	private Integer endedRound;

	@Enumerated(EnumType.STRING)
	@Column(name = "end_reason")
	private EndReason endReason;

	@Column(name = "artillery_siege")
	private Boolean artillerySiege;

	@Column(name = "damaged_building_id")
	private UUID damagedBuildingId;

	@Column(name = "population_lost", nullable = false)
	private int populationLost;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew = true;

	protected Siege() {
	}

	public static Siege start(UUID gameId, UUID provinceStateId, UUID cityId, UUID besieger, UUID defender,
			int fortressLevel, int requiredProgress, int round, int turnNumber, Instant now) {
		Siege s = new Siege();
		s.id = UUID.randomUUID();
		s.gameId = gameId;
		s.provinceStateId = provinceStateId;
		s.cityId = cityId;
		s.besiegerNationId = besieger;
		s.defenderNationId = defender;
		s.fortressLevel = fortressLevel;
		s.requiredProgress = requiredProgress;
		s.status = Status.ACTIVE;
		s.startedRound = round;
		s.startedTurnNumber = turnNumber;
		s.createdAt = now;
		return s;
	}

	/** Whether this round end may still advance the siege (at most once per round). */
	public boolean processableIn(int round) {
		return status == Status.ACTIVE && (lastProcessedRound == null || lastProcessedRound < round);
	}

	/** @return whether the siege has now reached its requirement */
	public boolean advance(int round, int progressAdded, int civiliansLost) {
		if (!processableIn(round)) {
			throw new IllegalStateException("Siege " + id + " cannot advance again in round " + round);
		}
		progress = Math.addExact(progress, progressAdded);
		populationLost = Math.addExact(populationLost, civiliansLost);
		lastProcessedRound = round;
		return progress >= requiredProgress;
	}

	public void succeed(int round, boolean withArtillery, UUID damagedBuilding) {
		end(round, EndReason.CAPTURED, Status.SUCCEEDED);
		artillerySiege = withArtillery;
		damagedBuildingId = damagedBuilding;
	}

	/** Progress is lost (Q-S8, PROPOSED); civilians already dead stay dead. */
	public void lift(int round) {
		end(round, EndReason.BESIEGERS_GONE, Status.LIFTED);
	}

	private void end(int round, EndReason reason, Status newStatus) {
		if (status != Status.ACTIVE) {
			throw new IllegalStateException("Siege " + id + " has already ended");
		}
		status = newStatus;
		endedRound = round;
		endReason = reason;
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

	public UUID provinceStateId() {
		return provinceStateId;
	}

	public UUID cityId() {
		return cityId;
	}

	public UUID besiegerNationId() {
		return besiegerNationId;
	}

	public UUID defenderNationId() {
		return defenderNationId;
	}

	public int fortressLevel() {
		return fortressLevel;
	}

	public int requiredProgress() {
		return requiredProgress;
	}

	public int progress() {
		return progress;
	}

	public Status status() {
		return status;
	}

	public int startedRound() {
		return startedRound;
	}

	public Integer lastProcessedRound() {
		return lastProcessedRound;
	}

	public Integer endedRound() {
		return endedRound;
	}

	public EndReason endReason() {
		return endReason;
	}

	public Boolean artillerySiege() {
		return artillerySiege;
	}

	public UUID damagedBuildingId() {
		return damagedBuildingId;
	}

	public int populationLost() {
		return populationLost;
	}

}
