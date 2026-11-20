package com.reach.strategy.ai.application;

import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.reach.strategy.ai.domain.AiDecisionLog;
import com.reach.strategy.ai.rules.AiAction;
import com.reach.strategy.ai.rules.AiBrain;
import com.reach.strategy.ai.rules.AiRules;
import com.reach.strategy.ai.rules.AiState;
import com.reach.strategy.diplomacy.api.DiplomacyActions;
import com.reach.strategy.diplomacy.api.DiplomacyViews;
import com.reach.strategy.game.api.ComputerSeats;
import com.reach.strategy.game.api.GameAccess;
import com.reach.strategy.identity.api.ComputerPlayers;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.platform.web.ApiException;
import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Peace-conference business of computer nations, which happens out of turn (DIPLOMACY §14): answer
 * requests, decide on the current revision, revise or end a conference they lead. Called whenever a
 * conference changed and before every computer turn. A cheap check (the diplomacy overview) decides
 * whether a nation has anything to do before it observes the full board. Idempotency keys are derived
 * from the decision itself (conference, revision, nation), so a repeated call can never act twice.
 * Bounded by {@code maxConferencePasses}; it never waits for anybody.
 */
@Component
class AiNegotiator {

	private static final Logger log = LoggerFactory.getLogger(AiNegotiator.class);

	private final ComputerPlayers computers;
	private final GameAccess gameAccess;
	private final DiplomacyActions diplomacy;
	private final AiRulesets rulesets;
	private final AiObserver observer;
	private final AiExecutor executor;
	private final AiDecisionLog decisions;
	private final JsonMapper jsonMapper;
	private final Clock clock;
	private final TransactionTemplate writes;
	private final TransactionTemplate reads;
	/**
	 * Conference situation a nation last looked at without finding anything to do, per game and
	 * nation: an unchanged situation is not observed again (the expensive part).
	 */
	private final Map<String, String> idle = new java.util.concurrent.ConcurrentHashMap<>();

	AiNegotiator(ComputerPlayers computers, GameAccess gameAccess, DiplomacyActions diplomacy, AiRulesets rulesets,
			AiObserver observer, AiExecutor executor, AiDecisionLog decisions, JsonMapper jsonMapper, Clock clock,
			PlatformTransactionManager transactions) {
		this.computers = computers;
		this.gameAccess = gameAccess;
		this.diplomacy = diplomacy;
		this.rulesets = rulesets;
		this.observer = observer;
		this.executor = executor;
		this.decisions = decisions;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
		this.writes = new TransactionTemplate(transactions);
		this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.reads = new TransactionTemplate(transactions);
		this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.reads.setReadOnly(true);
	}

	void negotiate(UUID gameId, List<ComputerSeats.Seat> seats) {
		if (seats.isEmpty()) {
			return;
		}
		// Computers only negotiate inside peace conferences. Without an open one there is nothing to do,
		// and checking costs one query instead of a diplomacy overview per computer nation.
		if (!read(() -> diplomacy.anyOpenConference(gameId))) {
			return;
		}
		CurrentPlayer probe = computers.actor(seats.getFirst().playerId());
		GameAccess.Membership membership = read(() -> gameAccess.membership(probe, gameId));
		if (!membership.inProgress()) {
			return;
		}
		Optional<AiRules> rules = rulesets.find(membership.rulesetVersion());
		if (rules.isEmpty()) {
			return;
		}
		// Attempts per nation: two nations deciding on the same revision have the same action signature.
		Map<UUID, Set<String>> attempted = new java.util.HashMap<>();
		for (int pass = 0; pass < rules.get().budget().maxConferencePasses(); pass++) {
			boolean acted = false;
			for (ComputerSeats.Seat seat : seats) {
				if (seat.eliminated()) {
					continue;
				}
				acted |= negotiateFor(gameId, computers.actor(seat.playerId()), rules.get(),
						attempted.computeIfAbsent(seat.playerId(), k -> new HashSet<>()));
			}
			if (!acted) {
				break;
			}
		}
		if (read(() -> gameAccess.membership(probe, gameId).paused())) {
			endDeadlock(gameId, seats);
		}
	}

