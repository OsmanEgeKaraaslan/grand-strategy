package com.reach.strategy.game.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.game.api.GameStartInitializer;
import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GameFullException;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.game.domain.InviteCode;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PlayerDirectory;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lobby use cases: create, list, view, join, choose a nation, start. Authorization rule: a game's
 * details, participants and invitation code are visible only to its members; to everyone else
 * the game does not exist (404). Every mutation first takes the game's row lock.
 */
@Service
public class LobbyService {

	private static final int INVITE_CODE_ATTEMPTS = 5;

	private final GameRepository games;
	private final GamePlayerRepository memberships;
	private final PlayerDirectory playerDirectory;
	private final WorldApi world;
	private final List<GameStartInitializer> startInitializers;
	private final String currentRulesetVersion;
	private final Clock clock;

	LobbyService(GameRepository games, GamePlayerRepository memberships, PlayerDirectory playerDirectory, WorldApi world,
			List<GameStartInitializer> startInitializers,
			@Value("${strategy.ruleset.current-version}") String currentRulesetVersion, Clock clock) {
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
		Instant now = clock.instant();
		Game.Created created = Game.create(name.strip(), me.id(), Game.DEFAULT_MAX_PLAYERS, world.defaultMapId(),
				currentRulesetVersion, freshInviteCode(), now);
		games.save(created.game());
		memberships.save(created.host());
		return view(created.game(), List.of(created.host()), me);
	}

	@Transactional(readOnly = true)
	public List<GameSummary> myGames(CurrentPlayer me) {
		Map<UUID, Integer> mySeats = memberships.findByPlayerId(me.id())
			.stream()
			.collect(Collectors.toMap(GamePlayer::gameId, GamePlayer::seatOrder));
		return games.findAllForMember(me.id())
			.stream()
			.map(game -> GameSummary.of(game, mySeats.get(game.id()), me.id()))
			.toList();
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
		if (!memberships.existsByGameIdAndPlayerId(game.id(), me.id())) {
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
		if (game.status() == GameStatus.IN_PROGRESS || game.status() == GameStatus.FINISHED) {
			return view(game, members, me, map);
		}
		LobbyView.StartBlocker blocker = LobbyView.startBlocker(game, members, map);
		if (blocker == LobbyView.StartBlocker.NOT_READY) {
			throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_READY", "All player slots must be filled first.");
		}
		if (blocker == LobbyView.StartBlocker.NATIONS_NOT_SELECTED) {
			throw new ApiException(HttpStatus.CONFLICT, "NATIONS_NOT_SELECTED",
					"Every player must choose a different nation first.");
		}
		Map<UUID, UUID> gamePlayerByNation = members.stream()
			.collect(Collectors.toMap(GamePlayer::nationTemplateId, GamePlayer::id));
		world.instantiate(game.id(), game.mapTemplateId(), gamePlayerByNation);
		// e.g. treasuries and starting workshops; the game keeps the ruleset it was created with.
		startInitializers.forEach(initializer -> initializer.initialize(game.id(), game.mapTemplateId(), game.rulesetVersion()));
		game.start(clock.instant());
		return view(game, members, me, map);
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
		Map<UUID, String> names = playerDirectory.displayNames(members.stream().map(GamePlayer::playerId).toList());
		return LobbyView.of(game, members, names, me.id(), map);
	}

}
