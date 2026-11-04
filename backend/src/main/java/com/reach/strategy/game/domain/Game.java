package com.reach.strategy.game.domain;

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

import com.reach.strategy.game.rules.TurnOrder;

import org.springframework.data.domain.Persistable;

/**
 * A game: lobby, start and sequential-turn state (docs/TURN_SYSTEM.md §3). Every mutation must
 * happen while holding the game's row lock ({@link GameRepository#findByIdForUpdate} or
 * {@link GameRepository#findByInviteCodeForUpdate}), which serializes all changes to one game.
 */
@Entity
@Table(name = "game")
public class Game implements Persistable<UUID> {

	/** Lobbies are two-player for now; the schema and rules already allow more. */
	public static final int DEFAULT_MAX_PLAYERS = 2;

	@Id
	private UUID id;

	@Column(nullable = false)
	private String name;

	@Column(name = "host_player_id", nullable = false, updatable = false)
	private UUID hostPlayerId;

	@Column(name = "map_template_id", nullable = false, updatable = false)
	private UUID mapTemplateId;

	/** Balance values (costs, rates, durations) come from this ruleset version for the whole game. */
	@Column(name = "ruleset_version", nullable = false, updatable = false)
	private String rulesetVersion;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private GameStatus status;

	@Column(name = "max_players", nullable = false, updatable = false)
	private int maxPlayers;

	@Column(name = "player_count", nullable = false)
	private int playerCount;

	@Column(name = "invite_code", nullable = false, updatable = false)
	private String inviteCode;

	@Column(name = "current_round")
	private Integer currentRound;

	@Column(name = "active_seat")
	private Integer activeSeat;

	/** Global count of player turns started; clients echo it back to act on the turn they saw. */
	@Column(name = "turn_number", nullable = false)
	private int turnNumber;