	/**
	 * Last resort: a conference between two computer nations that still pauses the game after the
	 * passes above can make no more progress (every decision was refused or exhausted). Its requester
	 * ends it so the game never stays paused without a human involved. Conferences with a human
	 * principal stay open until the human acts, as the rules say.
	 */
	private void endDeadlock(UUID gameId, List<ComputerSeats.Seat> seats) {
		Map<UUID, CurrentPlayer> computerNations = new java.util.HashMap<>();
		DiplomacyViews.Overview any = null;
		for (ComputerSeats.Seat seat : seats) {
			if (seat.eliminated()) {
				continue;
			}
			CurrentPlayer actor = computers.actor(seat.playerId());
			DiplomacyViews.Overview overview = read(() -> diplomacy.overview(actor, gameId));
			computerNations.put(overview.yourNationId(), actor);
			any = overview;
		}
		if (any == null || any.conferences() == null) {
			return;
		}
		for (DiplomacyViews.ConferenceView c : any.conferences()) {
			if (!"ACTIVE".equals(c.status()) || !computerNations.containsKey(c.requesterNationId())
					|| !computerNations.containsKey(c.counterpartNationId())) {
				continue;
			}
			// A human approver still to decide is legitimate waiting, not a deadlock.
			if (c.approvals().stream().anyMatch(a -> !a.npc() && a.decision() == null)) {
				continue;
			}
			CurrentPlayer requester = computerNations.get(c.requesterNationId());
			UUID key = AiTurnRunner.key(gameId + ":" + c.id() + ":" + c.currentRevision() + ":deadlock");
			try {
				write(() -> diplomacy.cancelConference(requester, gameId, c.id(), key));
				log.info("Computer conference {} ended without agreement (no progress possible)", c.id());
			}
			catch (ApiException refused) {
				log.info("Computer conference {} could not be ended: {}", c.id(), refused.code());
			}
		}
	}

	private boolean negotiateFor(UUID gameId, CurrentPlayer actor, AiRules rules, Set<String> failed) {
		GameAccess.Membership membership = read(() -> gameAccess.membership(actor, gameId));
		if (!membership.inProgress() || membership.eliminated()) {
			return false;
		}
		DiplomacyViews.Overview overview = read(() -> diplomacy.overview(actor, gameId));
		if (!hasBusiness(overview)) {
			return false;
		}
		String memo = gameId + ":" + actor.id();
		String situation = fingerprint(overview) + "@" + membership.round();
		if (situation.equals(idle.get(memo))) {
			return false;
		}
		AiState state = read(() -> observer.observe(actor, gameId, membership.round(), membership.turnNumber()));
		boolean acted = false;
		for (AiAction action : AiBrain.planNegotiation(state, rules, failed)) {
			if (!failed.add(action.signature())) {
				continue;
			}
			UUID key = AiTurnRunner.key(gameId + ":" + state.me() + ":" + action.signature());
			try {
				write(() -> {
					executor.execute(actor, gameId, action, key);
					decisions.record(gameId, membership.round(), membership.turnNumber(), state.me(),
							AiTurnRunner.kind(action), action.reason(), details(action), clock.instant());
					return null;
				});
				acted = true;
				log.info("Computer {} round {}: {} — {}", state.meKey(), membership.round(), AiTurnRunner.kind(action),
						action.reason());
			}
			catch (ApiException refused) {
				log.info("Computer {}: {} refused ({})", state.meKey(), action.signature(), refused.code());
			}
			catch (RuntimeException bug) {
				log.warn("Computer {}: {} failed", state.meKey(), action.signature(), bug);
			}
			// Every conference action changes what the others see: look again before the next one.
			if (acted) {
				break;
			}
		}
		if (acted) {
			idle.remove(memo);
		}
		else {
			idle.put(memo, situation);
		}
		return acted;
	}

	/** Everything about open conferences that could change this nation's decision. */
	static String fingerprint(DiplomacyViews.Overview overview) {
		StringBuilder s = new StringBuilder();
		overview.conferences()
			.stream()
			.filter(c -> "REQUESTED".equals(c.status()) || "ACTIVE".equals(c.status()))
			.sorted(java.util.Comparator.comparing(c -> c.id().toString()))
			.forEach(c -> {
				s.append(c.id()).append(c.status()).append(c.currentRevision());
				c.approvals().forEach(a -> s.append(a.nationId()).append(a.decision()));
			});
		return s.toString();
	}

	/** Whether this nation has conference business at all (answer, decide, revise or lead). */
	static boolean hasBusiness(DiplomacyViews.Overview overview) {
		if (overview.conferences() == null) {
			return false;
		}
		return overview.conferences()
			.stream()
			.filter(c -> "REQUESTED".equals(c.status()) || "ACTIVE".equals(c.status()))
			.anyMatch(c -> c.canAnswer() || c.youMustDecide() || "ACTIVE".equals(c.status()) && c.canRevise());
	}

	private String details(AiAction action) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("action", action.signature());
		return jsonMapper.writeValueAsString(details);
	}

	private <T> T read(Supplier<T> work) {
		return reads.execute(status -> work.get());
	}

	private <T> T write(Supplier<T> work) {
		return writes.execute(status -> work.get());
	}

}
