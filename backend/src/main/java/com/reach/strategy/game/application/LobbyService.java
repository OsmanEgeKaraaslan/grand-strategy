package com.reach.strategy.game.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.game.api.ComputerOpponents;
import com.reach.strategy.game.api.GameLifecycle;
import com.reach.strategy.game.api.GameStartInitializer;
import com.reach.strategy.game.api.MatchCreation;
import com.reach.strategy.game.rules.QuickMatchNations;
import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GameFullException;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.game.domain.InviteCode;
import com.reach.strategy.identity.api.ComputerPlayers;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PlayerDirectory;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lobby use cases: create, list, view, join, choose a nation, start. Authorization rule: a game's
 * details, participants and invitation code are visible only to its members; to everyone else
 * the game does not exist (404). Every mutation first takes the game's row lock.
 */
@Service
public class LobbyService implements MatchCreation {

	private static final int INVITE_CODE_ATTEMPTS = 5;

	private final GameRepository games;
	private final GamePlayerRepository memberships;
	private final PlayerDirectory playerDirectory;
	private final WorldApi world;
	private final List<GameStartInitializer> startInitializers;
	private final String currentRulesetVersion;
	private final Clock clock;
	private final ObjectProvider<ComputerOpponents> computerOpponents;
	private final ComputerPlayers computerPlayers;
	private final ApplicationEventPublisher events;
	private final int maxUnfinishedGames;
	private final GameCapacity capacity;

	LobbyService(GameRepository games, GamePlayerRepository memberships, PlayerDirectory playerDirectory, WorldApi world,
			List<GameStartInitializer> startInitializers,
			@Value("${strategy.ruleset.current-version}") String currentRulesetVersion, Clock clock,
			ObjectProvider<ComputerOpponents> computerOpponents, ComputerPlayers computerPlayers,
			ApplicationEventPublisher events,
			@Value("${strategy.game.max-unfinished-games:10}") int maxUnfinishedGames, GameCapacity capacity) {
		this.capacity = capacity;
		this.events = events;
		this.maxUnfinishedGames = maxUnfinishedGames;
		this.computerOpponents = computerOpponents;
		this.computerPlayers = computerPlayers;
		this.games = games;
		this.memberships = memberships;
		this.playerDirectory = playerDirectory;
		this.world = world;
		this.startInitializers = List.copyOf(startInitializers);
		this.currentRulesetVersion = currentRulesetVersion;
		this.clock = clock;
	}

	@Transactional
	public LobbyView create(CurrentPlayer me, String name) {
		return create(me, name, null, null);
	}

