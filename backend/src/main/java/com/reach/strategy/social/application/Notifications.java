package com.reach.strategy.social.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.reach.strategy.game.api.Lobbies;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PublicProfiles;
import com.reach.strategy.social.domain.InvitationStore;
import com.reach.strategy.social.domain.NotificationStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications (Phases 12B–13). Written inside the transaction of the change they report,
 * so a notification exists exactly when its change committed. Read only by their recipient: the
 * recipient is the authenticated caller, never a parameter.
 */
@Service
public class Notifications {

	enum Kind {
		FRIEND_REQUEST, FRIEND_ACCEPTED, GAME_INVITATION, INVITATION_ACCEPTED, INVITATION_DECLINED, MATCH_FOUND,
		GAME_STARTED, GAME_FINISHED
	}

	private final NotificationStore store;
	private final InvitationStore invitations;
	private final PublicProfiles profiles;
	private final Lobbies lobbies;
	private final Clock clock;
	private final int listLimit;
	private final int keepPerPlayer;

	Notifications(NotificationStore store, InvitationStore invitations, PublicProfiles profiles, Lobbies lobbies, Clock clock,
			@Value("${strategy.social.notification-list-limit:30}") int listLimit,
			@Value("${strategy.social.notifications-kept-per-player:200}") int keepPerPlayer) {
		this.store = store;
		this.invitations = invitations;
		this.profiles = profiles;
		this.lobbies = lobbies;
		this.clock = clock;
		this.listLimit = listLimit;
		this.keepPerPlayer = keepPerPlayer;
	}

	void notify(UUID playerId, Kind kind, UUID actorId, UUID gameId, UUID referenceId, Instant now) {
		store.insert(playerId, kind.name(), actorId, gameId, referenceId, now);
		store.prune(playerId, keepPerPlayer);
	}

	void blocked(UUID blocker, UUID blocked, Instant now) {
		store.deleteFrom(blocked, blocker);
	}

	/**
	 * @param actor the player who caused it (null for game events)
	 * @param gameName the game it concerns, if any and still visible to the recipient
	 */
	public record NotificationView(UUID id, String kind, Actor actor, UUID gameId, String gameName, UUID referenceId,
			Instant createdAt, boolean read) {
	}

	public record Actor(String username, String displayName, String emblem) {
	}

	public record NotificationsView(int unread, List<NotificationView> items) {
	}

	@Transactional(readOnly = true)
	public NotificationsView list(CurrentPlayer me) {
		List<NotificationStore.Row> rows = store.latest(me.id(), listLimit);
		Map<UUID, PublicProfiles.PublicPlayer> actors = profiles
			.byIds(rows.stream().map(NotificationStore.Row::actorId).filter(Objects::nonNull).distinct().toList());
		List<UUID> gameIds = rows.stream().map(NotificationStore.Row::gameId).filter(Objects::nonNull).distinct().toList();
		// Names of games the player is in, or was invited to (an invitation names its lobby).
		Map<UUID, String> gameNames = new java.util.HashMap<>(lobbies.namesFor(me.id(), gameIds));
		for (UUID invited : invitations.gamesInvitedTo(me.id(), gameIds)) {
			if (!gameNames.containsKey(invited)) {
				lobbies.facts(invited).ifPresent(f -> gameNames.put(invited, f.name()));
			}
		}
		List<NotificationView> items = new ArrayList<>();
		for (NotificationStore.Row row : rows) {
			PublicProfiles.PublicPlayer actor = row.actorId() == null ? null : actors.get(row.actorId());
			items.add(new NotificationView(row.id(), row.kind(),
					actor == null ? null : new Actor(actor.username(), actor.displayName(), actor.emblem()), row.gameId(),
					row.gameId() == null ? null : gameNames.get(row.gameId()), row.referenceId(), row.createdAt(), row.read()));
		}
		return new NotificationsView(store.unread(me.id()), items);
	}

	/** A cheap poll: only the count. */
	@Transactional(readOnly = true)
	public int unread(CurrentPlayer me) {
		return store.unread(me.id());
	}

	@Transactional
	public NotificationsView markRead(CurrentPlayer me, Collection<UUID> ids) {
		store.markRead(me.id(), ids, clock.instant());
		return list(me);
	}

}
