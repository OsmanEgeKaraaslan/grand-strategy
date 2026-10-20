package com.reach.strategy.diplomacy.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.diplomacy.rules.DiplomacyRules;

/**
 * Read models of diplomacy. Wars, alliances, treaties, tributes and the event history are public
 * (no fog of war, Q-T4); peace and alliance proposals are shown only to the two nations concerned.
 * Every number (war score, treasury effects, tribute) is computed on the server.
 */
public final class DiplomacyViews {

	private DiplomacyViews() {
	}

	/**
	 * @param available false for games of rulesets without diplomacy (every nation permanently at war)
	 */
	public record Overview(UUID gameId, boolean available, String rulesetVersion, int round, boolean yourTurn,
			UUID yourNationId, Rules rules, List<NationRelation> nations, List<WarView> wars, List<AllianceView> alliances,
			List<ProposalView> proposals, List<TreatyView> treaties, List<TributeView> tributes, List<EventView> events,
			List<Gift> gifts, List<CallView> calls, List<ConferenceView> conferences) {

		public Overview(UUID gameId, boolean available, String rulesetVersion, int round, boolean yourTurn,
				UUID yourNationId, Rules rules, List<NationRelation> nations, List<WarView> wars,
				List<AllianceView> alliances, List<ProposalView> proposals, List<TreatyView> treaties,
				List<TributeView> tributes, List<EventView> events, List<Gift> gifts) {
			this(gameId, available, rulesetVersion, round, yourTurn, yourNationId, rules, nations, wars, alliances,
					proposals, treaties, tributes, events, gifts, List.of(), List.of());
		}

	}

	public record Rules(String version, int truceRounds, int proposalExpiryRounds, int maxPeaceTerms, int tributeRateBps,
			int tributeRounds, int populationMinPeople, int populationMaxPeople, int populationMaxShareBps,
			int populationMinResidual, long reparationsMaxMg, int maxScore, int victoryMaxRounds, int victoryProvincePoints,
			int victoryPointsPerThousandPopulation, boolean normalAllianceGrantsAccess,
			boolean defensiveAllianceGrantsAccess, boolean coBelligerentAccess, Long giftMaxMg, boolean giftsToEnemies,
			DiplomacyRules.Features features, int victoryPointsPerHundredGold, boolean domination) {
	}

	/**
	 * The caller's standing with one nation.
	 *
	 * @param status SELF, WAR, ALLIED or PEACE
	 * @param truceUntilRound last round of a truce with this nation, if any
	 * @param warBlockers why the caller cannot declare war now (empty = allowed)
	 * @param allianceBlockers why the caller cannot propose an alliance now (empty = allowed)
	 * @param weMayEnter whether the caller's battalions may enter this nation's territory
	 * @param theyMayEnter whether this nation's battalions may enter the caller's territory
	 */
	public record NationRelation(UUID nationId, String name, String color, boolean you, String status,
			String allianceKind, UUID allianceId, UUID warId, Integer truceUntilRound, boolean weMayEnter,
			boolean theyMayEnter, List<String> warBlockers, List<String> allianceBlockers) {
	}

	/**
	 * @param durationRounds rounds the war has lasted (counting the round it was declared in)
	 * @param yourSide ATTACKER, DEFENDER or null
	 * @param youLead whether the caller is one of the two leaders (who negotiate peace)
	 */
	public record WarView(UUID id, String status, UUID attackerLeaderId, UUID defenderLeaderId,
			List<Participant> participants, int declaredRound, Integer endedRound, String endReason, int durationRounds,
			Score score, String yourSide, boolean youLead) {
	}

	/** @param leftRound round the nation left the war (separate peace, elimination), null while it fights */
	public record Participant(UUID nationId, String name, String side, String joinReason, int joinedRound,
			Integer leftRound, String leftReason) {

		public Participant(UUID nationId, String name, String side, String joinReason, int joinedRound) {
			this(nationId, name, side, joinReason, joinedRound, null, null);
		}

	}

	/** @param net attacker total minus defender total (positive favours the attacker) */
	public record Score(SideScore attacker, SideScore defender, int net, int maxScore) {
	}

	public record SideScore(int battles, int sieges, int occupation, int total, int battlesWon, int siegesWon,
			long occupiedEnemyPopulation, long enemyPopulation) {
	}

	public record WarDetail(WarView war, List<ScoreEvent> scoreEvents, List<EventView> history) {
	}

	public record ScoreEvent(String kind, String side, UUID sourceId, int round, int enemyLosses, int points) {
	}

	/**
	 * @param grantsMilitaryAccess whether this kind of alliance lets the partners' battalions in
	 * @param canAccept the caller may accept (or reject) it now
	 * @param canEnd the caller may end it (or withdraw the proposal) now
	 * @param endBlockers why an active alliance cannot be ended now
	 */
	public record AllianceView(UUID id, String kind, UUID nationA, UUID nationB, UUID proposerNationId, String status,
			int proposedRound, Integer acceptedRound, Integer endedRound, String endReason, int expiresAfterRound,
			boolean grantsMilitaryAccess, boolean canAccept, boolean canEnd, List<String> endBlockers) {
	}

