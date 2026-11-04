package com.reach.strategy.game.application;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.game.api.Lobbies;
import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GameFullException;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class LobbiesService implements Lobbies {

	private final GameRepository games;
	private final GamePlayerRepository memberships;
	private final WorldApi world;
	private final Clock clock;
	private final GameCapacity capacity;

	LobbiesService(GameRepository games, GamePlayerRepository memberships, WorldApi world, Clock clock,
			GameCapacity capacity) {
		this.capacity = capacity;
		this.games = games;
		this.memberships = memberships;
		this.world = world;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> namesFor(UUID playerId, Collection<UUID> gameIds) {
		if (gameIds.isEmpty()) {
			return Map.of();
		}
		Set<UUID> mine = memberships.findByPlayerId(playerId).stream().map(GamePlayer::gameId).collect(Collectors.toSet());
		return games.findAllById(gameIds.stream().filter(mine::contains).toList())
			.stream()
			.collect(Collectors.toMap(Game::id, Game::name));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<LobbyFacts> facts(UUID gameId) {
		return games.findById(gameId).map(game -> facts(game, memberships.findByGameIdOrderBySeatOrder(gameId)));
	}

	private LobbyFacts facts(Game game, List<GamePlayer> members) {
		return new LobbyFacts(game.id(), game.name(), world.mapSummary(game.mapTemplateId()).name(), game.status().name(),
				game.hostPlayerId(), game.playerCount(), game.maxPlayers(),
				members.stream().filter(m -> !m.computer()).map(GamePlayer::playerId).collect(Collectors.toSet()));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public AdmitOutcome admitInvited(UUID gameId, UUID inviterId, UUID inviteeId) {
		Optional<Game> locked = games.findByIdForUpdate(gameId);
		if (locked.isEmpty()) {
			return AdmitOutcome.NOT_FOUND;
		}
		Game game = locked.get();
		if (memberships.existsByGameIdAndPlayerId(gameId, inviteeId)) {
			return AdmitOutcome.ALREADY_MEMBER;
		}
		if (!game.isHost(inviterId)) {
			return AdmitOutcome.INVITER_NOT_HOST;
		}
		if (game.status() == GameStatus.IN_PROGRESS || game.closed()) {
			return AdmitOutcome.ALREADY_STARTED;
		}
		try {
			memberships.save(game.admit(inviteeId, clock.instant()));
			return AdmitOutcome.ADMITTED;
		}
		catch (GameFullException full) {
			return AdmitOutcome.GAME_FULL;
		}
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Faction> factions(UUID gameId) {
		Optional<Game> game = games.findById(gameId);
		if (game.isEmpty()) {
			return Map.of();
		}
		WorldApi.MapSummary map = world.mapSummary(game.get().mapTemplateId());
		Map<UUID, Faction> factions = new java.util.HashMap<>();
		for (GamePlayer seat : memberships.findByGameIdOrderBySeatOrder(gameId)) {
			if (!seat.computer() && seat.nationTemplateId() != null) {
				map.nation(seat.nationTemplateId())
					.ifPresent(n -> factions.put(seat.playerId(), new Faction(n.key(), n.name(), n.color())));
			}
		}
		return factions;
	}

	@Override
	@Transactional(readOnly = true)
	public int unfinishedGames(UUID playerId) {
		return (int) games.countUnfinishedForMember(playerId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean capacityAvailable() {
		return capacity.available();
	}

}
