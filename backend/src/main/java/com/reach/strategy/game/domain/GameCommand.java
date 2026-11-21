package com.reach.strategy.game.domain;

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
 * An executed gameplay command, recorded in the same transaction as its effects. Its
 * (game, player, idempotency key) uniqueness turns client retries into safe replays
 * (docs/TURN_SYSTEM.md §4.2).
 */
@Entity
@Table(name = "game_command")
public class GameCommand implements Persistable<UUID> {

	public static final String END_TURN = "END_TURN";

	public static final String BUILD_WORKSHOP = "BUILD_WORKSHOP";

	@Id
	private UUID id;

	@Column(name = "game_id", nullable = false, updatable = false)
	private UUID gameId;

	@Column(name = "game_player_id", nullable = false, updatable = false)
	private UUID gamePlayerId;

	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private UUID idempotencyKey;

	@Column(name = "command_type", nullable = false, updatable = false)
	private String commandType;

	/** The turn number the command was executed in. */
	@Column(name = "turn_number", nullable = false, updatable = false)
	private int turnNumber;

	/** What the command produced (e.g. the new building), so a retry can rebuild its response. */
	@Column(name = "result_ref", updatable = false)
	private UUID resultRef;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** Its id is assigned here, so without this Hibernate would read the row before inserting it. */
	@Transient
	private boolean isNew = true;

	protected GameCommand() {
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

	public GameCommand(UUID gameId, UUID gamePlayerId, UUID idempotencyKey, String commandType, int turnNumber,
			Instant now) {
		this.id = UUID.randomUUID();
		this.gameId = gameId;
		this.gamePlayerId = gamePlayerId;
		this.idempotencyKey = idempotencyKey;
		this.commandType = commandType;
		this.turnNumber = turnNumber;
		this.createdAt = now;
	}

	public GameCommand withResultRef(UUID resultRef) {
		this.resultRef = resultRef;
		return this;
	}

	public UUID resultRef() {
		return resultRef;
	}

	public String commandType() {
		return commandType;
	}

	public int turnNumber() {
		return turnNumber;
	}

}
