package com.reach.strategy.military.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import com.reach.strategy.military.rules.Cohorts;

import org.springframework.data.domain.Persistable;

/** The soldiers of one battalion that came from one city (see {@link Cohorts}). */
@Entity
@Table(name = "battalion_cohort")
public class BattalionCohort implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "game_id", nullable = false, updatable = false)
	private UUID gameId;

	@Column(name = "battalion_id", nullable = false, updatable = false)
	private UUID battalionId;

	@Column(name = "origin_city_id", nullable = false, updatable = false)
	private UUID originCityId;

	@Column(nullable = false)
	private int soldiers;

	@Transient
	private boolean isNew = true;

	protected BattalionCohort() {
	}

	public static BattalionCohort of(UUID gameId, UUID battalionId, UUID originCityId, int soldiers) {
		BattalionCohort cohort = new BattalionCohort();
		cohort.id = UUID.randomUUID();
		cohort.gameId = gameId;
		cohort.battalionId = battalionId;
		cohort.originCityId = originCityId;
		cohort.soldiers = soldiers;
		return cohort;
	}

	public Cohorts.Cohort asCohort() {
		return new Cohorts.Cohort(originCityId, soldiers);
	}

	void setSoldiers(int soldiers) {
		this.soldiers = soldiers;
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

	public UUID battalionId() {
		return battalionId;
	}

	public UUID originCityId() {
		return originCityId;
	}

	public int soldiers() {
		return soldiers;
	}

}
