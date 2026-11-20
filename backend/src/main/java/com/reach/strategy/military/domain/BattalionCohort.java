package com.reach.strategy.military.domain;

import java.util.UUID;

import com.reach.strategy.military.rules.Cohorts;

/** The soldiers of one battalion that came from one city (see {@link Cohorts}). */
public class BattalionCohort {

	private UUID id;
	private UUID gameId;
	private UUID battalionId;
	private UUID originCityId;
	private int soldiers;

	private BattalionCohort() {
	}

	/** A cohort as stored (see {@link BattalionCohortRepository}). */
	static BattalionCohort restore(UUID id, UUID gameId, UUID battalionId, UUID originCityId, int soldiers) {
		BattalionCohort cohort = new BattalionCohort();
		cohort.id = id;
		cohort.gameId = gameId;
		cohort.battalionId = battalionId;
		cohort.originCityId = originCityId;
		cohort.soldiers = soldiers;
		return cohort;
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

	public UUID battalionId() {
		return battalionId;
	}

	public UUID originCityId() {
		return originCityId;
	}

	public int soldiers() {
		return soldiers;
	}

	UUID id() {
		return id;
	}

	UUID gameId() {
		return gameId;
	}

}
