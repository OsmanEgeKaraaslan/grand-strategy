package com.reach.strategy.game.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PlayerDirectory;
import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.game.domain.InviteCode;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Lobby authorization and membership rules with mocked persistence. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LobbyServiceTest {

	static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

	@Mock
	GameRepository games;

	@Mock
	GamePlayerRepository memberships;

	@Mock
	PlayerDirectory playerDirectory;

	@Mock
	WorldApi world;

	static final UUID MAP = UUID.randomUUID();
	static final WorldApi.NationOption VARN = new WorldApi.NationOption(UUID.randomUUID(), "varn", "Kingdom of Varn", "#3f6fb5", "varnhold", 6, "POWER", true);
	static final WorldApi.NationOption OSTRA = new WorldApi.NationOption(UUID.randomUUID(), "ostra", "Ostran Confederacy", "#b5503a", "ostrava", 6, "POWER", true);

	LobbyService service;

	final CurrentPlayer host = new CurrentPlayer(UUID.randomUUID(), "Host");
	final CurrentPlayer guest = new CurrentPlayer(UUID.randomUUID(), "Guest");
	final CurrentPlayer outsider = new CurrentPlayer(UUID.randomUUID(), "Outsider");

	/** A persisted two-player lobby created by {@link #host}; its members as the "database" holds them. */
	Game game;
	final List<GamePlayer> storedMembers = new ArrayList<>();

	@BeforeEach
	void setUp() {
		service = new LobbyService(games, memberships, playerDirectory, world, List.of(), "v1",
				Clock.fixed(NOW, ZoneOffset.UTC), new org.springframework.beans.factory.support.StaticListableBeanFactory()
					.getBeanProvider(com.reach.strategy.game.api.ComputerOpponents.class),
				org.mockito.Mockito.mock(com.reach.strategy.identity.api.ComputerPlayers.class), event -> { }, 10, new GameCapacity(games, 1000));
		when(world.defaultMapId()).thenReturn(MAP);
		when(world.mapSummary(MAP)).thenReturn(new WorldApi.MapSummary(MAP, "sundered-reach", "The Sundered Reach", 2, List.of(VARN, OSTRA)));
		Game.Created created = Game.create("Twin Shores", host.id(), 2, MAP, "v1", InviteCode.generate(), NOW);
		game = created.game();
		storedMembers.add(created.host());

		when(playerDirectory.displayNames(anyCollection())).thenReturn(Map.of(host.id(), "Host", guest.id(), "Guest"));
		when(games.findById(game.id())).thenReturn(Optional.of(game));
		when(games.findByIdForUpdate(game.id())).thenReturn(Optional.of(game));
		when(games.findByInviteCodeForUpdate(anyString())).thenReturn(Optional.empty());
		when(games.findByInviteCodeForUpdate(game.inviteCode().value())).thenReturn(Optional.of(game));
		when(memberships.findByGameIdOrderBySeatOrder(game.id())).thenAnswer(invocation -> List.copyOf(storedMembers));
		when(memberships.existsByGameIdAndPlayerId(any(), any())).thenAnswer(invocation -> storedMembers.stream()
			.anyMatch(member -> member.playerId().equals(invocation.getArgument(1))));
		when(memberships.save(any(GamePlayer.class))).thenAnswer(invocation -> {
			storedMembers.add(invocation.getArgument(0));
			return invocation.getArgument(0);
		});
	}

	@Test
	void createSeatsTheCreatorAndReturnsTheInviteCode() {
		LobbyView lobby = service.create(host, "  Four Rivers ");

		assertThat(lobby.name()).isEqualTo("Four Rivers");
		assertThat(lobby.status()).isEqualTo(GameStatus.WAITING_FOR_PLAYERS);
		assertThat(lobby.maxPlayers()).isEqualTo(2);
		assertThat(lobby.availableSlots()).isEqualTo(1);
		assertThat(lobby.yourSeat()).isZero();
		assertThat(lobby.youAreHost()).isTrue();
		assertThat(lobby.inviteCode()).matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");
		verify(games).save(any(Game.class));
	}

	@Test
	void joiningWithAValidCodeFillsTheLastSlot() {
		LobbyView lobby = service.join(guest, game.inviteCode().formatted().toLowerCase());

		assertThat(lobby.status()).isEqualTo(GameStatus.READY);
		assertThat(lobby.yourSeat()).isEqualTo(1);
		assertThat(lobby.youAreHost()).isFalse();
		assertThat(lobby.participants()).extracting(LobbyView.Participant::displayName).containsExactly("Host", "Guest");
	}

	@Test
	void joiningAgainCreatesNoDuplicateMembership() {
		service.join(guest, game.inviteCode().value());
		LobbyView again = service.join(guest, game.inviteCode().value());

		assertThat(again.playerCount()).isEqualTo(2);
		assertThat(storedMembers).hasSize(2);
	}

	@Test
	void theCreatorJoiningTheirOwnGameIsANoOp() {
		service.join(host, game.inviteCode().value());

		verify(memberships, never()).save(any());
		assertThat(game.playerCount()).isEqualTo(1);
	}

	@Test
	void aFullGameRejectsNewPlayersWithConflict() {
		service.join(guest, game.inviteCode().value());

		assertThatThrownBy(() -> service.join(outsider, game.inviteCode().value()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("GAME_FULL"));
		assertThat(storedMembers).extracting(GamePlayer::playerId).containsExactly(host.id(), guest.id());
	}

	@Test
	void malformedInviteCodesAreRejectedWithoutTouchingTheDatabase() {
		assertThatThrownBy(() -> service.join(guest, "not a code"))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("INVITE_CODE_NOT_FOUND"));
		verifyNoInteractions(games);
	}

	@Test
	void unknownInviteCodesAreNotFound() {
		assertThatThrownBy(() -> service.join(guest, InviteCode.generate().value()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("INVITE_CODE_NOT_FOUND"));
	}

	@Test
	void nonMembersCannotSeeALobby() {
		assertThatThrownBy(() -> service.lobby(outsider, game.id()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("GAME_NOT_FOUND"));
	}

	@Test
	void unknownGamesLookExactlyLikeForeignGames() {
		assertThatThrownBy(() -> service.lobby(host, UUID.randomUUID()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("GAME_NOT_FOUND"));
	}

	@Test
	void membersSeeTheLobbyIncludingTheInviteCode() {
		LobbyView lobby = service.lobby(host, game.id());

		assertThat(lobby.inviteCode()).isEqualTo(game.inviteCode().formatted());
		assertThat(lobby.participants()).singleElement().satisfies(participant -> {
			assertThat(participant.displayName()).isEqualTo("Host");
			assertThat(participant.host()).isTrue();
			assertThat(participant.you()).isTrue();
			assertThat(participant.seat()).isZero();
		});
	}


	// --- nation choice and start -----------------------------------------------------------

	@Test
	void lobbyListsTheMapsNationsAndWhoChoseThem() {
		service.chooseNation(host, game.id(), VARN.id());

		LobbyView lobby = service.lobby(host, game.id());

		assertThat(lobby.mapName()).isEqualTo("The Sundered Reach");
		assertThat(lobby.nations()).extracting(LobbyView.NationChoice::key).containsExactly("varn", "ostra");
		assertThat(lobby.nations().getFirst().chosenBySeat()).isZero();
		assertThat(lobby.nations().getFirst().chosenByYou()).isTrue();
		assertThat(lobby.nations().get(1).chosenBySeat()).isNull();
		assertThat(lobby.participants().getFirst().nationId()).isEqualTo(VARN.id());
	}

	@Test
	void aNationCanBeChosenByOnlyOnePlayer() {
		service.join(guest, game.inviteCode().value());
		service.chooseNation(host, game.id(), VARN.id());

		assertThatThrownBy(() -> service.chooseNation(guest, game.id(), VARN.id()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("NATION_TAKEN"));
	}

	@Test
	void playersMayChangeTheirChoiceBeforeTheStart() {
		service.chooseNation(host, game.id(), VARN.id());
		LobbyView lobby = service.chooseNation(host, game.id(), OSTRA.id());

		assertThat(lobby.participants().getFirst().nationId()).isEqualTo(OSTRA.id());
		assertThat(lobby.nations().getFirst().chosenBySeat()).isNull();
	}

	@Test
	void unknownNationsAndOutsidersAreRejected() {
		assertThatThrownBy(() -> service.chooseNation(host, game.id(), UUID.randomUUID()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("NATION_NOT_FOUND"));
		assertThatThrownBy(() -> service.chooseNation(outsider, game.id(), VARN.id()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("GAME_NOT_FOUND"));
	}

	@Test
	void theHostStartsAReadyGameWithDistinctNations() {
		readyWithNations();

		LobbyView started = service.start(host, game.id());

		assertThat(started.status()).isEqualTo(GameStatus.IN_PROGRESS);
		assertThat(game.activeSeat()).isZero();
		verify(world).instantiate(eq(game.id()), eq(MAP), eq(Map.of(VARN.id(), storedMembers.get(0).id(), OSTRA.id(),
				storedMembers.get(1).id())), eq(java.util.Set.of()));
	}

	@Test
	void startingTwiceDoesNotCreateASecondWorld() {
		readyWithNations();
		service.start(host, game.id());

		LobbyView again = service.start(host, game.id());

		assertThat(again.status()).isEqualTo(GameStatus.IN_PROGRESS);
		verify(world, times(1)).instantiate(any(), any(), any(), any());
		assertThat(game.turnNumber()).isEqualTo(1);
	}

	@Test
	void onlyTheHostMayStart() {
		readyWithNations();

		assertThatThrownBy(() -> service.start(guest, game.id()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("NOT_HOST"));
		assertThatThrownBy(() -> service.start(outsider, game.id()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("GAME_NOT_FOUND"));
		verify(world, never()).instantiate(any(), any(), any(), any());
	}

	@Test
	void aGameStartsOnlyWhenFullAndEveryoneHasADistinctNation() {
		assertThatThrownBy(() -> service.start(host, game.id()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("GAME_NOT_READY"));

		service.join(guest, game.inviteCode().value());
		service.chooseNation(host, game.id(), VARN.id());
		assertThat(service.lobby(host, game.id()).canStart()).isFalse();
		assertThatThrownBy(() -> service.start(host, game.id()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("NATIONS_NOT_SELECTED"));

		service.chooseNation(guest, game.id(), OSTRA.id());
		assertThat(service.lobby(host, game.id()).canStart()).isTrue();
		assertThat(service.lobby(guest, game.id()).canStart()).as("only the host can start").isFalse();
	}

	@Test
	void nationChoicesAreLockedOnceStarted() {
		readyWithNations();
		service.start(host, game.id());

		assertThatThrownBy(() -> service.chooseNation(host, game.id(), OSTRA.id()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("GAME_ALREADY_STARTED"));
	}

	private void readyWithNations() {
		service.join(guest, game.inviteCode().value());
		service.chooseNation(host, game.id(), VARN.id());
		service.chooseNation(guest, game.id(), OSTRA.id());
	}

}
