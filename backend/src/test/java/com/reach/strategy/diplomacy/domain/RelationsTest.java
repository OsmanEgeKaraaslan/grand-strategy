package com.reach.strategy.diplomacy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The immutable diplomatic state (ENGINE_DESIGN S4): transitions and their guards. */
class RelationsTest {

	static final UUID A = new UUID(0, 1);
	static final UUID B = new UUID(0, 2);
	static final UUID C = new UUID(0, 3);
	static final UUID WAR = new UUID(0, 100);
	static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

	static Relations empty() {
		return new Relations(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of());
	}

	static Relations atWar() {
		return empty().declare(new RelationsRepository.WarRow(WAR, A, B, "ACTIVE", 1, 1, null, null, null))
			.join(new RelationsRepository.ParticipantRow(WAR, A, "ATTACKER", 1, "DECLARED"))
			.join(new RelationsRepository.ParticipantRow(WAR, B, "DEFENDER", 1, "ATTACKED"))
			.join(new RelationsRepository.ParticipantRow(WAR, C, "DEFENDER", 1, "CALL_TO_ARMS"))
			.front(WAR, A, B)
			.front(WAR, A, C);
	}

	@Test
	void anAllianceIsActivatedOrClosedOnlyFromTheExpectedStatus() {
		UUID id = new UUID(0, 50);
		Relations proposed = empty().proposeAlliance(
				new RelationsRepository.AllianceRow(id, "NORMAL", A, B, A, "PROPOSED", 1, null, null, null));

		Relations active = proposed.activateAlliance(id, 2).orElseThrow();
		assertThat(active.alliance(id).orElseThrow().status()).isEqualTo("ACTIVE");
		assertThat(active.alliance(id).orElseThrow().acceptedRound()).isEqualTo(2);
		assertThat(active.activateAlliance(id, 3)).isEmpty();
		assertThat(active.closeAlliance(id, "PROPOSED", "REJECTED", "REJECTED", 3)).isEmpty();

		Relations ended = active.closeAlliance(id, "ACTIVE", "ENDED", "TERMINATED", 4).orElseThrow();
		assertThat(ended.alliance(id).orElseThrow().endReason()).isEqualTo("TERMINATED");
		assertThat(proposed.alliance(id).orElseThrow().status()).isEqualTo("PROPOSED");
	}

	@Test
	void endingAWarEndsAllItsFrontsAndOnlyOnce() {
		Relations ended = atWar().endWar(WAR, 3, 7, "PEACE_TREATY").orElseThrow();

		assertThat(ended.war(WAR).orElseThrow().active()).isFalse();
		assertThat(ended.war(WAR).orElseThrow().endedTurn()).isEqualTo(7);
		assertThat(ended.fronts()).isEmpty();
		assertThat(ended.endWar(WAR, 4, 8, "PEACE_TREATY")).isEmpty();
		assertThat(ended.endWarIfActive(WAR, 4, 8, "X").war(WAR).orElseThrow().endedRound()).isEqualTo(3);
	}

	@Test
	void aNationLeavingAWarLosesOnlyItsFrontsAndANewLeaderTakesOver() {
		Relations left = atWar().leaveWar(WAR, C, 2, "SEPARATE_PEACE").lead(WAR, "DEFENDER", C);

		assertThat(left.fronts()).containsExactly(new RelationsRepository.FrontRow(WAR, A, B));
		assertThat(left.participants().stream().filter(p -> p.nationId().equals(C)).findFirst().orElseThrow().active())
			.isFalse();
		assertThat(left.war(WAR).orElseThrow().defenderLeader()).isEqualTo(C);
		assertThatThrownBy(() -> left.leaveWar(WAR, C, 3, "AGAIN")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aTributeIsChargedRoundByRoundCompletesWithItsLastRoundAndCanBeCancelledWhileActive() {
		UUID id = new UUID(0, 70);
		Relations r = empty().impose(new TreatyRepository.TributeRow(id, new UUID(0, 71), A, B, 1000, 2, 0, 3, 0, "ACTIVE",
				null, null));

		Relations once = r.charge(id, 500, false, 3);
		assertThat(once.tribute(id).orElseThrow().roundsCharged()).isEqualTo(1);
		assertThat(once.tribute(id).orElseThrow().endedRound()).isNull();
		Relations done = once.charge(id, 300, true, 4);
		assertThat(done.tribute(id).orElseThrow().status()).isEqualTo("COMPLETED");
		assertThat(done.tribute(id).orElseThrow().totalPaidMg()).isEqualTo(800);
		assertThat(done.tribute(id).orElseThrow().endedRound()).isEqualTo(4);
		assertThatThrownBy(() -> done.charge(id, 1, true, 5)).isInstanceOf(IllegalStateException.class);
		assertThat(done.cancelTribute(id, "WAR_RENEWED", 5)).isEmpty();
		assertThat(once.cancelTribute(id, "WAR_RENEWED", 4).orElseThrow().tribute(id).orElseThrow().status())
			.isEqualTo("CANCELLED");
	}

	@Test
	void proposalsCallsAndConferencesCloseOnceAndRevisionsBuildOnTheExpectedOne() {
		UUID proposal = new UUID(0, 80);
		Relations r = atWar().propose(new TreatyRepository.ProposalRow(proposal, WAR, A, B, "PENDING", "[]", 2, 3, 9, null,
				null, NOW));
		assertThat(r.closeProposal(proposal, "ACCEPTED", 3).orElseThrow().closeProposal(proposal, "REJECTED", 3)).isEmpty();

		UUID call = new UUID(0, 81);
		Relations called = r.call(new CallToArmsRepository.CallRow(call, WAR, B, C, new UUID(0, 50), "PENDING", 2, null,
				NOW));
		assertThat(called.closeCall(call, "HONORED", 3).orElseThrow().call(call).orElseThrow().answeredRound()).isEqualTo(3);

		UUID conference = new UUID(0, 82);
		Relations requested = called.request(new ConferenceRepository.ConferenceRow(conference, WAR, "GENERAL", A, B,
				"REQUESTED", 0, 2, null, null, NOW));
		Relations open = requested.openConference(conference, NOW).orElseThrow();
		assertThat(open.openConference(conference, NOW)).isEmpty();
		Relations revised = open.revise(conference, 0).orElseThrow();
		assertThat(revised.conference(conference).orElseThrow().currentRevision()).isEqualTo(1);
		assertThat(revised.revise(conference, 0)).isEmpty();
		assertThat(revised.closeConference(conference, "ACTIVE", "CONCLUDED", NOW).orElseThrow()
			.conference(conference).orElseThrow()
			.closedAt()).isEqualTo(NOW);
	}

}
