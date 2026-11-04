package com.reach.strategy.game.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.identity.api.Administrators;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PlayerDirectory;
import com.reach.strategy.platform.web.ApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrators' view of games and the operator action ABANDONED (Q-T3, Q-MOD1). */
@Service
public class GameModerationService {

	private static final Logger log = LoggerFactory.getLogger(GameModerationService.class);
	private static final int LIST_LIMIT = 50;

	private final GameRepository games;
	private final GamePlayerRepository memberships;
	private final PlayerDirectory directory;
	private final Administrators admins;
	private final Clock clock;

	GameModerationService(GameRepository games, GamePlayerRepository memberships, PlayerDirectory directory,
			Administrators admins, Clock clock) {
		this.games = games;
		this.memberships = memberships;
		this.directory = directory;
		this.admins = admins;
		this.clock = clock;
	}

	/** @param humans display names of the human players, in seat order */
	public record AdminGame(UUID id, String name, GameStatus status, Integer round, int computerSeats,
			List<String> humans, Instant createdAt, Instant lastActionAt) {
	}

	/** Lobbies and running games, least recently active first: stuck games come first. */
	@Transactional(readOnly = true)
	public List<AdminGame> unfinished(CurrentPlayer me) {
		admins.requireAdmin(me);
		List<Game> open = games.findUnfinished(Limit.of(LIST_LIMIT));
		Map<UUID, List<GamePlayer>> seats = memberships.findByGameIdIn(open.stream().map(Game::id).toList())
			.stream()
			.sorted(Comparator.comparingInt(GamePlayer::seatOrder))
			.collect(Collectors.groupingBy(GamePlayer::gameId));
		Map<UUID, String> names = directory.displayNames(seats.values()
			.stream()
			.flatMap(List::stream)
			.filter(seat -> !seat.computer())
			.map(GamePlayer::playerId)
			.toList());
		return open.stream().map(game -> view(game, seats.getOrDefault(game.id(), List.of()), names)).toList();
	}

	@Transactional
	public AdminGame abandon(CurrentPlayer me, UUID gameId, String reason) {
		admins.requireAdmin(me);
		Game game = games.findByIdForUpdate(gameId).orElseThrow(GameErrors::gameNotFound);
		if (game.closed()) {
			throw new ApiException(HttpStatus.CONFLICT, "GAME_ALREADY_CLOSED", "This game has already finished or been closed.");
		}
		game.abandon(clock.instant());
		games.saveAndFlush(game);
		admins.record(me, Administrators.Action.ABANDON_GAME, null, gameId, reason.strip());
		log.info("Game {} abandoned by admin {}", gameId, me.id());
		List<GamePlayer> members = memberships.findByGameIdOrderBySeatOrder(gameId);
		return view(game, members,
				directory.displayNames(members.stream().filter(m -> !m.computer()).map(GamePlayer::playerId).toList()));
	}

	private static AdminGame view(Game game, List<GamePlayer> seats, Map<UUID, String> names) {
		return new AdminGame(game.id(), game.name(), game.status(), game.currentRound(),
				(int) seats.stream().filter(GamePlayer::computer).count(),
				seats.stream().filter(seat -> !seat.computer()).map(seat -> names.get(seat.playerId())).toList(),
				game.createdAt(), game.lastActionAt() != null ? game.lastActionAt() : game.updatedAt());
	}

}
