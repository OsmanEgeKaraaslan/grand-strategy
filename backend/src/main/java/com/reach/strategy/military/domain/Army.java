package com.reach.strategy.military.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * An organisational label for co-located battalions of one nation (PROVISIONAL Q-M6). It owns
 * neither location nor movement: those stay on each battalion.
 */
@Entity
@Table(name = "army")
public class Army implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "game_id", nullable = false, updatable = false)
	private UUID gameId;

	@Column(name = "nation_id", nullable = false, updatable = false)
	private UUID nationId;

	@Column(nullable = false)
	private String name;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew = true;

	protected Army() {
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

	public String name() {
		return name;
	}

}
