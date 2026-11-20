package com.reach.strategy.diplomacy.api;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;

/**
 * Diplomatic commands and the diplomacy read model for the computer opponent (Phase 11). Alliance
 * kinds are their names (NORMAL, DEFENSIVE); conference kinds GENERAL or SEPARATE.
 */
public interface DiplomacyActions {

	DiplomacyViews.Overview overview(CurrentPlayer me, UUID gameId);

	/**
	 * Whether any peace conference of the game is requested or running: computer opponents only
	 * negotiate inside conferences, so without one there is nothing for them to look at.
	 */
	boolean anyOpenConference(UUID gameId);

	void declareWar(CurrentPlayer me, UUID gameId, UUID targetNationId, UUID idempotencyKey);

	/** Declares war and invites normal allies to join the attack (ruleset v8). */
	void declareWar(CurrentPlayer me, UUID gameId, UUID targetNationId, java.util.List<UUID> inviteAllies, UUID idempotencyKey);

	void proposeAlliance(CurrentPlayer me, UUID gameId, UUID nationId, String kind, UUID idempotencyKey);

	void acceptAlliance(CurrentPlayer me, UUID gameId, UUID allianceId, UUID idempotencyKey);

	void rejectAlliance(CurrentPlayer me, UUID gameId, UUID allianceId, UUID idempotencyKey);

	void endAlliance(CurrentPlayer me, UUID gameId, UUID allianceId, UUID idempotencyKey);

	void sendGold(CurrentPlayer me, UUID gameId, UUID recipientNationId, long amountMg, UUID idempotencyKey);

	void callToArms(CurrentPlayer me, UUID gameId, UUID warId, UUID allyNationId, UUID idempotencyKey);

	void answerCall(CurrentPlayer me, UUID gameId, UUID callId, boolean honor, UUID idempotencyKey);

	DiplomacyViews.ConferenceResult requestConference(CurrentPlayer me, UUID gameId, UUID warId, String kind,
			UUID idempotencyKey);

	DiplomacyViews.ConferenceResult answerConference(CurrentPlayer me, UUID gameId, UUID conferenceId, boolean accept,
			UUID idempotencyKey);

	DiplomacyViews.ConferenceResult cancelConference(CurrentPlayer me, UUID gameId, UUID conferenceId,
			UUID idempotencyKey);

	DiplomacyViews.ConferenceResult submitDraft(CurrentPlayer me, UUID gameId, UUID conferenceId, int expectedRevision,
			List<DiplomacyViews.Term> terms, UUID idempotencyKey);

	DiplomacyViews.ConferenceResult decideDraft(CurrentPlayer me, UUID gameId, UUID conferenceId, int revision,
			boolean approve, UUID idempotencyKey);

}
