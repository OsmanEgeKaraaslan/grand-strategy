package com.reach.strategy.diplomacy.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.diplomacy.domain.CallToArmsRepository;
import com.reach.strategy.diplomacy.domain.ConferenceRepository;
import com.reach.strategy.diplomacy.domain.DiplomacyStore;
import com.reach.strategy.diplomacy.domain.Relations;
import com.reach.strategy.diplomacy.domain.RelationsRepository;
import com.reach.strategy.diplomacy.domain.TreatyRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The diplomatic changes of one command or round step (ENGINE_DESIGN S4). It loads the game's
 * {@link Relations} once, applies every change to it in memory and {@link #write() writes} the
 * difference: before anything else reads diplomatic rows again (another module, a fresh
 * {@code DiplomacyState} picture) and at the end. The method names and results mirror the
 * repository writes it replaces, so a guarded change still reports whether it applied.
 */
final class DiplomacyDraft {

	private final DiplomacyStore store;
	private final TreatyRepository treaties;
	private final ConferenceRepository conferences;
	private final UUID gameId;
	private final Instant now;
	private Relations written;
	private Relations current;

	private DiplomacyDraft(DiplomacyStore store, TreatyRepository treaties, ConferenceRepository conferences, UUID gameId,
			Instant now) {
		this.store = store;
		this.treaties = treaties;
		this.conferences = conferences;
		this.gameId = gameId;
		this.now = now;
		this.written = store.load(gameId);
		this.current = written;
	}

	/** Creates drafts; a draft lives inside one transaction. */
	@Component
	static class Factory {

		private final DiplomacyStore store;
		private final TreatyRepository treaties;
		private final ConferenceRepository conferences;

		Factory(DiplomacyStore store, TreatyRepository treaties, ConferenceRepository conferences) {
			this.store = store;
			this.treaties = treaties;
			this.conferences = conferences;
		}

		@Transactional(propagation = Propagation.MANDATORY)
		DiplomacyDraft begin(UUID gameId, Instant now) {
			DiplomacyDraft draft = new DiplomacyDraft(store, treaties, conferences, gameId, now);
			pending().add(draft);
			return draft;
		}

		/**
		 * Writes every draft of the current transaction, in the order they began: before a record that
		 * refers to diplomatic rows (an event naming a new war, say) and, as a safety net, before commit.
		 */
		void writeAll() {
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				pending().forEach(DiplomacyDraft::write);
			}
		}

		@SuppressWarnings("unchecked")
		private List<DiplomacyDraft> pending() {
			List<DiplomacyDraft> list = (List<DiplomacyDraft>) TransactionSynchronizationManager.getResource(this);
			if (list == null) {
				List<DiplomacyDraft> created = new ArrayList<>();
				list = created;
				TransactionSynchronizationManager.bindResource(this, created);
				Factory key = this;
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

					@Override
					public void beforeCommit(boolean readOnly) {
						created.forEach(DiplomacyDraft::write);
					}

					@Override
					public void afterCompletion(int status) {
						TransactionSynchronizationManager.unbindResourceIfPossible(key);
					}

				});
			}
			return list;
		}

	}

	Relations current() {
		return current;
	}

	/** Persists every change made since the last write. */
	void write() {
		if (current != written) {
			store.persist(gameId, written, current, now);
			written = current;
		}
	}

	private boolean apply(Optional<Relations> changed) {
		changed.ifPresent(r -> current = r);
		return changed.isPresent();
	}

	// --- alliances ----------------------------------------------------------------------------

	void insertAlliance(RelationsRepository.AllianceRow row) {
		current = current.proposeAlliance(row);
	}

	boolean activateAlliance(UUID allianceId, int round) {
		return apply(current.activateAlliance(allianceId, round));
	}

	boolean closeAlliance(UUID allianceId, String fromStatus, String toStatus, String reason, int round) {
		return apply(current.closeAlliance(allianceId, fromStatus, toStatus, reason, round));
	}

	// --- wars ---------------------------------------------------------------------------------

	void insertWar(RelationsRepository.WarRow war) {
		current = current.declare(war);
	}

	void insertParticipant(RelationsRepository.ParticipantRow participant) {
		current = current.join(participant);
	}

	void insertFront(UUID warId, UUID a, UUID b) {
		current = current.front(warId, a, b);
	}

	/** ACTIVE → ENDED; its fronts end in any case (as the SQL it replaces did). */
	boolean endWar(UUID warId, int round, int turn, String reason) {
		boolean active = current.war(warId).map(RelationsRepository.WarRow::active).orElse(false);
		current = current.endWarIfActive(warId, round, turn, reason);
		return active;
	}

	void leaveWar(UUID warId, UUID nationId, int round, String reason) {
		current = current.leaveWar(warId, nationId, round, reason);
	}

	void setLeader(UUID warId, String side, UUID nationId) {
		current = current.lead(warId, side, nationId);
	}

	void insertTruce(RelationsRepository.TruceRow truce) {
		current = current.truce(truce);
	}

	// --- tribute ------------------------------------------------------------------------------

	void insertTribute(TreatyRepository.TributeRow tribute) {
		current = current.impose(tribute);
	}

	/** Charges one round: the payment record is written at once, the tribute changes with the draft. */
	void recordPayment(TreatyRepository.PaymentRow payment, boolean last) {
		current = current.charge(payment.tributeId(), payment.paidMg(), last, payment.round());
		treaties.insertPayment(gameId, payment, now);
	}

	boolean cancelTribute(UUID tributeId, String reason, int round) {
		return apply(current.cancelTribute(tributeId, reason, round));
	}

	// --- peace proposals ----------------------------------------------------------------------

	void insertProposal(TreatyRepository.ProposalRow proposal) {
		current = current.propose(proposal);
	}

	boolean closeProposal(UUID proposalId, String status, int round) {
		return apply(current.closeProposal(proposalId, status, round));
	}

	// --- calls to arms ------------------------------------------------------------------------

	void insertCall(CallToArmsRepository.CallRow call) {
		current = current.call(call);
	}

	boolean closeCall(UUID callId, String status, int round) {
		return apply(current.closeCall(callId, status, round));
	}

	// --- conferences --------------------------------------------------------------------------

	void insertConference(ConferenceRepository.ConferenceRow conference) {
		current = current.request(conference);
	}

	boolean openConference(UUID conferenceId) {
		return apply(current.openConference(conferenceId, now));
	}

	boolean closeConference(UUID conferenceId, String fromStatus, String toStatus) {
		return apply(current.closeConference(conferenceId, fromStatus, toStatus, now));
	}

	/** The next revision; the conference row changes with the draft, the revision record is written now. */
	boolean addRevision(UUID conferenceId, int expectedRevision, ConferenceRepository.RevisionRow revision) {
		if (!apply(current.revise(conferenceId, expectedRevision))) {
			return false;
		}
		write();
		conferences.insertRevision(gameId, conferenceId, revision);
		return true;
	}

}
