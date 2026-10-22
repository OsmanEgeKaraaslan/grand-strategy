package com.reach.strategy.social.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.game.api.Lobbies;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PublicProfiles;
import com.reach.strategy.identity.api.PublicProfiles.PublicPlayer;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.social.domain.FriendStore;
import com.reach.strategy.social.domain.InvitationStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inviting friends into a private lobby (Phase 13A). Only the lobby's host invites, only friends,
 * only while the lobby is waiting for players. Invitations reserve no seat: accepting one admits
 * the invitee through the game module under the game row lock, exactly like joining by code, and
 * an invitation that can no longer be honoured becomes INVALIDATED (that outcome is committed even
 * though the caller receives an error).
 */
@Service
public class InvitationService {

	private final InvitationStore store;
	private final FriendStore friends;
	private final Lobbies lobbies;
	private final PublicProfiles profiles;
	private final Notifications notifications;
	private final TransactionTemplate transactions;
	private final Clock clock;
	private final int maxPendingPerLobby;

	InvitationService(InvitationStore store, FriendStore friends, Lobbies lobbies, PublicProfiles profiles,
			Notifications notifications, PlatformTransactionManager transactionManager, Clock clock,
			@Value("${strategy.social.max-pending-invitations-per-lobby:10}") int maxPendingPerLobby) {
		this.store = store;
		this.friends = friends;
		this.lobbies = lobbies;
		this.profiles = profiles;
		this.notifications = notifications;
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.maxPendingPerLobby = maxPendingPerLobby;
	}

	/**
	 * @param joinable whether the lobby can still be joined right now (a hint; acceptance decides)
	 * @param reason why an invitation was invalidated, else null
	 */
	public record InvitationView(UUID id, UUID gameId, String gameName, String mapName, int playerCount, int maxPlayers,
			boolean joinable, Person inviter, Person invitee, String status, String reason, Instant createdAt) {
	}

	public record Person(String username, String displayName, String emblem) {
	}

