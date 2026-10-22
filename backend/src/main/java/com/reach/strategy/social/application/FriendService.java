package com.reach.strategy.social.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PublicProfiles;
import com.reach.strategy.identity.api.PublicProfiles.PublicPlayer;
import com.reach.strategy.social.domain.FriendStore;
import com.reach.strategy.social.domain.InvitationStore;
import com.reach.strategy.social.rules.FriendRequestRules;
import com.reach.strategy.social.rules.FriendRequestRules.Action;
import com.reach.strategy.social.rules.FriendRequestRules.Role;
import com.reach.strategy.social.rules.FriendRequestRules.Status;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Player search, friend requests, friendships and blocks (Phase 12B). The acting player is always
 * the authenticated caller; targets are addressed by username. Every transition locks the request
 * row, and the database guarantees one pending request per pair and one friendship per pair, so
 * crossed or repeated requests can never produce duplicates.
 */
@Service
public class FriendService {

	private final FriendStore store;
	private final InvitationStore invitations;
	private final PublicProfiles profiles;
	private final Relationships relationships;
	private final Notifications notifications;
	private final Clock clock;
	private final int searchLimit;
	private final int maxPendingRequests;
	private final int maxFriends;

	FriendService(FriendStore store, InvitationStore invitations, PublicProfiles profiles, Relationships relationships, Notifications notifications,
			Clock clock, @Value("${strategy.social.search-limit:10}") int searchLimit,
			@Value("${strategy.social.max-pending-friend-requests:50}") int maxPendingRequests,
			@Value("${strategy.social.max-friends:200}") int maxFriends) {
		this.store = store;
		this.invitations = invitations;
		this.profiles = profiles;
		this.relationships = relationships;
		this.notifications = notifications;
		this.clock = clock;
		this.searchLimit = searchLimit;
		this.maxPendingRequests = maxPendingRequests;
		this.maxFriends = maxFriends;
	}

	public record PlayerCard(String username, String displayName, String emblem, String relation) {
	}

	public record FriendView(String username, String displayName, String emblem, Instant since) {
	}

	public record RequestView(UUID id, String username, String displayName, String emblem, Instant createdAt,
			String status) {
	}

	public record FriendsView(List<FriendView> friends, List<RequestView> incoming, List<RequestView> outgoing,
			List<PlayerCard> blocked) {
	}

	/** Exact match first, then a bounded prefix search; never emails or ids. */
	@Transactional(readOnly = true)
	public List<PlayerCard> search(CurrentPlayer me, String query) {
		List<PublicPlayer> found = profiles.search(query, searchLimit);
		Map<UUID, Relationships.Relation> relations = relationships.of(me.id(), found.stream().map(PublicPlayer::id).toList());
		return found.stream().map(p -> card(p, relations.get(p.id()))).toList();
	}

	@Transactional(readOnly = true)
	public FriendsView overview(CurrentPlayer me) {
		List<FriendStore.Friend> friends = store.friendsOf(me.id());
		List<FriendStore.Request> pending = store.pendingFor(me.id());
		List<UUID> blocked = store.blockedBy(me.id());
		List<UUID> ids = new ArrayList<>();
		friends.forEach(f -> ids.add(f.playerId()));
		pending.forEach(r -> ids.add(r.senderId().equals(me.id()) ? r.recipientId() : r.senderId()));
		ids.addAll(blocked);
		Map<UUID, PublicPlayer> people = profiles.byIds(ids);
		List<FriendView> friendViews = friends.stream()
			.filter(f -> people.containsKey(f.playerId()))
			.map(f -> {
				PublicPlayer p = people.get(f.playerId());
				return new FriendView(p.username(), p.displayName(), p.emblem(), f.since());
			})
			.sorted(java.util.Comparator.comparing(FriendView::username))
			.toList();
		List<RequestView> incoming = pending.stream()
			.filter(r -> r.recipientId().equals(me.id()))
			.map(r -> requestView(r, people.get(r.senderId())))
			.toList();
		List<RequestView> outgoing = pending.stream()
			.filter(r -> r.senderId().equals(me.id()))
			.map(r -> requestView(r, people.get(r.recipientId())))
			.toList();
		List<PlayerCard> blockedCards = blocked.stream()
			.filter(people::containsKey)
			.map(id -> card(people.get(id), Relationships.Relation.BLOCKED))
			.toList();
		return new FriendsView(friendViews, incoming, outgoing, blockedCards);
	}

	/**
	 * Sends a request — or, if the other player has already asked the caller, accepts theirs (both
	 * want the friendship). Repeating a request returns the pending one.
	 */
	@Transactional
	public RequestView request(CurrentPlayer me, String username) {
		PublicPlayer target = target(me, username);
		Instant now = clock.instant();
		if (store.areFriends(me.id(), target.id())) {
			throw SocialErrors.conflict("ALREADY_FRIENDS", "You are already friends.");
		}
		var existing = store.pendingBetweenForUpdate(me.id(), target.id());
		if (existing.isPresent()) {
			return existing.get().senderId().equals(me.id()) ? requestView(existing.get(), target)
					: acceptLocked(me, existing.get(), target, now);
		}
		if (store.pendingSentBy(me.id()) >= maxPendingRequests) {
			throw SocialErrors.conflict("TOO_MANY_PENDING_REQUESTS",
					"You have too many unanswered friend requests; cancel some first.");
		}
		if (store.friendCount(me.id()) >= maxFriends) {
			throw SocialErrors.conflict("TOO_MANY_FRIENDS", "Your friend list is full.");
		}
		UUID id = UUID.randomUUID();
		if (!store.insertPending(id, me.id(), target.id(), now)) {
			// A crossed request committed in the meantime: the pending one is theirs (or ours, on a retry).
			FriendStore.Request winner = store.pendingBetweenForUpdate(me.id(), target.id())
				.orElseThrow(() -> SocialErrors.conflict("REQUEST_CONFLICT", "Please try again."));
			return winner.senderId().equals(me.id()) ? requestView(winner, target) : acceptLocked(me, winner, target, now);
		}
		notifications.notify(target.id(), Notifications.Kind.FRIEND_REQUEST, me.id(), null, id, now);
		return new RequestView(id, target.username(), target.displayName(), target.emblem(), now, Status.PENDING.name());
	}

