package com.reach.strategy.social.application;

import java.time.Instant;
import java.util.UUID;

import com.reach.strategy.identity.api.AccountSuspensionParticipant;
import com.reach.strategy.social.domain.InvitationStore;
import com.reach.strategy.social.domain.TicketStore;

import org.springframework.stereotype.Component;

/**
 * Account suspension (Q-MOD1): a waiting Quick Match search and pending invitations (sent and
 * received) are cancelled, so nobody waits for the suspended player. Friendships stay, for a
 * possible reinstatement.
 */
@Component
class SocialAccountSuspension implements AccountSuspensionParticipant {

	private final InvitationStore invitations;
	private final TicketStore tickets;

	SocialAccountSuspension(InvitationStore invitations, TicketStore tickets) {
		this.invitations = invitations;
		this.tickets = tickets;
	}

	@Override
	public void accountSuspended(UUID playerId, Instant now) {
		tickets.cancelWaitingOf(playerId, now);
		invitations.cancelAllPendingOf(playerId, "ACCOUNT_SUSPENDED", now);
	}

}
