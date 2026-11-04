package com.reach.strategy.game.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class GameTest {

	static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

	static final UUID MAP = UUID.randomUUID();

	final UUID host = UUID.randomUUID();

	@Test
	void creatorTakesTheFirstSlotAndTheLobbyWaitsForPlayers() {
		Game.Created created = Game.create("Sundered Reach", host, 2, MAP, "v1", InviteCode.generate(), NOW);

		assertThat(created.host().playerId()).isEqualTo(host);
		assertThat(created.host().seatOrder()).isZero();
		assertThat(created.host().gameId()).isEqualTo(created.game().id());
		assertThat(created.game().hostPlayerId()).isEqualTo(host);
		assertThat(created.game().status()).isEqualTo(GameStatus.WAITING_FOR_PLAYERS);
		assertThat(created.game().playerCount()).isEqualTo(1);
		assertThat(created.game().availableSlots()).isEqualTo(1);
	}

	@Test
	void secondPlayerTakesTheRemainingSlotAndTheLobbyBecomesReady() {
		Game game = Game.create("Sundered Reach", host, 2, MAP, "v1", InviteCode.generate(), NOW).game();
		UUID guest = UUID.randomUUID();

		GamePlayer seat = game.admit(guest, NOW.plusSeconds(60));

		assertThat(seat.seatOrder()).isEqualTo(1);
		assertThat(seat.playerId()).isEqualTo(guest);
		assertThat(game.status()).isEqualTo(GameStatus.READY);
		assertThat(game.availableSlots()).isZero();
		assertThat(game.updatedAt()).isEqualTo(NOW.plusSeconds(60));
	}

	@Test
	void aFullLobbyRejectsFurtherPlayers() {
		Game game = Game.create("Sundered Reach", host, 2, MAP, "v1", InviteCode.generate(), NOW).game();
		game.admit(UUID.randomUUID(), NOW);

		assertThatThrownBy(() -> game.admit(UUID.randomUUID(), NOW)).isInstanceOf(GameFullException.class);
		assertThat(game.playerCount()).isEqualTo(2);
		assertThat(game.status()).isEqualTo(GameStatus.READY);
	}

	@Test
	void largerLobbiesStayOpenUntilTheLastSlotIsTaken() {
		Game game = Game.create("Four Crowns", host, 4, MAP, "v1", InviteCode.generate(), NOW).game();

		assertThat(game.admit(UUID.randomUUID(), NOW).seatOrder()).isEqualTo(1);
		assertThat(game.admit(UUID.randomUUID(), NOW).seatOrder()).isEqualTo(2);
		assertThat(game.status()).isEqualTo(GameStatus.WAITING_FOR_PLAYERS);
		assertThat(game.admit(UUID.randomUUID(), NOW).seatOrder()).isEqualTo(3);
		assertThat(game.status()).isEqualTo(GameStatus.READY);
	}

	@Test
	void startingAReadyGameMakesTheHostTheFirstActivePlayer() {
		Game game = Game.create("Sundered Reach", host, 2, MAP, "v1", InviteCode.generate(), NOW).game();
		game.admit(UUID.randomUUID(), NOW);
		long versionBefore = game.stateVersion();

		game.start(NOW.plusSeconds(5));

		assertThat(game.status()).isEqualTo(GameStatus.IN_PROGRESS);
		assertThat(game.currentRound()).isEqualTo(1);
		assertThat(game.activeSeat()).isZero();
		assertThat(game.turnNumber()).isEqualTo(1);
		assertThat(game.startedAt()).isEqualTo(NOW.plusSeconds(5));
		assertThat(game.stateVersion()).isGreaterThan(versionBefore);
		assertThat(game.acceptsNationChoices()).isFalse();
	}

	@Test
	void onlyAReadyGameCanStart() {
		Game waiting = Game.create("Sundered Reach", host, 2, MAP, "v1", InviteCode.generate(), NOW).game();
		assertThatThrownBy(() -> waiting.start(NOW)).isInstanceOf(IllegalStateException.class);

		Game started = Game.create("Sundered Reach", host, 2, MAP, "v1", InviteCode.generate(), NOW).game();
		started.admit(UUID.randomUUID(), NOW);
		started.start(NOW);
		assertThatThrownBy(() -> started.start(NOW)).isInstanceOf(IllegalStateException.class);
		assertThat(started.turnNumber()).isEqualTo(1);
	}

	@Test
	void turnsPassInSeatOrderAndTheRoundAdvancesAfterTheLastSeat() {
		Game game = Game.create("Sundered Reach", host, 2, MAP, "v1", InviteCode.generate(), NOW).game();
		game.admit(UUID.randomUUID(), NOW);
		game.start(NOW);

		var first = game.nextTurn();
		assertThat(first.roundCompleted()).isFalse();
		game.applyTurnAdvance(first, NOW);
		assertThat(game.activeSeat()).isEqualTo(1);
		assertThat(game.currentRound()).isEqualTo(1);
		assertThat(game.turnNumber()).isEqualTo(2);

		var second = game.nextTurn();
		assertThat(second.roundCompleted()).isTrue();
		assertThat(second.endedRound()).isEqualTo(1);
		game.applyTurnAdvance(second, NOW);
		assertThat(game.activeSeat()).isZero();
		assertThat(game.currentRound()).isEqualTo(2);
		assertThat(game.turnNumber()).isEqualTo(3);
	}

	@Test
	void aLobbyHasNoTurns() {
		Game game = Game.create("Sundered Reach", host, 2, MAP, "v1", InviteCode.generate(), NOW).game();
		assertThatThrownBy(game::nextTurn).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aGameNeedsAtLeastTwoSlots() {
		assertThatThrownBy(() -> Game.create("Solo", host, 1, MAP, "v1", InviteCode.generate(), NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anAdministratorCanAbandonAGameOnceBeforeItFinishes() {
		Game game = Game.create("Stuck", host, 2, MAP, "v1", InviteCode.generate(), NOW).game();
		long version = game.stateVersion();
		game.abandon(NOW);
		assertThat(game.status()).isEqualTo(GameStatus.ABANDONED);
		assertThat(game.closed()).isTrue();
		assertThat(game.finished()).isFalse();
		assertThat(game.stateVersion()).isGreaterThan(version);
		assertThatThrownBy(() -> game.abandon(NOW)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aSeatHandedToTheComputerKeepsItsPlaceButChangesItsPlayer() {
		Game.Created created = Game.create("Handover", host, 2, MAP, "v1", InviteCode.generate(), NOW);
		GamePlayer seat = created.host();
		UUID computer = UUID.randomUUID();
		seat.handOverToComputer(computer);
		assertThat(seat.playerId()).isEqualTo(computer);
		assertThat(seat.computer()).isTrue();
		assertThat(seat.seatOrder()).isZero();
		assertThatThrownBy(() -> seat.handOverToComputer(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
	}

}
