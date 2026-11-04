package com.reach.strategy.identity.api;

import java.util.UUID;

/**
 * Who may moderate (Q-MOD1): the accounts whose Supabase user ids the operator lists in the server
 * configuration ({@code ADMIN_SUPABASE_USER_IDS}). There is no way to become an administrator through
 * the API. Every moderation action is recorded with the administrator who took it.
 */
public interface Administrators {

	boolean isAdmin(UUID playerId);

	/** @throws com.reach.strategy.platform.web.ApiException 403 ADMIN_ONLY unless the player is an administrator */
	void requireAdmin(CurrentPlayer me);

	/** Records a moderation action in the caller's transaction; exactly one of the targets is set. */
	void record(CurrentPlayer admin, Action action, UUID targetPlayerId, UUID targetGameId, String reason);

	enum Action {
		BAN, UNBAN, RESET_NAME, ABANDON_GAME
	}

}
