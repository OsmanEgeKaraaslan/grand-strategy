package com.reach.strategy.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A player account in this game, linked one-to-one to a Supabase Auth user. Holds no credentials:
 * authentication is entirely Supabase's responsibility.
 */
@Entity
@Table(name = "player")
public class Player {

	@Id
	private UUID id;

	@Column(name = "supabase_user_id", updatable = false)
	private UUID supabaseUserId;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Player() {
	}

	public UUID id() {
		return id;
	}

	public UUID supabaseUserId() {
		return supabaseUserId;
	}

	public String displayName() {
		return displayName;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	public void rename(DisplayName newName, Instant now) {
		this.displayName = newName.value();
		this.updatedAt = now;
	}

}
