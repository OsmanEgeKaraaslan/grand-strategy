package com.reach.strategy.game.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.game.api.ComputerSeats;
import com.reach.strategy.game.api.TurnControl;
import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.identity.api.CurrentPlayer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read access to computer seats and the end-of-turn port for the computer opponent (Phase 11). */
@Service
class ComputerSeatsService implements ComputerSeats, TurnControl {

	private final GameRepository games;
	private final GamePlayerRepository memberships;
	private final TurnService turns;
	private final JdbcTemplate jdbc;

	ComputerSeatsService(GameRepository games, GamePlayerRepository memberships, TurnService turns, JdbcTemplate jdbc) {
		this.games = games;
		this.memberships = memberships;
		this.turns = turns;
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Seat> seats(UUID gameId) {
		return memberships.findByGameIdOrderBySeatOrder(gameId)
			.stream()
			.filter(GamePlayer::computer)
			.map(ComputerSeatsService::seat)
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Turn> activeComputerTurn(UUID gameId) {
		Optional<Game> game = games.findById(gameId);
		if (game.isEmpty() || game.get().status() != GameStatus.IN_PROGRESS || game.get().paused()
				|| game.get().activeSeat() == null) {
			return Optional.empty();
		}
		Game g = game.get();
		return memberships.findByGameIdOrderBySeatOrder(gameId)
			.stream()
			.filter(member -> member.computer() && !member.eliminated() && member.seatOrder() == g.activeSeat())
			.findFirst()
			.map(member -> new Turn(gameId, seat(member), g.currentRound(), g.turnNumber(), g.rulesetVersion()));
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> runningGamesWithComputers() {
		return jdbc.queryForList("""
				SELECT g.id FROM game.game g
				WHERE g.status = 'IN_PROGRESS'
				  AND EXISTS (SELECT 1 FROM game.game_player gp WHERE gp.game_id = g.id AND gp.computer)
				ORDER BY g.id
				""", UUID.class);
	}

	@Override
	public void endTurn(CurrentPlayer me, UUID gameId, UUID idempotencyKey, int expectedTurnNumber) {
		turns.endTurn(me, gameId, idempotencyKey, expectedTurnNumber);
	}

	private static Seat seat(GamePlayer member) {
		return new Seat(member.id(), member.playerId(), member.seatOrder(), member.eliminated());
	}

}
