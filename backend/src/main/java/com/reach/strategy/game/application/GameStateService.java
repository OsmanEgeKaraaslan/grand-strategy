package com.reach.strategy.game.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PlayerDirectory;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model of a started game for its members: turn state, players and the game's own world
 * (no fog of war in the MVP, Q-T4). Runs as one REPEATABLE READ snapshot without locks, so it
 * never blocks commands and never mixes two versions of the state.
 */
@Service
public class GameStateService {

	private final GameRepository games;
	private final GamePlayerRepository memberships;
	private final PlayerDirectory playerDirectory;
	private final WorldApi world;

	GameStateService(GameRepository games, GamePlayerRepository memberships, PlayerDirectory playerDirectory,
			WorldApi world) {
		this.games = games;
		this.memberships = memberships;
		this.playerDirectory = playerDirectory;
		this.world = world;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public GameStateView state(CurrentPlayer me, UUID gameId) {
		Game game = games.findById(gameId).orElseThrow(GameErrors::gameNotFound);
		List<GamePlayer> members = memberships.findByGameIdOrderBySeatOrder(gameId);
		GamePlayer mine = members.stream()
			.filter(member -> member.playerId().equals(me.id()))
			.findFirst()
			.orElseThrow(GameErrors::gameNotFound);
		// An abandoned game stays readable if it had started (a closed lobby has no world).
		boolean readable = game.status() == GameStatus.IN_PROGRESS || game.status() == GameStatus.FINISHED
				|| (game.status() == GameStatus.ABANDONED && game.startedAt() != null);
		if (!readable) {
			throw game.status() == GameStatus.ABANDONED ? GameErrors.abandoned() : GameErrors.notInProgress();
		}

		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		Map<UUID, String> names = playerDirectory.displayNames(members.stream().map(GamePlayer::playerId).toList());
		Map<UUID, WorldApi.NationState> nationByPlayer = snapshot.nations()
			.stream()
			.filter(nation -> nation.gamePlayerId() != null)
			.collect(Collectors.toMap(WorldApi.NationState::gamePlayerId, Function.identity()));

		List<GameStateView.Player> players = members.stream()
			.map(member -> new GameStateView.Player(member.seatOrder(),
					member.computer() ? "Computer" : names.getOrDefault(member.playerId(), "Unknown player"),
					game.isHost(member.playerId()), member == mine, nationByPlayer.get(member.id()).id(), member.computer()))
			.toList();
		Map<UUID, Integer> seatByGamePlayer = members.stream()
			.collect(Collectors.toMap(GamePlayer::id, GamePlayer::seatOrder));
		List<GameStateView.Nation> nations = snapshot.nations()
			.stream()
			.map(nation -> new GameStateView.Nation(nation.id(), nation.name(), nation.color(),
					nation.gamePlayerId() == null ? null : seatByGamePlayer.get(nation.gamePlayerId()), nation.control(),
					nation.eliminatedRound()))
			.toList();
		List<GameStateView.Province> provinces = snapshot.provinces()
			.stream()
			.map(province -> new GameStateView.Province(province.provinceKey(), province.ownerNationId(),
					province.controllerNationId(), new GameStateView.City(province.cityId(), province.cityName(), province.population())))
			.toList();

		return new GameStateView(game.id(), game.name(), game.status(), game.mapTemplateId(), game.currentRound(),
				game.activeSeat(), game.turnNumber(), game.stateVersion(), mine.seatOrder(),
				game.status() == GameStatus.IN_PROGRESS && !game.paused() && !mine.eliminated()
						&& game.activeSeat() == mine.seatOrder(),
				players, nations, provinces, game.pausedConferenceId(), mine.eliminated());
	}

	/**
	 * @param yourTurn whether the caller is the active player
	 */
	public record GameStateView(UUID id, String name, GameStatus status, UUID mapId, int round, int activeSeat,
			int turnNumber, long stateVersion, int yourSeat, boolean yourTurn, List<Player> players, List<Nation> nations,
			List<Province> provinces, UUID pausedConferenceId, boolean youAreEliminated) {

		/** @param computer a computer-controlled seat (Phase 11) */
		public record Player(int seat, String displayName, boolean host, boolean you, UUID nationId, boolean computer) {
		}

		/**
		 * @param seat the seat of its human player, or null for an NPC-controlled nation
		 * @param control HUMAN or NPC
		 * @param eliminatedRound round in which it was eliminated, or null
		 */
		public record Nation(UUID id, String name, String color, Integer seat, String control, Integer eliminatedRound) {
		}

		/** Legal owner and military controller; they differ only while occupied (Q-W1). */
		public record Province(String key, UUID ownerNationId, UUID controllerNationId, City city) {
		}

		public record City(UUID id, String name, int population) {
		}

	}

}