	@Transactional
	public InvitationView invite(CurrentPlayer me, UUID gameId, String username) {
		Lobbies.LobbyFacts lobby = lobbies.facts(gameId)
			.filter(f -> f.humanMemberIds().contains(me.id()))
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "Game not found."));
		if (!lobby.hostId().equals(me.id())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "NOT_HOST", "Only the host can invite players.");
		}
		if (!lobby.joinable()) {
			throw SocialErrors.conflict("LOBBY_NOT_JOINABLE", "The lobby is full or has started.");
		}
		PublicPlayer invitee = profiles.byUsername(username).orElseThrow(SocialErrors::playerNotFound);
		if (friends.blockedEitherWay(me.id(), invitee.id())) {
			throw FriendService.unavailable();
		}
		if (!friends.areFriends(me.id(), invitee.id())) {
			throw SocialErrors.conflict("NOT_FRIENDS", "You can invite friends only.");
		}
		if (lobby.humanMemberIds().contains(invitee.id())) {
			throw SocialErrors.conflict("ALREADY_IN_GAME", "That player is already in this game.");
		}
		Optional<InvitationStore.Invitation> pending = store.pendingFor(gameId, invitee.id());
		if (pending.isPresent()) {
			return view(pending.get(), lobby, profiles.byIds(List.of(me.id(), invitee.id())));
		}
		if (store.pendingForGame(gameId) >= maxPendingPerLobby) {
			throw SocialErrors.conflict("TOO_MANY_INVITATIONS", "This lobby has too many open invitations.");
		}
		Instant now = clock.instant();
		UUID id = UUID.randomUUID();
		if (!store.insertPending(id, gameId, me.id(), invitee.id(), now)) {
			// A concurrent identical invitation won; return it.
			return view(store.pendingFor(gameId, invitee.id()).orElseThrow(), lobby,
					profiles.byIds(List.of(me.id(), invitee.id())));
		}
		notifications.notify(invitee.id(), Notifications.Kind.GAME_INVITATION, me.id(), gameId, id, now);
		return view(new InvitationStore.Invitation(id, gameId, me.id(), invitee.id(), "PENDING", null, now), lobby,
				profiles.byIds(List.of(me.id(), invitee.id())));
	}

	public record InvitationsView(List<InvitationView> incoming) {
	}

	@Transactional(readOnly = true)
	public InvitationsView incoming(CurrentPlayer me) {
		List<InvitationStore.Invitation> pending = store.pendingTo(me.id(), 30);
		List<InvitationView> views = new ArrayList<>();
		Map<UUID, PublicPlayer> people = profiles.byIds(
				java.util.stream.Stream.concat(pending.stream().map(InvitationStore.Invitation::inviterId), java.util.stream.Stream.of(me.id()))
					.distinct()
					.toList());
		for (InvitationStore.Invitation invitation : pending) {
			lobbies.facts(invitation.gameId()).ifPresent(lobby -> views.add(view(invitation, lobby, people)));
		}
		return new InvitationsView(views);
	}

	/** The host's invitations for one of their lobbies. */
	@Transactional(readOnly = true)
	public List<InvitationView> sent(CurrentPlayer me, UUID gameId) {
		Lobbies.LobbyFacts lobby = lobbies.facts(gameId)
			.filter(f -> f.humanMemberIds().contains(me.id()))
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "Game not found."));
		List<InvitationStore.Invitation> sent = store.sentFor(gameId, me.id());
		Map<UUID, PublicPlayer> people = profiles.byIds(java.util.stream.Stream
			.concat(sent.stream().map(InvitationStore.Invitation::inviteeId), java.util.stream.Stream.of(me.id()))
			.distinct()
			.toList());
		return sent.stream().map(i -> view(i, lobby, people)).toList();
	}

	/**
	 * Joins the invited lobby. Outcomes that make the invitation unusable are committed as
	 * INVALIDATED before the error is returned, so a retry sees a consistent state; accepting again
	 * after a lost response simply returns the joined lobby.
	 */
	public InvitationView accept(CurrentPlayer me, UUID invitationId) {
		Accepted result = transactions.execute(status -> acceptInTransaction(me, invitationId));
		if (result.error() != null) {
			throw result.error();
		}
		return result.view();
	}

	private record Accepted(InvitationView view, ApiException error) {
	}

	private Accepted acceptInTransaction(CurrentPlayer me, UUID invitationId) {
		InvitationStore.Invitation invitation = store.findForUpdate(invitationId)
			.filter(i -> i.inviteeId().equals(me.id()))
			.orElseThrow(() -> SocialErrors.notFound("INVITATION"));
		Instant now = clock.instant();
		if ("ACCEPTED".equals(invitation.status())) {
			return new Accepted(currentView(invitation), null);
		}
		if (!"PENDING".equals(invitation.status())) {
			throw SocialErrors.conflict("INVITATION_NOT_PENDING", "This invitation is no longer open.");
		}
		if (friends.blockedEitherWay(invitation.inviterId(), me.id())) {
			store.resolve(invitation.id(), "INVALIDATED", "BLOCKED", now);
			return new Accepted(null, FriendService.unavailable());
		}
		Lobbies.AdmitOutcome outcome = lobbies.admitInvited(invitation.gameId(), invitation.inviterId(), me.id());
		return switch (outcome) {
			case ADMITTED, ALREADY_MEMBER -> {
				store.resolve(invitation.id(), "ACCEPTED", null, now);
				notifications.notify(invitation.inviterId(), Notifications.Kind.INVITATION_ACCEPTED, me.id(),
						invitation.gameId(), invitation.id(), now);
				yield new Accepted(currentView(new InvitationStore.Invitation(invitation.id(), invitation.gameId(),
						invitation.inviterId(), invitation.inviteeId(), "ACCEPTED", null, invitation.createdAt())), null);
			}
			case GAME_FULL -> invalidated(invitation, outcome, now, "GAME_FULL", "The lobby is already full.");
			case ALREADY_STARTED -> invalidated(invitation, outcome, now, "GAME_ALREADY_STARTED", "The game has already started.");
			case INVITER_NOT_HOST -> invalidated(invitation, outcome, now, "INVITATION_INVALID",
					"The player who invited you no longer hosts this lobby.");
			case NOT_FOUND -> invalidated(invitation, outcome, now, "INVITATION_INVALID", "The lobby no longer exists.");
		};
	}

	private Accepted invalidated(InvitationStore.Invitation invitation, Lobbies.AdmitOutcome outcome, Instant now,
			String code, String detail) {
		store.resolve(invitation.id(), "INVALIDATED", outcome.name(), now);
		return new Accepted(null, SocialErrors.conflict(code, detail));
	}

	@Transactional
	public InvitationView decline(CurrentPlayer me, UUID invitationId) {
		InvitationStore.Invitation invitation = store.findForUpdate(invitationId)
			.filter(i -> i.inviteeId().equals(me.id()))
			.orElseThrow(() -> SocialErrors.notFound("INVITATION"));
		return answer(invitation, "DECLINED", true);
	}

	@Transactional
	public InvitationView cancel(CurrentPlayer me, UUID invitationId) {
		InvitationStore.Invitation invitation = store.findForUpdate(invitationId)
			.filter(i -> i.inviterId().equals(me.id()))
			.orElseThrow(() -> SocialErrors.notFound("INVITATION"));
		return answer(invitation, "CANCELLED", false);
	}

	private InvitationView answer(InvitationStore.Invitation invitation, String to, boolean notifyInviter) {
		if (invitation.status().equals(to)) {
			return currentView(invitation);
		}
		if (!"PENDING".equals(invitation.status())) {
			throw SocialErrors.conflict("INVITATION_NOT_PENDING", "This invitation is no longer open.");
		}
		Instant now = clock.instant();
		store.resolve(invitation.id(), to, null, now);
		if (notifyInviter) {
			notifications.notify(invitation.inviterId(), Notifications.Kind.INVITATION_DECLINED, invitation.inviteeId(),
					invitation.gameId(), invitation.id(), now);
		}
		return currentView(new InvitationStore.Invitation(invitation.id(), invitation.gameId(), invitation.inviterId(),
				invitation.inviteeId(), to, null, invitation.createdAt()));
	}

	private InvitationView currentView(InvitationStore.Invitation invitation) {
		Lobbies.LobbyFacts lobby = lobbies.facts(invitation.gameId()).orElseThrow(() -> SocialErrors.notFound("INVITATION"));
		return view(invitation, lobby, profiles.byIds(List.of(invitation.inviterId(), invitation.inviteeId())));
	}

	private static InvitationView view(InvitationStore.Invitation i, Lobbies.LobbyFacts lobby, Map<UUID, PublicPlayer> people) {
		return new InvitationView(i.id(), i.gameId(), lobby.name(), lobby.mapName(), lobby.playerCount(), lobby.maxPlayers(),
				lobby.joinable(), person(people.get(i.inviterId())), person(people.get(i.inviteeId())), i.status(), i.reason(),
				i.createdAt());
	}

	private static Person person(PublicPlayer p) {
		return p == null ? null : new Person(p.username(), p.displayName(), p.emblem());
	}

}
