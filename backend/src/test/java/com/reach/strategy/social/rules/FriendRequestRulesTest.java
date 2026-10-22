package com.reach.strategy.social.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.reach.strategy.social.rules.FriendRequestRules.Action;
import com.reach.strategy.social.rules.FriendRequestRules.Decision;
import com.reach.strategy.social.rules.FriendRequestRules.Role;
import com.reach.strategy.social.rules.FriendRequestRules.Status;
import org.junit.jupiter.api.Test;

class FriendRequestRulesTest {

	@Test
	void theRecipientAcceptsOrDeclinesAndTheSenderCancelsAPendingRequest() {
		assertThat(FriendRequestRules.decide(Status.PENDING, Action.ACCEPT, Role.RECIPIENT))
			.isEqualTo(new Decision(Status.ACCEPTED, true, null));
		assertThat(FriendRequestRules.decide(Status.PENDING, Action.DECLINE, Role.RECIPIENT))
			.isEqualTo(new Decision(Status.DECLINED, true, null));
		assertThat(FriendRequestRules.decide(Status.PENDING, Action.CANCEL, Role.SENDER))
			.isEqualTo(new Decision(Status.CANCELLED, true, null));
	}

	@Test
	void nobodyElseMayActAndTheWrongPartyLooksLikeNotFound() {
		assertThat(FriendRequestRules.decide(Status.PENDING, Action.ACCEPT, Role.SENDER).refusal()).isEqualTo("NOT_FOUND");
		assertThat(FriendRequestRules.decide(Status.PENDING, Action.CANCEL, Role.RECIPIENT).refusal()).isEqualTo("NOT_FOUND");
		assertThat(FriendRequestRules.decide(Status.PENDING, Action.DECLINE, Role.OTHER).refusal()).isEqualTo("NOT_FOUND");
	}

	@Test
	void repeatingTheSameAnswerIsANoOpButChangingItIsRefused() {
		assertThat(FriendRequestRules.decide(Status.ACCEPTED, Action.ACCEPT, Role.RECIPIENT))
			.isEqualTo(new Decision(Status.ACCEPTED, false, null));
		assertThat(FriendRequestRules.decide(Status.ACCEPTED, Action.DECLINE, Role.RECIPIENT).refusal())
			.isEqualTo("NOT_PENDING");
		assertThat(FriendRequestRules.decide(Status.CANCELLED, Action.ACCEPT, Role.RECIPIENT).refusal())
			.isEqualTo("NOT_PENDING");
		assertThat(FriendRequestRules.decide(Status.DECLINED, Action.CANCEL, Role.SENDER).refusal())
			.isEqualTo("NOT_PENDING");
	}

}
