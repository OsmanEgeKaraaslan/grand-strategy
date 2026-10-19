package com.reach.strategy.research.rules;

/** Research institutions (GAME_DESIGN §10) and the national pool each one feeds. */
public enum InstitutionType {

	MILITARY_ACADEMY(Pool.MILITARY),

	UNIVERSITY(Pool.CIVILIAN),

	/** Doctrine research — not related to recruiting battalions. */
	BARRACKS(Pool.DOCTRINE);

	private final Pool pool;

	InstitutionType(Pool pool) {
		this.pool = pool;
	}

	public Pool pool() {
		return pool;
	}

	public enum Pool {

		MILITARY, CIVILIAN, DOCTRINE

	}

}
