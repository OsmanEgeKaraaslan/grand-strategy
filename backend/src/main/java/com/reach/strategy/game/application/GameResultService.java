package com.reach.strategy.game.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.game.domain.GameResultRepository;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PlayerDirectory;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The stored final result of a finished game, for its members (Phase 9). */
@Service
public class GameResultService {

	private final GameRepository games;
	private final GamePlayerRepository memberships;
	private final GameResultRepository results;
	private final PlayerDirectory playerDirectory;
	private final WorldApi world;
	private final JsonMapper jsonMapper;

	GameResultService(GameRepository games, GamePlayerRepository memberships, GameResultRepository results,
			PlayerDirectory playerDirectory, WorldApi world, JsonMapper jsonMapper) {
		this.games = games;
		this.memberships = memberships;
		this.results = results;
		this.playerDirectory = playerDirectory;
		this.world = world;
		this.jsonMapper = jsonMapper;
	}

	@Transactional(readOnly = true)
	public ResultView result(CurrentPlayer me, UUID gameId) {
		Game game = games.findById(gameId).orElseThrow(GameErrors::gameNotFound);
		List<GamePlayer> members = memberships.findByGameIdOrderBySeatOrder(gameId);
		if (members.stream().noneMatch(m -> m.playerId().equals(me.id()))) {
			throw GameErrors.gameNotFound();
		}
		GameResultRepository.Result result = results.find(gameId)
			.filter(r -> game.finished())
			.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "GAME_NOT_FINISHED",
					"The game has not finished; there is no result yet."));
		Map<UUID, WorldApi.NationState> nations = world.snapshot(gameId)
			.nations()
			.stream()
			.collect(Collectors.toMap(WorldApi.NationState::id, Function.identity()));
		Map<UUID, String> names = playerDirectory.displayNames(members.stream().map(GamePlayer::playerId).toList());
		List<ResultView.Standing> standings = results.standings(gameId).stream().map(s -> {
			WorldApi.NationState nation = nations.get(s.nationId());
			return new ResultView.Standing(s.rank(), s.nationId(), nation.name(), nation.color(),
					s.playerId() == null ? "Computer (NPC)" : names.getOrDefault(s.playerId(), "Unknown player"), me.id().equals(s.playerId()), s.score(),
					s.ownedProvinces(), s.ownedPopulation(), s.treasuryMg());
		}).toList();
		Map<String, Long> statistics = jsonMapper.readValue(result.statisticsJson(), new TypeReference<Map<String, Long>>() {
		});
		WorldApi.NationState winner = result.winnerNationId() == null ? null : nations.get(result.winnerNationId());
		return new ResultView(game.id(), game.name(), result.finishedRound(), result.reason(),
				result.draw() ? "DRAW" : "WINNER", result.winnerNationId(), winner == null ? null : winner.name(),
				result.rulesetVersion(), result.finishedAt(), standings, statistics);
	}

	/**
	 * @param outcome WINNER or DRAW
	 * @param standings every nation, best first
	 */
	public record ResultView(UUID gameId, String gameName, int finishedRound, String reason, String outcome,
			UUID winnerNationId, String winnerName, String rulesetVersion, Instant finishedAt, List<Standing> standings,
			Map<String, Long> statistics) {

		public record Standing(int rank, UUID nationId, String nationName, String color, String playerName, boolean you,
				long score, int ownedProvinces, long ownedPopulation, long treasuryMg) {
		}

	}

}