	/** Incremented on every committed change visible to players; used as the state ETag. */
	@Column(name = "state_version", nullable = false)
	private long stateVersion;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "last_action_at")
	private Instant lastActionAt;

	/** The peace conference that pauses normal play (Phase 10), or null. The turn state is untouched. */
	@Column(name = "paused_conference_id")
	private UUID pausedConferenceId;

	// Ids are assigned by the application, so tell Spring Data when a row is new (insert, no merge).
	@Transient
	private boolean isNew = true;

	protected Game() {
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

	private Game(UUID id, String name, UUID hostPlayerId, int maxPlayers, UUID mapTemplateId, String rulesetVersion,
			InviteCode inviteCode, Instant now) {
		this.rulesetVersion = rulesetVersion;
		this.id = id;
		this.name = name;
		this.hostPlayerId = hostPlayerId;
		this.maxPlayers = maxPlayers;
		this.mapTemplateId = mapTemplateId;
		this.inviteCode = inviteCode.value();
		this.status = GameStatus.WAITING_FOR_PLAYERS;
		this.playerCount = 0;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	 * Creates a lobby with the host already seated in the first slot. Returns both so the caller
	 * persists them together.
	 */
	public static Created create(String name, UUID hostPlayerId, int maxPlayers, UUID mapTemplateId,
			String rulesetVersion, InviteCode inviteCode, Instant now) {
		if (maxPlayers < 2) {
			throw new IllegalArgumentException("A game needs at least two player slots");
		}
		Game game = new Game(UUID.randomUUID(), name, hostPlayerId, maxPlayers, mapTemplateId, rulesetVersion,
				inviteCode, now);
		GamePlayer host = game.admit(hostPlayerId, now);
		return new Created(game, host);
	}

	/**
	 * Seats a player in the next free slot (seat numbers are 0-based, in join order) and marks the
	 * lobby READY when the last slot is filled. The caller must have checked that the player is
	 * not already a member.
	 *
	 * @throws GameFullException if no slot is free
	 */
	public GamePlayer admit(UUID playerId, Instant now) {
		if (status != GameStatus.WAITING_FOR_PLAYERS || playerCount >= maxPlayers) {
			throw new GameFullException(id);
		}
		GamePlayer membership = GamePlayer.seat(id, playerId, playerCount, now);
		playerCount++;
		if (playerCount == maxPlayers) {
			status = GameStatus.READY;
		}
		touch(now);
		return membership;
	}

	/** Nation choices are possible only before the game starts. */
	public boolean acceptsNationChoices() {
		return status == GameStatus.WAITING_FOR_PLAYERS || status == GameStatus.READY;
	}

	/** Records a lobby change (e.g. a nation choice) so polling clients see a new version. */
	public void touch(Instant now) {
		stateVersion++;
		updatedAt = now;
	}

	/**
	 * READY → IN_PROGRESS. The host (seat 0) takes the first turn of round 1.
	 *
	 * @throws IllegalStateException unless the lobby is READY
	 */
	public void start(Instant now) {
		if (status != GameStatus.READY) {
			throw new IllegalStateException("Only a READY game can start, status is " + status);
		}
		status = GameStatus.IN_PROGRESS;
		currentRound = 1;
		activeSeat = 0;
		turnNumber = 1;
		startedAt = now;
		lastActionAt = now;
		touch(now);
	}

	/** The turn change that ending the active player's turn would cause. */
	public TurnOrder.Advance nextTurn() {
		return nextTurn(java.util.Set.of());
	}

	/** @param skippedSeats seats of eliminated players, which take no turn */
	public TurnOrder.Advance nextTurn(java.util.Set<Integer> skippedSeats) {
		return nextTurn(skippedSeats, playerCount);
	}

	/**
	 * @param seatCount all seats of the game: the human players plus the computer seats added at the
	 * start (Phase 11)
	 */
	public TurnOrder.Advance nextTurn(java.util.Set<Integer> skippedSeats, int seatCount) {
		if (status != GameStatus.IN_PROGRESS) {
			throw new IllegalStateException("Game is not in progress");
		}
		return TurnOrder.next(currentRound, activeSeat, seatCount, skippedSeats);
	}

	public void applyTurnAdvance(TurnOrder.Advance advance, Instant now) {
		currentRound = advance.nextRound();
		activeSeat = advance.nextSeat();
		turnNumber++;
		lastActionAt = now;
		touch(now);
	}

	/**
	 * IN_PROGRESS → FINISHED after the round end that met the victory condition. The round and the
	 * last active seat are kept as they were, so the final state reads as of the finishing round.
	 *
	 * @throws IllegalStateException unless the game is in progress (a game finishes once)
	 */
	public void finish(Instant now) {
		if (status != GameStatus.IN_PROGRESS) {
			throw new IllegalStateException("Only a game in progress can finish, status is " + status);
		}
		status = GameStatus.FINISHED;
		pausedConferenceId = null;
		lastActionAt = now;
		touch(now);
	}

	/**
	 * Any status before FINISHED → ABANDONED (administrator action). The turn state is kept as it was,
	 * so a started game reads as of the moment it was closed.
	 *
	 * @throws IllegalStateException if the game is already finished or abandoned
	 */
	public void abandon(Instant now) {
		if (closed()) {
			throw new IllegalStateException("Game " + id + " is already " + status);
		}
		status = GameStatus.ABANDONED;
		pausedConferenceId = null;
		touch(now);
	}

	/** FINISHED or ABANDONED: nothing changes any more. */
	public boolean closed() {
		return status == GameStatus.FINISHED || status == GameStatus.ABANDONED;
	}

	/** Normal play pauses for a peace conference; round, seat and turn number are preserved. */
	public void pause(UUID conferenceId, Instant now) {
		if (status != GameStatus.IN_PROGRESS || pausedConferenceId != null) {
			throw new IllegalStateException("Game " + id + " cannot pause now");
		}
		pausedConferenceId = conferenceId;
		touch(now);
	}

	/** Play resumes exactly where it was paused. */
	public void resume(UUID conferenceId, Instant now) {
		if (!conferenceId.equals(pausedConferenceId)) {
			throw new IllegalStateException("Game " + id + " is not paused by conference " + conferenceId);
		}
		pausedConferenceId = null;
		touch(now);
	}

	public boolean paused() {
		return pausedConferenceId != null;
	}

	public UUID pausedConferenceId() {
		return pausedConferenceId;
	}

	public boolean finished() {
		return status == GameStatus.FINISHED;
	}

	public int availableSlots() {
		return maxPlayers - playerCount;
	}

	public boolean isHost(UUID playerId) {
		return hostPlayerId.equals(playerId);
	}

	public UUID id() {
		return id;
	}

	public String name() {
		return name;
	}

	public UUID hostPlayerId() {
		return hostPlayerId;
	}

	public UUID mapTemplateId() {
		return mapTemplateId;
	}

	public String rulesetVersion() {
		return rulesetVersion;
	}

	public GameStatus status() {
		return status;
	}

	public int maxPlayers() {
		return maxPlayers;
	}

	public int playerCount() {
		return playerCount;
	}

	public InviteCode inviteCode() {
		return new InviteCode(inviteCode);
	}

	public Integer currentRound() {
		return currentRound;
	}

	public Integer activeSeat() {
		return activeSeat;
	}

	public int turnNumber() {
		return turnNumber;
	}

	public long stateVersion() {
		return stateVersion;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	public Instant startedAt() {
		return startedAt;
	}

	public Instant lastActionAt() {
		return lastActionAt;
	}

	public record Created(Game game, GamePlayer host) {
	}

}
