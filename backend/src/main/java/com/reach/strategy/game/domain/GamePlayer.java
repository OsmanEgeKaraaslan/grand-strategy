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

/** A player's seat in one game. Unique per (game, player) and per (game, seat). */
@Entity
@Table(name = "game_player")
public class GamePlayer implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "game_id", nullable = false, updatable = false)
	private UUID gameId;

	@Column(name = "player_id", nullable = false, updatable = false)
	private UUID playerId;

	@Column(name = "seat_order", nullable = false, updatable = false)
	private int seatOrder;

	@Column(name = "joined_at", nullable = false, updatable = false)
	private Instant joinedAt;

	// Chosen in the lobby; locked once the game starts (the game status enforces that).
	@Column(name = "nation_template_id")
	private UUID nationTemplateId;

	/** Round in which this player's nation was eliminated (Phase 10); it takes no more turns. */
	@Column(name = "eliminated_round")
	private Integer eliminatedRound;

	// Ids are assigned by the application, so tell Spring Data when a row is new (insert, no merge).
	@Transient
	private boolean isNew = true;

	protected GamePlayer() {
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

	static GamePlayer seat(UUID gameId, UUID playerId, int seatOrder, Instant now) {
		GamePlayer membership = new GamePlayer();
		membership.id = UUID.randomUUID();
		membership.gameId = gameId;
		membership.playerId = playerId;
		membership.seatOrder = seatOrder;
		membership.joinedAt = now;
		return membership;
	}

	public UUID id() {
		return id;
	}

	public UUID gameId() {
		return gameId;
	}

	public UUID playerId() {
		return playerId;
	}

	public int seatOrder() {
		return seatOrder;
	}

	public Instant joinedAt() {
		return joinedAt;
	}

	public UUID nationTemplateId() {
		return nationTemplateId;
	}

	public boolean eliminated() {
		return eliminatedRound != null;
	}

	public Integer eliminatedRound() {
		return eliminatedRound;
	}

	public void eliminate(int round) {
		if (eliminatedRound == null) {
			eliminatedRound = round;
		}
	}

	public void chooseNation(UUID nationTemplateId) {
		this.nationTemplateId = nationTemplateId;
	}

}