	/**
	 * Creates a lobby for {@code maxPlayers} humans (2 up to what the map supports; default 2) on the
	 * chosen or default map. The map's other nations will be NPC-controlled.
	 */
	@Transactional
	public LobbyView create(CurrentPlayer me, String name, Integer maxPlayers, String mapKey) {
		Instant now = clock.instant();
		UUID mapId = mapKey == null || mapKey.isBlank() ? world.defaultMapId()
				: world.mapId(mapKey)
					.orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "MAP_NOT_FOUND",
							"That map does not exist."));
		WorldApi.MapSummary map = world.mapSummary(mapId);
		int players = maxPlayers == null ? Game.DEFAULT_MAX_PLAYERS : maxPlayers;
		if (players < 2 || players > map.maxPlayers()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_PLAYER_COUNT",
					"This map supports 2 to " + map.maxPlayers() + " human players.");
		}
		requireRoomForAnotherGame(me);
		capacity.require();
		Game.Created created = Game.create(name.strip(), me.id(), players, mapId, currentRulesetVersion,
				freshInviteCode(), now);
		games.save(created.game());
		memberships.save(created.host());
		return view(created.game(), List.of(created.host()), me);
	}

	/** The caller's games with what the dashboard needs to resume them; two queries plus cached maps. */
	@Transactional(readOnly = true)
	public List<GameSummary> myGames(CurrentPlayer me) {
		List<Game> mine = games.findAllForMember(me.id());
		Map<UUID, List<GamePlayer>> seatsByGame = memberships
			.findByGameIdIn(mine.stream().map(Game::id).toList())
			.stream()
			.collect(Collectors.groupingBy(GamePlayer::gameId));
		Map<UUID, WorldApi.MapSummary> maps = new java.util.HashMap<>();
		return mine.stream().map(game -> {
			List<GamePlayer> seats = seatsByGame.getOrDefault(game.id(), List.of());
			GamePlayer seat = seats.stream().filter(s -> s.playerId().equals(me.id())).findFirst().orElseThrow();
			WorldApi.MapSummary map = maps.computeIfAbsent(game.mapTemplateId(), world::mapSummary);
			GameSummary.Nation nation = seat.nationTemplateId() == null ? null
					: map.nation(seat.nationTemplateId())
						.map(n -> new GameSummary.Nation(n.key(), n.name(), n.color()))
						.orElse(null);
			int computers = (int) seats.stream().filter(GamePlayer::computer).count();
			return GameSummary.of(game, seat.seatOrder(), me.id(), map.name(), nation, seat.eliminated(),
					seats.size() - computers, computers);
		}).toList();
	}

	/**
	 * Bounds the lobbies and running games one account can hold (Phase 15B): each costs storage and,
	 * with computer seats, worker time. Finished games do not count.
	 */
	private void requireRoomForAnotherGame(CurrentPlayer me) {
		if (games.countUnfinishedForMember(me.id()) >= maxUnfinishedGames) {
			throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_UNFINISHED_GAMES",
					"You already have " + maxUnfinishedGames + " unfinished games. Finish one before starting or joining another.");
		}
	}

	@Transactional(readOnly = true)
	public LobbyView lobby(CurrentPlayer me, UUID gameId) {
		Game game = games.findById(gameId).orElseThrow(GameErrors::gameNotFound);
		List<GamePlayer> members = membersOf(game, me);
		return view(game, members, me);
	}

	/**
	 * Joins the game identified by an invitation code. Concurrent joins of one game are serialized
	 * by the game row lock, so two players can never both take the last slot; joining a game you
	 * are already in returns it unchanged (no duplicate membership).
	 */
	@Transactional
	public LobbyView join(CurrentPlayer me, String rawInviteCode) {
		InviteCode code = InviteCode.parse(rawInviteCode).orElseThrow(GameErrors::inviteCodeNotFound);
		Game game = games.findByInviteCodeForUpdate(code.value()).orElseThrow(GameErrors::inviteCodeNotFound);
		if (game.closed() && !game.finished()) {
			throw GameErrors.abandoned();
		}
		if (!memberships.existsByGameIdAndPlayerId(game.id(), me.id())) {
			requireRoomForAnotherGame(me);
			try {
				memberships.save(game.admit(me.id(), clock.instant()));
			}
			catch (GameFullException ex) {
				throw new ApiException(HttpStatus.CONFLICT, "GAME_FULL", "This game has no free player slot.");
			}
		}
		return view(game, memberships.findByGameIdOrderBySeatOrder(game.id()), me);
	}

	/**
	 * Picks (or changes) the caller's nation before the start. The game row lock serializes
	 * competing picks; the unique (game, nation) constraint is the backstop. Picking the nation
	 * you already have is a no-op.
	 */
	@Transactional
	public LobbyView chooseNation(CurrentPlayer me, UUID gameId, UUID nationTemplateId) {
		Game game = games.findByIdForUpdate(gameId).orElseThrow(GameErrors::gameNotFound);
		List<GamePlayer> members = membersOf(game, me);
		if (!game.acceptsNationChoices()) {
			throw GameErrors.alreadyStarted();
		}
		WorldApi.MapSummary map = world.mapSummary(game.mapTemplateId());
		if (map.nation(nationTemplateId).isEmpty()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NATION_NOT_FOUND",
					"That nation does not exist on this game's map.");
		}
		if (!map.nation(nationTemplateId).get().playable()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NATION_NOT_PLAYABLE",
					"That nation is controlled by the game; choose one of the playable nations.");
		}
		GamePlayer mine = mine(members, me);
		if (nationTemplateId.equals(mine.nationTemplateId())) {
			return view(game, members, me, map);
		}
		boolean taken = members.stream().anyMatch(member -> nationTemplateId.equals(member.nationTemplateId()));
		if (taken) {
			throw new ApiException(HttpStatus.CONFLICT, "NATION_TAKEN", "Another player has already chosen that nation.");
		}
		mine.chooseNation(nationTemplateId);
		game.touch(clock.instant());
		try {
			memberships.saveAndFlush(mine);
		}
		catch (DataIntegrityViolationException raceBackstop) {
			throw new ApiException(HttpStatus.CONFLICT, "NATION_TAKEN", "Another player has already chosen that nation.");
		}
		return view(game, members, me, map);
	}

	/**
	 * Starts a READY game (host only): instantiates its own world from the map template and makes
	 * the host (seat 0) the first active player. Runs as one transaction under the game lock, so a
	 * game is started at most once; repeating the request after success returns the started game
	 * unchanged.
	 */
	@Transactional
	public LobbyView start(CurrentPlayer me, UUID gameId) {
		Game game = games.findByIdForUpdate(gameId).orElseThrow(GameErrors::gameNotFound);
		List<GamePlayer> members = membersOf(game, me);
		if (!game.isHost(me.id())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "NOT_HOST", "Only the host can start the game.");
		}
		WorldApi.MapSummary map = world.mapSummary(game.mapTemplateId());
		if (game.status() == GameStatus.IN_PROGRESS || game.finished()) {
			return view(game, members, me, map);
		}
		if (game.closed()) {
			throw GameErrors.abandoned();
		}
		LobbyView.StartBlocker blocker = LobbyView.startBlocker(game, members, map);
		if (blocker == LobbyView.StartBlocker.NOT_READY) {
			throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_READY", "All player slots must be filled first.");
		}
		if (blocker == LobbyView.StartBlocker.NATIONS_NOT_SELECTED) {
			throw new ApiException(HttpStatus.CONFLICT, "NATIONS_NOT_SELECTED",
					"Every player must choose a different nation first.");
		}
		instantiateAndStart(game, members, map);
		// Reported after commit (notifications); the host started it, so is not told.
		events.publishEvent(new GameLifecycle.Started(game.id(), members.stream()
			.filter(m -> !m.computer() && !m.playerId().equals(me.id()))
			.map(GamePlayer::playerId)
			.collect(Collectors.toSet())));
		return view(game, members, me, map);
	}

	/**
	 * Gives every nation nobody chose a computer seat (Phase 11, rulesets with computer opponents),
	 * instantiates the game's world, runs the start initializers and starts the game. Shared by the
	 * host's start and Quick Match creation; the caller holds the game row lock.
	 */
	private void instantiateAndStart(Game game, List<GamePlayer> members, WorldApi.MapSummary map) {
		List<GamePlayer> seats = new ArrayList<>(members);
		Set<UUID> computerSeats = new HashSet<>();
		ComputerOpponents opponents = computerOpponents.getIfAvailable();
		if (opponents != null && opponents.enabled(game.rulesetVersion())) {
			// Phase 11: every nation nobody chose gets a computer seat after the human seats, in key order.
			Set<UUID> chosen = members.stream().map(GamePlayer::nationTemplateId).collect(Collectors.toSet());
			int seat = members.size();
			for (WorldApi.NationOption nation : map.nations()
				.stream()
				.sorted(Comparator.comparing(WorldApi.NationOption::key))
				.toList()) {
				if (!chosen.contains(nation.id())) {
					GamePlayer computer = GamePlayer.computerSeat(game.id(), computerPlayers.create(), seat++, nation.id(),
							clock.instant());
					memberships.saveAndFlush(computer);
					seats.add(computer);
					computerSeats.add(computer.id());
				}
			}
		}
		Map<UUID, UUID> gamePlayerByNation = seats.stream()
			.collect(Collectors.toMap(GamePlayer::nationTemplateId, GamePlayer::id));
		world.instantiate(game.id(), game.mapTemplateId(), gamePlayerByNation, computerSeats);
		// e.g. treasuries and starting workshops; the game keeps the ruleset it was created with.
		startInitializers.forEach(initializer -> initializer.initialize(game.id(), game.mapTemplateId(), game.rulesetVersion()));
		game.start(clock.instant());
	}

	/**
	 * Quick Match (Phase 13B): a started game for exactly the given players, created in the caller's
	 * transaction with the current ruleset; the first player hosts. Nothing here differs from a private
	 * game except that the factions are assigned ({@link QuickMatchNations}) instead of chosen.
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public CreatedMatch createQuickMatch(String mapKey, List<UUID> playerIds, long seed) {
		if (playerIds.size() != 2 || playerIds.get(0).equals(playerIds.get(1))) {
			throw new IllegalArgumentException("A Quick Match needs two different players");
		}
		capacity.require();
		Instant now = clock.instant();
		UUID mapId = world.mapId(mapKey).orElseThrow(() -> new IllegalStateException("Quick Match map missing: " + mapKey));
		WorldApi.MapSummary map = world.mapSummary(mapId);
		WorldApi.Topology topology = world.topology(mapId);
		QuickMatchNations.Pair pair = QuickMatchNations.choose(map.nations()
			.stream()
			.map(n -> new QuickMatchNations.Candidate(n.key(), n.tier(), n.playable(), n.capitalProvinceKey()))
			.toList(), topology::neighbours, seed);
		Game.Created created = Game.create("Quick Match", playerIds.get(0), 2, mapId, currentRulesetVersion,
				freshInviteCode(), now);
		Game game = created.game();
		games.save(game);
		GamePlayer host = created.host();
		GamePlayer guest = game.admit(playerIds.get(1), now);
		WorldApi.NationOption first = nationByKey(map, pair.first());
		WorldApi.NationOption second = nationByKey(map, pair.second());
		host.chooseNation(first.id());
		guest.chooseNation(second.id());
		memberships.save(host);
		memberships.saveAndFlush(guest);
		instantiateAndStart(game, List.of(host, guest), map);
		return new CreatedMatch(game.id(), game.name(), map.name(), game.rulesetVersion(),
				List.of(new Assignment(playerIds.get(0), first.key(), first.name(), first.color()),
						new Assignment(playerIds.get(1), second.key(), second.name(), second.color())));
	}

	private static WorldApi.NationOption nationByKey(WorldApi.MapSummary map, String key) {
		return map.nations().stream().filter(n -> n.key().equals(key)).findFirst().orElseThrow();
	}

	private List<GamePlayer> membersOf(Game game, CurrentPlayer me) {
		List<GamePlayer> members = memberships.findByGameIdOrderBySeatOrder(game.id());
		if (members.stream().noneMatch(member -> member.playerId().equals(me.id()))) {
			throw GameErrors.gameNotFound();
		}
		return members;
	}

	private static GamePlayer mine(List<GamePlayer> members, CurrentPlayer me) {
		return members.stream().filter(member -> member.playerId().equals(me.id())).findFirst().orElseThrow();
	}

	private InviteCode freshInviteCode() {
		// 60 random bits make collisions vanishingly rare; the unique constraint is the backstop.
		for (int attempt = 0; attempt < INVITE_CODE_ATTEMPTS; attempt++) {
			InviteCode candidate = InviteCode.generate();
			if (!games.existsByInviteCode(candidate.value())) {
				return candidate;
			}
		}
		throw new IllegalStateException("Could not generate a unique invite code");
	}

	private LobbyView view(Game game, List<GamePlayer> members, CurrentPlayer me) {
		return view(game, members, me, world.mapSummary(game.mapTemplateId()));
	}

	private LobbyView view(Game game, List<GamePlayer> members, CurrentPlayer me, WorldApi.MapSummary map) {
		// Computer seats are not lobby participants.
		List<GamePlayer> humans = members.stream().filter(member -> !member.computer()).toList();
		Map<UUID, String> names = playerDirectory.displayNames(humans.stream().map(GamePlayer::playerId).toList());
		return LobbyView.of(game, humans, names, me.id(), map);
	}

}
