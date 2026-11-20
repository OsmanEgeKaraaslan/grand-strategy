package com.reach.strategy.diplomacy.application;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.diplomacy.api.DiplomacyActions;
import com.reach.strategy.diplomacy.api.DiplomacyViews;
import com.reach.strategy.diplomacy.rules.Diplomacy;
import com.reach.strategy.identity.api.CurrentPlayer;

import org.springframework.stereotype.Component;

@Component
class DiplomacyActionsAdapter implements DiplomacyActions {

	private final DiplomacyQueries queries;
	private final DiplomacyService diplomacy;
	private final GiftService gifts;
	private final CallToArmsService calls;
	private final ConferenceService conferences;
	private final com.reach.strategy.diplomacy.domain.ConferenceRepository conferenceRows;

	DiplomacyActionsAdapter(DiplomacyQueries queries, DiplomacyService diplomacy, GiftService gifts,
			CallToArmsService calls, ConferenceService conferences,
			com.reach.strategy.diplomacy.domain.ConferenceRepository conferenceRows) {
		this.queries = queries;
		this.diplomacy = diplomacy;
		this.gifts = gifts;
		this.calls = calls;
		this.conferences = conferences;
		this.conferenceRows = conferenceRows;
	}

	@Override
	public DiplomacyViews.Overview overview(CurrentPlayer me, UUID gameId) {
		return queries.overview(me, gameId);
	}

	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public boolean anyOpenConference(UUID gameId) {
		return conferenceRows.anyOpen(gameId);
	}

	@Override
	public void declareWar(CurrentPlayer me, UUID gameId, UUID targetNationId, UUID idempotencyKey) {
		diplomacy.declareWar(me, gameId, targetNationId, idempotencyKey);
	}

	@Override
	public void declareWar(CurrentPlayer me, UUID gameId, UUID targetNationId, java.util.List<UUID> inviteAllies,
			UUID idempotencyKey) {
		diplomacy.declareWar(me, gameId, targetNationId, inviteAllies, idempotencyKey);
	}

	@Override
	public void proposeAlliance(CurrentPlayer me, UUID gameId, UUID nationId, String kind, UUID idempotencyKey) {
		diplomacy.proposeAlliance(me, gameId, nationId, Diplomacy.AllianceKind.valueOf(kind), idempotencyKey);
	}

	@Override
	public void acceptAlliance(CurrentPlayer me, UUID gameId, UUID allianceId, UUID idempotencyKey) {
		diplomacy.acceptAlliance(me, gameId, allianceId, idempotencyKey);
	}

	@Override
	public void rejectAlliance(CurrentPlayer me, UUID gameId, UUID allianceId, UUID idempotencyKey) {
		diplomacy.rejectAlliance(me, gameId, allianceId, idempotencyKey);
	}

	@Override
	public void endAlliance(CurrentPlayer me, UUID gameId, UUID allianceId, UUID idempotencyKey) {
		diplomacy.endAlliance(me, gameId, allianceId, idempotencyKey);
	}

	@Override
	public void sendGold(CurrentPlayer me, UUID gameId, UUID recipientNationId, long amountMg, UUID idempotencyKey) {
		gifts.send(me, gameId, recipientNationId, amountMg, idempotencyKey);
	}

	@Override
	public void callToArms(CurrentPlayer me, UUID gameId, UUID warId, UUID allyNationId, UUID idempotencyKey) {
		calls.call(me, gameId, warId, allyNationId, idempotencyKey);
	}

	@Override
	public void answerCall(CurrentPlayer me, UUID gameId, UUID callId, boolean honor, UUID idempotencyKey) {
		calls.answer(me, gameId, callId, honor, idempotencyKey);
	}

	@Override
	public DiplomacyViews.ConferenceResult requestConference(CurrentPlayer me, UUID gameId, UUID warId, String kind,
			UUID idempotencyKey) {
		return conferences.request(me, gameId, warId, kind, idempotencyKey);
	}

	@Override
	public DiplomacyViews.ConferenceResult answerConference(CurrentPlayer me, UUID gameId, UUID conferenceId,
			boolean accept, UUID idempotencyKey) {
		return conferences.answer(me, gameId, conferenceId, accept, idempotencyKey);
	}

	@Override
	public DiplomacyViews.ConferenceResult cancelConference(CurrentPlayer me, UUID gameId, UUID conferenceId,
			UUID idempotencyKey) {
		return conferences.cancel(me, gameId, conferenceId, idempotencyKey);
	}

	@Override
	public DiplomacyViews.ConferenceResult submitDraft(CurrentPlayer me, UUID gameId, UUID conferenceId,
			int expectedRevision, List<DiplomacyViews.Term> terms, UUID idempotencyKey) {
		return conferences.submit(me, gameId, conferenceId, expectedRevision, terms, idempotencyKey);
	}

	@Override
	public DiplomacyViews.ConferenceResult decideDraft(CurrentPlayer me, UUID gameId, UUID conferenceId, int revision,
			boolean approve, UUID idempotencyKey) {
		return conferences.decide(me, gameId, conferenceId, revision, approve, idempotencyKey);
	}

}