	@Transactional
	public RequestView accept(CurrentPlayer me, UUID requestId) {
		return transition(me, requestId, Action.ACCEPT);
	}

	@Transactional
	public RequestView decline(CurrentPlayer me, UUID requestId) {
		return transition(me, requestId, Action.DECLINE);
	}

	@Transactional
	public RequestView cancel(CurrentPlayer me, UUID requestId) {
		return transition(me, requestId, Action.CANCEL);
	}

	/** Either friend may end the friendship; ending one that does not exist is a no-op. */
	@Transactional
	public void unfriend(CurrentPlayer me, String username) {
		PublicPlayer other = profiles.byUsername(username).orElseThrow(SocialErrors::playerNotFound);
		store.unfriend(me.id(), other.id());
	}

	/** Blocking ends the friendship and every pending request and invitation between the two players. */
	@Transactional
	public PlayerCard block(CurrentPlayer me, String username) {
		PublicPlayer other = profiles.byUsername(username).orElseThrow(SocialErrors::playerNotFound);
		if (other.id().equals(me.id())) {
			throw SocialErrors.unprocessable("CANNOT_BLOCK_SELF", "You cannot block yourself.");
		}
		Instant now = clock.instant();
		store.block(me.id(), other.id(), now);
		store.unfriend(me.id(), other.id());
		store.cancelPendingBetween(me.id(), other.id(), now);
		invitations.cancelBetween(me.id(), other.id(), now);
		notifications.blocked(me.id(), other.id(), now);
		return card(other, Relationships.Relation.BLOCKED);
	}

	@Transactional
	public void unblock(CurrentPlayer me, String username) {
		PublicPlayer other = profiles.byUsername(username).orElseThrow(SocialErrors::playerNotFound);
		store.unblock(me.id(), other.id());
	}

	private RequestView transition(CurrentPlayer me, UUID requestId, Action action) {
		FriendStore.Request request = store.findForUpdate(requestId)
			.orElseThrow(() -> SocialErrors.notFound("FRIEND_REQUEST"));
		Role role = request.senderId().equals(me.id()) ? Role.SENDER
				: request.recipientId().equals(me.id()) ? Role.RECIPIENT : Role.OTHER;
		FriendRequestRules.Decision decision = FriendRequestRules.decide(request.status(), action, role);
		if ("NOT_FOUND".equals(decision.refusal())) {
			throw SocialErrors.notFound("FRIEND_REQUEST");
		}
		if (!decision.allowed()) {
			throw SocialErrors.conflict("REQUEST_NOT_PENDING", "This request has already been answered or withdrawn.");
		}
		UUID otherId = role == Role.SENDER ? request.recipientId() : request.senderId();
		PublicPlayer other = profiles.byIds(List.of(otherId)).get(otherId);
		Instant now = clock.instant();
		if (decision.changed()) {
			if (action == Action.ACCEPT) {
				return acceptLocked(me, request, other, now);
			}
			store.setStatus(request.id(), decision.to(), now);
		}
		return new RequestView(request.id(), other.username(), other.displayName(), other.emblem(), request.createdAt(),
				decision.to().name());
	}

	/** Accepts a pending request already locked by the caller's transaction. */
	private RequestView acceptLocked(CurrentPlayer me, FriendStore.Request request, PublicPlayer sender, Instant now) {
		if (store.blockedEitherWay(request.senderId(), request.recipientId())) {
			throw unavailable();
		}
		store.setStatus(request.id(), Status.ACCEPTED, now);
		store.befriend(request.senderId(), request.recipientId(), now);
		notifications.notify(request.senderId(), Notifications.Kind.FRIEND_ACCEPTED, me.id(), null, request.id(), now);
		return new RequestView(request.id(), sender.username(), sender.displayName(), sender.emblem(), request.createdAt(),
				Status.ACCEPTED.name());
	}

	/** The addressed player, if the caller may contact them. */
	private PublicPlayer target(CurrentPlayer me, String username) {
		PublicPlayer target = profiles.byUsername(username).orElseThrow(SocialErrors::playerNotFound);
		if (target.id().equals(me.id())) {
			throw SocialErrors.unprocessable("CANNOT_FRIEND_SELF", "You cannot send a friend request to yourself.");
		}
		if (store.blockedEitherWay(me.id(), target.id())) {
			throw unavailable();
		}
		return target;
	}

	static com.reach.strategy.platform.web.ApiException unavailable() {
		return SocialErrors.conflict("PLAYER_UNAVAILABLE", "You cannot contact this player.");
	}

	private static PlayerCard card(PublicPlayer p, Relationships.Relation relation) {
		return new PlayerCard(p.username(), p.displayName(), p.emblem(), relation.name());
	}

	private static RequestView requestView(FriendStore.Request r, PublicPlayer other) {
		return new RequestView(r.id(), other.username(), other.displayName(), other.emblem(), r.createdAt(), r.status().name());
	}

}
