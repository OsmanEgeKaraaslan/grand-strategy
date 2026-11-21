package com.reach.strategy.diplomacy.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The diplomatic state of one game as immutable values (ENGINE_DESIGN S4): alliances, wars, their
 * participants and active fronts, truces, tributes, peace proposals, calls to arms and peace
 * conferences, as the rows their tables hold. Every change returns a new {@code Relations}; a store
 * writes the difference to the value it was derived from. The guarded changes return
 * {@link Optional#empty()} when the row is no longer in the expected state, exactly where the
 * conditional SQL updates they replace matched no row.
 * <p>
 * Lists keep the order the store loaded them in (the order views and rules have always seen); new
 * rows are appended. Append-only records (events, gifts, treaties, payments, war score, conference
 * revisions and decisions) are not part of this state: they are written directly.
 */
public final class Relations {

	private final List<RelationsRepository.AllianceRow> alliances;
	private final List<RelationsRepository.WarRow> wars;
	private final List<RelationsRepository.ParticipantRow> participants;
	private final List<RelationsRepository.FrontRow> fronts;
	private final List<RelationsRepository.TruceRow> truces;
	private final List<TreatyRepository.TributeRow> tributes;
	private final List<TreatyRepository.ProposalRow> proposals;
	private final List<CallToArmsRepository.CallRow> calls;
	private final List<ConferenceRepository.ConferenceRow> conferences;

	public Relations(List<RelationsRepository.AllianceRow> alliances, List<RelationsRepository.WarRow> wars,
			List<RelationsRepository.ParticipantRow> participants, List<RelationsRepository.FrontRow> fronts,
			List<RelationsRepository.TruceRow> truces, List<TreatyRepository.TributeRow> tributes,
			List<TreatyRepository.ProposalRow> proposals, List<CallToArmsRepository.CallRow> calls,
			List<ConferenceRepository.ConferenceRow> conferences) {
		this.alliances = List.copyOf(alliances);
		this.wars = List.copyOf(wars);
		this.participants = List.copyOf(participants);
		this.fronts = List.copyOf(fronts);
		this.truces = List.copyOf(truces);
		this.tributes = List.copyOf(tributes);
		this.proposals = List.copyOf(proposals);
		this.calls = List.copyOf(calls);
		this.conferences = List.copyOf(conferences);
	}

	// --- reading ------------------------------------------------------------------------------

	public List<RelationsRepository.AllianceRow> alliances() {
		return alliances;
	}

	public List<RelationsRepository.WarRow> wars() {
		return wars;
	}

	public List<RelationsRepository.ParticipantRow> participants() {
		return participants;
	}

	/** The active fronts (pairs of nations fighting each other in a war). */
	public List<RelationsRepository.FrontRow> fronts() {
		return fronts;
	}

	public List<RelationsRepository.TruceRow> truces() {
		return truces;
	}

	public List<TreatyRepository.TributeRow> tributes() {
		return tributes;
	}

	public List<TreatyRepository.ProposalRow> proposals() {
		return proposals;
	}

	public List<CallToArmsRepository.CallRow> calls() {
		return calls;
	}

	public List<ConferenceRepository.ConferenceRow> conferences() {
		return conferences;
	}

	public Optional<RelationsRepository.AllianceRow> alliance(UUID id) {
		return alliances.stream().filter(a -> a.id().equals(id)).findFirst();
	}

	public Optional<RelationsRepository.WarRow> war(UUID id) {
		return wars.stream().filter(w -> w.id().equals(id)).findFirst();
	}

	public Optional<TreatyRepository.TributeRow> tribute(UUID id) {
		return tributes.stream().filter(t -> t.id().equals(id)).findFirst();
	}

	public Optional<TreatyRepository.ProposalRow> proposal(UUID id) {
		return proposals.stream().filter(p -> p.id().equals(id)).findFirst();
	}

	public Optional<CallToArmsRepository.CallRow> call(UUID id) {
		return calls.stream().filter(c -> c.id().equals(id)).findFirst();
	}

	public Optional<ConferenceRepository.ConferenceRow> conference(UUID id) {
		return conferences.stream().filter(c -> c.id().equals(id)).findFirst();
	}

	// --- alliances ----------------------------------------------------------------------------

	public Relations proposeAlliance(RelationsRepository.AllianceRow proposal) {
		return with(append(alliances, proposal), wars, participants, fronts, truces, tributes, proposals, calls,
				conferences);
	}

	/** PROPOSED → ACTIVE; empty if it was no longer pending. */
	public Optional<Relations> activateAlliance(UUID id, int round) {
		return replace(alliances, a -> a.id().equals(id) && "PROPOSED".equals(a.status()),
				a -> new RelationsRepository.AllianceRow(a.id(), a.kind(), a.nationA(), a.nationB(), a.proposer(), "ACTIVE",
						a.proposedRound(), round, a.endedRound(), a.endReason()))
			.map(list -> with(list, wars, participants, fronts, truces, tributes, proposals, calls, conferences));
	}

	/** Closes an open alliance (REJECTED, CANCELLED, EXPIRED, ENDED); empty if it was not in {@code fromStatus}. */
	public Optional<Relations> closeAlliance(UUID id, String fromStatus, String toStatus, String reason, int round) {
		return replace(alliances, a -> a.id().equals(id) && fromStatus.equals(a.status()),
				a -> new RelationsRepository.AllianceRow(a.id(), a.kind(), a.nationA(), a.nationB(), a.proposer(), toStatus,
						a.proposedRound(), a.acceptedRound(), round, reason))
			.map(list -> with(list, wars, participants, fronts, truces, tributes, proposals, calls, conferences));
	}

	// --- wars ---------------------------------------------------------------------------------

	public Relations declare(RelationsRepository.WarRow war) {
		RelationsRepository.WarRow active = new RelationsRepository.WarRow(war.id(), war.attackerLeader(),
				war.defenderLeader(), "ACTIVE", war.declaredRound(), war.declaredTurn(), null, null, null);
		return with(alliances, append(wars, active), participants, fronts, truces, tributes, proposals, calls, conferences);
	}

	public Relations join(RelationsRepository.ParticipantRow participant) {
		return with(alliances, wars, append(participants, participant), fronts, truces, tributes, proposals, calls,
				conferences);
	}

	/** A pair of nations now fighting each other in this war. */
	public Relations front(UUID warId, UUID a, UUID b) {
		return with(alliances, wars, participants, append(fronts, new RelationsRepository.FrontRow(warId, a, b)), truces,
				tributes, proposals, calls, conferences);
	}

	/** ACTIVE → ENDED with every front of the war; empty if it had already ended. */
	public Optional<Relations> endWar(UUID warId, int round, int turn, String reason) {
		List<RelationsRepository.FrontRow> remaining = fronts.stream().filter(f -> !f.warId().equals(warId)).toList();
		return replace(wars, w -> w.id().equals(warId) && w.active(),
				w -> new RelationsRepository.WarRow(w.id(), w.attackerLeader(), w.defenderLeader(), "ENDED", w.declaredRound(),
						w.declaredTurn(), round, turn, reason))
			.map(list -> with(alliances, list, participants, remaining, truces, tributes, proposals, calls, conferences));
	}

	/** Like {@link #endWar}, but an already ended war only loses its (remaining) fronts. */
	public Relations endWarIfActive(UUID warId, int round, int turn, String reason) {
		return endWar(warId, round, turn, reason).orElseGet(() -> with(alliances, wars, participants,
				fronts.stream().filter(f -> !f.warId().equals(warId)).toList(), truces, tributes, proposals, calls,
				conferences));
	}

	/** A nation leaves a war (separate peace, elimination): its fronts in that war end. */
	public Relations leaveWar(UUID warId, UUID nationId, int round, String reason) {
		List<RelationsRepository.ParticipantRow> list = replace(participants,
				p -> p.warId().equals(warId) && p.nationId().equals(nationId) && p.active(),
				p -> new RelationsRepository.ParticipantRow(p.warId(), p.nationId(), p.side(), p.joinedRound(), p.joinReason(),
						round, reason))
			.orElseThrow(() -> new IllegalStateException("Nation " + nationId + " is not fighting in war " + warId));
		List<RelationsRepository.FrontRow> remaining = fronts.stream()
			.filter(f -> !(f.warId().equals(warId) && (f.nationA().equals(nationId) || f.nationB().equals(nationId))))
			.toList();
		return with(alliances, wars, list, remaining, truces, tributes, proposals, calls, conferences);
	}

	/** A new leader of one side (the previous one left the war); only while the war is active. */
	public Relations lead(UUID warId, String side, UUID nationId) {
		return replace(wars, w -> w.id().equals(warId) && w.active(),
				w -> "ATTACKER".equals(side)
						? new RelationsRepository.WarRow(w.id(), nationId, w.defenderLeader(), w.status(), w.declaredRound(),
								w.declaredTurn(), w.endedRound(), w.endedTurn(), w.endReason())
						: new RelationsRepository.WarRow(w.id(), w.attackerLeader(), nationId, w.status(), w.declaredRound(),
								w.declaredTurn(), w.endedRound(), w.endedTurn(), w.endReason()))
			.map(list -> with(alliances, list, participants, fronts, truces, tributes, proposals, calls, conferences))
			.orElse(this);
	}

	public Relations truce(RelationsRepository.TruceRow truce) {
		return with(alliances, wars, participants, fronts, append(truces, truce), tributes, proposals, calls, conferences);
	}

	// --- tribute ------------------------------------------------------------------------------

	public Relations impose(TreatyRepository.TributeRow tribute) {
		TreatyRepository.TributeRow active = new TreatyRepository.TributeRow(tribute.id(), tribute.treatyId(),
				tribute.payer(), tribute.receiver(), tribute.rateBps(), tribute.roundsTotal(), 0, tribute.firstRound(), 0,
				"ACTIVE", null, null);
		return with(alliances, wars, participants, fronts, truces, append(tributes, active), proposals, calls,
				conferences);
	}

	/** One round charged; the tribute completes with its last round. */
	public Relations charge(UUID tributeId, long paidMg, boolean last, int round) {
		List<TreatyRepository.TributeRow> list = replace(tributes, t -> t.id().equals(tributeId) && t.active(),
				t -> new TreatyRepository.TributeRow(t.id(), t.treatyId(), t.payer(), t.receiver(), t.rateBps(),
						t.roundsTotal(), t.roundsCharged() + 1, t.firstRound(), Math.addExact(t.totalPaidMg(), paidMg),
						last ? "COMPLETED" : t.status(), last ? "COMPLETED" : t.endReason(), last ? Integer.valueOf(round) : t.endedRound()))
			.orElseThrow(() -> new IllegalStateException("Tribute " + tributeId + " is not active"));
		return with(alliances, wars, participants, fronts, truces, list, proposals, calls, conferences);
	}

	/** ACTIVE → CANCELLED (e.g. the two nations are at war again); empty if it was not active. */
	public Optional<Relations> cancelTribute(UUID tributeId, String reason, int round) {
		return replace(tributes, t -> t.id().equals(tributeId) && t.active(),
				t -> new TreatyRepository.TributeRow(t.id(), t.treatyId(), t.payer(), t.receiver(), t.rateBps(),
						t.roundsTotal(), t.roundsCharged(), t.firstRound(), t.totalPaidMg(), "CANCELLED", reason, round))
			.map(list -> with(alliances, wars, participants, fronts, truces, list, proposals, calls, conferences));
	}

	// --- peace proposals ----------------------------------------------------------------------

	public Relations propose(TreatyRepository.ProposalRow proposal) {
		TreatyRepository.ProposalRow pending = new TreatyRepository.ProposalRow(proposal.id(), proposal.warId(),
				proposal.proposer(), proposal.recipient(), "PENDING", proposal.termsJson(), proposal.createdRound(),
				proposal.createdTurn(), proposal.createdStateVersion(), null, proposal.counterOfId(), proposal.createdAt());
		return with(alliances, wars, participants, fronts, truces, tributes, append(proposals, pending), calls,
				conferences);
	}

	/** PENDING → {@code status}; empty if it was no longer pending. */
	public Optional<Relations> closeProposal(UUID proposalId, String status, int round) {
		return replace(proposals, p -> p.id().equals(proposalId) && p.pending(),
				p -> new TreatyRepository.ProposalRow(p.id(), p.warId(), p.proposer(), p.recipient(), status, p.termsJson(),
						p.createdRound(), p.createdTurn(), p.createdStateVersion(), round, p.counterOfId(), p.createdAt()))
			.map(list -> with(alliances, wars, participants, fronts, truces, tributes, list, calls, conferences));
	}

	// --- calls to arms ------------------------------------------------------------------------

	public Relations call(CallToArmsRepository.CallRow call) {
		CallToArmsRepository.CallRow pending = new CallToArmsRepository.CallRow(call.id(), call.warId(), call.caller(),
				call.ally(), call.allianceId(), "PENDING", call.createdRound(), null, call.createdAt(), call.side());
		return with(alliances, wars, participants, fronts, truces, tributes, proposals, append(calls, pending),
				conferences);
	}

	/** PENDING → {@code status}; empty if it was no longer pending. */
	public Optional<Relations> closeCall(UUID callId, String status, int round) {
		return replace(calls, c -> c.id().equals(callId) && c.pending(),
				c -> new CallToArmsRepository.CallRow(c.id(), c.warId(), c.caller(), c.ally(), c.allianceId(), status,
						c.createdRound(), round, c.createdAt(), c.side()))
			.map(list -> with(alliances, wars, participants, fronts, truces, tributes, proposals, list, conferences));
	}

	// --- conferences --------------------------------------------------------------------------

	public Relations request(ConferenceRepository.ConferenceRow conference) {
		ConferenceRepository.ConferenceRow requested = new ConferenceRepository.ConferenceRow(conference.id(),
				conference.warId(), conference.kind(), conference.requester(), conference.counterpart(), "REQUESTED", 0,
				conference.requestedRound(), null, null, conference.createdAt());
		return with(alliances, wars, participants, fronts, truces, tributes, proposals, calls,
				append(conferences, requested));
	}

	/** REQUESTED → ACTIVE; empty if it was not requested any more. */
	public Optional<Relations> openConference(UUID id, Instant now) {
		return replace(conferences, c -> c.id().equals(id) && "REQUESTED".equals(c.status()),
				c -> new ConferenceRepository.ConferenceRow(c.id(), c.warId(), c.kind(), c.requester(), c.counterpart(),
						"ACTIVE", c.currentRevision(), c.requestedRound(), now, c.closedAt(), c.createdAt()))
			.map(list -> with(alliances, wars, participants, fronts, truces, tributes, proposals, calls, list));
	}

	/** An open conference ends (REJECTED, CANCELLED, CONCLUDED); empty if it was not in {@code fromStatus}. */
	public Optional<Relations> closeConference(UUID id, String fromStatus, String toStatus, Instant now) {
		return replace(conferences, c -> c.id().equals(id) && fromStatus.equals(c.status()),
				c -> new ConferenceRepository.ConferenceRow(c.id(), c.warId(), c.kind(), c.requester(), c.counterpart(),
						toStatus, c.currentRevision(), c.requestedRound(), c.openedAt(), now, c.createdAt()))
			.map(list -> with(alliances, wars, participants, fronts, truces, tributes, proposals, calls, list));
	}

	/** The next revision of an active conference still at {@code expectedRevision}; empty otherwise. */
	public Optional<Relations> revise(UUID id, int expectedRevision) {
		return replace(conferences,
				c -> c.id().equals(id) && "ACTIVE".equals(c.status()) && c.currentRevision() == expectedRevision,
				c -> new ConferenceRepository.ConferenceRow(c.id(), c.warId(), c.kind(), c.requester(), c.counterpart(),
						c.status(), c.currentRevision() + 1, c.requestedRound(), c.openedAt(), c.closedAt(), c.createdAt()))
			.map(list -> with(alliances, wars, participants, fronts, truces, tributes, proposals, calls, list));
	}

	// --- helpers ------------------------------------------------------------------------------

	private static Relations with(List<RelationsRepository.AllianceRow> alliances, List<RelationsRepository.WarRow> wars,
			List<RelationsRepository.ParticipantRow> participants, List<RelationsRepository.FrontRow> fronts,
			List<RelationsRepository.TruceRow> truces, List<TreatyRepository.TributeRow> tributes,
			List<TreatyRepository.ProposalRow> proposals, List<CallToArmsRepository.CallRow> calls,
			List<ConferenceRepository.ConferenceRow> conferences) {
		return new Relations(alliances, wars, participants, fronts, truces, tributes, proposals, calls, conferences);
	}

	private static <T> List<T> append(List<T> list, T row) {
		List<T> copy = new ArrayList<>(list);
		copy.add(row);
		return copy;
	}

	/** Replaces the first row matching {@code which}; empty if none matches. */
	private static <T> Optional<List<T>> replace(List<T> list, Predicate<T> which, UnaryOperator<T> change) {
		for (int i = 0; i < list.size(); i++) {
			if (which.test(list.get(i))) {
				List<T> copy = new ArrayList<>(list);
				copy.set(i, change.apply(list.get(i)));
				return Optional.of(copy);
			}
		}
		return Optional.empty();
	}

}
