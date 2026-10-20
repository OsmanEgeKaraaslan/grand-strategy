package com.reach.strategy.game.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.world.api.WorldApi;

/**
 * A lobby as seen by one of its members. Only ever built for members, because it includes the
 * invitation code. Participants are identified by seat and display name, not internal ids.
 */
public record LobbyView(UUID id, String name, GameStatus status, UUID mapId, String mapName, int maxPlayers,
		int playerCount, int availableSlots, String inviteCode, int yourSeat, boolean youAreHost,
		List<Participant> participants, List<NationChoice> nations, boolean canStart, long stateVersion,
		Instant createdAt) {

	public record Participant(int seat, String displayName, boolean host, boolean you, UUID nationId, Instant joinedAt) {
	}

	/** A selectable nation; {@code chosenBySeat} is null while nobody has picked it. */
	/** @param tier starting profile; @param playable whether a human may choose it (others become NPC nations) */
	public record NationChoice(UUID id, String key, String name, String color, int provinceCount, Integer chosenBySeat,
			boolean chosenByYou, String tier, boolean playable) {
	}

	static LobbyView of(Game game, List<GamePlayer> members, Map<UUID, String> names, UUID myPlayerId,
			WorldApi.MapSummary map) {
		GamePlayer mine = members.stream().filter(member -> member.playerId().equals(myPlayerId)).findFirst().orElseThrow();
		List<Participant> participants = members.stream()
			.map(member -> new Participant(member.seatOrder(), names.getOrDefault(member.playerId(), "Unknown player"),
					game.isHost(member.playerId()), member.playerId().equals(myPlayerId), member.nationTemplateId(),
					member.joinedAt()))
			.toList();
		List<NationChoice> nations = map.nations().stream().map(nation -> {
			GamePlayer chooser = members.stream()
				.filter(member -> nation.id().equals(member.nationTemplateId()))
				.findFirst()
				.orElse(null);
			return new NationChoice(nation.id(), nation.key(), nation.name(), nation.color(), nation.provinceCount(),
					chooser == null ? null : chooser.seatOrder(), chooser != null && chooser == mine, nation.tier(),
					nation.playable());
		}).toList();
		return new LobbyView(game.id(), game.name(), game.status(), map.id(), map.name(), game.maxPlayers(),
				game.playerCount(), game.availableSlots(), game.inviteCode().formatted(), mine.seatOrder(),
				game.isHost(myPlayerId), participants, nations,
				game.isHost(myPlayerId) && startBlocker(game, members, map) == null, game.stateVersion(), game.createdAt());
	}

	/** Why the game cannot start yet, or null if it can. Shared by the view and the start command. */
	static StartBlocker startBlocker(Game game, List<GamePlayer> members, WorldApi.MapSummary map) {
		if (game.status() != GameStatus.READY) {
			return StartBlocker.NOT_READY;
		}
		Set<UUID> chosen = members.stream()
			.map(GamePlayer::nationTemplateId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		Set<UUID> playable = map.nations()
			.stream()
			.filter(WorldApi.NationOption::playable)
			.map(WorldApi.NationOption::id)
			.collect(Collectors.toSet());
		// Every player needs a distinct playable nation; nations nobody chose are NPC-controlled (Phase 10).
		if (chosen.size() != members.size() || !playable.containsAll(chosen)) {
			return StartBlocker.NATIONS_NOT_SELECTED;
		}
		return null;
	}

	enum StartBlocker {

		NOT_READY, NATIONS_NOT_SELECTED

	}

}
