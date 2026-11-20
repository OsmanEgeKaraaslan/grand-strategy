package com.reach.strategy.military.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An organisational label for co-located battalions of one nation (PROVISIONAL Q-M6). It owns
 * neither location nor movement: those stay on each battalion.
 */
public class Army {

	private UUID id;
	private UUID gameId;
	private UUID nationId;
	private String name;
	private Instant createdAt;

	private Army() {
	}

	/** An army as stored (see {@link ArmyRepository}). */
	static Army restore(UUID id, UUID gameId, UUID nationId, String name, Instant createdAt) {
		Army army = new Army();
		army.id = id;
		army.gameId = gameId;
		army.nationId = nationId;
		army.name = name;
		army.createdAt = createdAt;
		return army;
	}

	public static Army create(UUID gameId, UUID nationId, String name, Instant now) {
		Army army = new Army();
		army.id = UUID.randomUUID();
		army.gameId = gameId;
		army.nationId = nationId;
		army.name = name;
		army.createdAt = now;
		return army;
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

	public String name() {
		return name;
	}

	Instant createdAt() {
		return createdAt;
	}

}
