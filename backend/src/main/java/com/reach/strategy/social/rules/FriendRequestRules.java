package com.reach.strategy.social.rules;

/**
 * The friend-request state machine (Phase 12B, docs/DATA_MODEL.md §3.4):
 *
 * <pre>
 * PENDING ──accept (recipient)──▶ ACCEPTED
 * PENDING ──decline (recipient)─▶ DECLINED
 * PENDING ──cancel (sender)─────▶ CANCELLED   (also when either player blocks the other)
 * </pre>
 *
 * Every other combination is refused. Repeating the action that produced the current state is
 * accepted as a no-op, so a retried request after a lost response succeeds.
 */
public final class FriendRequestRules {

	private FriendRequestRules() {
	}

	public enum Status {
		PENDING, ACCEPTED, DECLINED, CANCELLED
	}

	public enum Action {
		ACCEPT, DECLINE, CANCEL
	}

	public enum Role {
		SENDER, RECIPIENT, OTHER
	}

	/**
	 * @param to the resulting status
	 * @param changed false when the request was already in that state (idempotent repeat)
	 * @param refusal NOT_FOUND (the actor is not allowed to see or act on it) or NOT_PENDING, or null
	 */
	public record Decision(Status to, boolean changed, String refusal) {

		public boolean allowed() {
			return refusal == null;
		}

	}

	public static Decision decide(Status current, Action action, Role role) {
		Role allowedRole = action == Action.CANCEL ? Role.SENDER : Role.RECIPIENT;
		if (role != allowedRole) {
			// Not the party who may do this: indistinguishable from a request that does not exist.
			return new Decision(current, false, "NOT_FOUND");
		}
		Status target = switch (action) {
			case ACCEPT -> Status.ACCEPTED;
			case DECLINE -> Status.DECLINED;
			case CANCEL -> Status.CANCELLED;
		};
		if (current == Status.PENDING) {
			return new Decision(target, true, null);
		}
		if (current == target) {
			return new Decision(current, false, null);
		}
		return new Decision(current, false, "NOT_PENDING");
	}

}