	/**
	 * @param incoming whether the caller is the recipient
	 * @param violations what would stop this proposal from being settled against the current state
	 */
	public record ProposalView(UUID id, UUID warId, UUID proposerNationId, UUID recipientNationId, String status,
			List<Term> terms, int createdRound, int createdTurn, long createdStateVersion, Integer respondedRound,
			UUID counterOfId, int expiresAfterRound, boolean incoming, List<String> violations, Instant createdAt) {
	}

	/**
	 * One peace term. {@code type}: PROVINCE (provinceKey), GOLD (amountMg), TRIBUTE (rate and rounds
	 * from the ruleset), POPULATION (source and destination city, people).
	 */
	public record Term(String type, UUID fromNationId, UUID toNationId, String provinceKey, Long amountMg,
			UUID sourceCityId, UUID destinationCityId, Integer people, Integer tributeRateBps, Integer tributeRounds) {
	}

	public record TreatyView(UUID id, UUID warId, UUID proposalId, int signedRound, int signedTurn, List<Term> terms,
			int truceUntilRound) {
	}

	public record TributeView(UUID id, UUID treatyId, UUID payerNationId, UUID receiverNationId, int rateBps,
			int roundsTotal, int roundsCharged, int firstRound, long totalPaidMg, String status, String endReason,
			Integer endedRound, List<Payment> payments) {
	}

	public record Payment(int round, long grossIncomeMg, long dueMg, long paidMg) {
	}

	public record EventView(UUID id, int round, String kind, UUID actorNationId, UUID targetNationId, UUID warId,
			Map<String, Object> details, Instant createdAt) {
	}

	/** Result of a validation-only check of peace terms. */
	public record TermsCheck(boolean valid, List<Violation> violations) {
	}

	/** @param term index of the term, −1 for the proposal as a whole */
	public record Violation(int term, String code) {
	}

	/** A gold gift (public history). */
	public record Gift(UUID id, UUID senderNationId, UUID recipientNationId, long amountMg, int round, Instant createdAt) {
	}

	/**
	 * A defensive call to arms (public).
	 *
	 * @param status PENDING, HONORED, DECLINED or CANCELLED
	 * @param canAnswer the viewer is the called ally and the call is pending
	 */
	public record CallView(UUID id, UUID warId, UUID callerNationId, UUID allyNationId, String status, int createdRound,
			Integer answeredRound, boolean canAnswer) {
	}

	/**
	 * A peace conference (public: every nation sees the draft it may be affected by).
	 *
	 * @param kind GENERAL (between the war leaders) or SEPARATE (one nation leaves the war)
	 * @param status REQUESTED, ACTIVE, CONCLUDED, REJECTED or CANCELLED
	 * @param terms the current revision's terms (empty before the conference opens)
	 * @param approvals every nation whose approval the current revision needs, with its decision
	 * @param violations why the current revision could not be settled now (empty = it could)
	 */
	public record ConferenceView(UUID id, UUID warId, String kind, UUID requesterNationId, UUID counterpartNationId,
			String status, int requestedRound, int currentRevision, List<Term> terms, List<Approval> approvals,
			List<RevisionSummary> revisions, List<String> violations, boolean youArePrincipal, boolean canAnswer,
			boolean canCancel, boolean canRevise, boolean youMustDecide) {
	}

	/** @param decision APPROVED, REJECTED or null (not yet decided) */
	public record Approval(UUID nationId, String name, boolean npc, String decision) {
	}

	public record RevisionSummary(int revision, UUID authorNationId, int termCount, Instant createdAt) {
	}

	// --- command results ------------------------------------------------------------------------

	public record WarResult(WarView war, List<UUID> joinedDefenders) {
	}

	/** @param treasuryMg the sender's treasury after the gift */
	public record GiftResult(Gift gift, long treasuryMg) {
	}

	public record AllianceResult(AllianceView alliance, List<Withdrawal> withdrawals) {
	}

	public record ProposalResult(ProposalView proposal) {
	}

	/** @param withdrawals battalions moved out of territory their nation may no longer enter */
	public record PeaceResult(TreatyView treaty, WarView war, List<Withdrawal> withdrawals, List<String> controlReturned) {
	}

	public record CallResult(CallView call) {
	}

	/** @param treaty the treaty, when this command settled it */
	public record ConferenceResult(ConferenceView conference, TreatyView treaty, List<Withdrawal> withdrawals) {
	}

	public record Withdrawal(UUID battalionId, UUID nationId, String fromProvinceKey, String toProvinceKey) {
	}

}
