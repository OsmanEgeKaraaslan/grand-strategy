package com.reach.strategy.social.application;

import java.time.Instant;
import java.util.UUID;

import com.reach.strategy.identity.api.AccountDeletionParticipant;
import com.reach.strategy.social.domain.FriendStore;
import com.reach.strategy.social.domain.InvitationStore;
import com.reach.strategy.social.domain.NotificationStore;
import com.reach.strategy.social.domain.TicketStore;

import org.springframework.stereotype.Component;

/**
 * Account deletion (Phase 15A) for the social graph: friendships, friend requests, blocks and
 * notifications of the player are removed, pending invitations and a waiting Quick Match search are
 * cancelled. Answered invitations and matched tickets stay, because they belong to games.
 */
@Component
class SocialAccountDeletion implements AccountDeletionParticipant {

	private final FriendStore friends;
	private final NotificationStore notifications;
	private final InvitationStore invitations;
	private final TicketStore tickets;

	SocialAccountDeletion(FriendStore friends, NotificationStore notifications, InvitationStore invitations,
			TicketStore tickets) {
		this.friends = friends;
		this.notifications = notifications;
		this.invitations = invitations;
		this.tickets = tickets;
	}

	@Override
	public void accountDeleted(UUID playerId, Instant now) {
		tickets.cancelWaitingOf(playerId, now);
		invitations.cancelAllPendingOf(playerId, "ACCOUNT_DELETED", now);
		notifications.purge(playerId);
		friends.purge(playerId);
	}

}
