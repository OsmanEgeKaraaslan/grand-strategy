package com.reach.strategy.ai.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Diplomacy of computer nations (docs/AI.md §8–10): war targets and declarations, alliances, calls to
 * arms, peace requests, peace terms and the evaluation of drafts. Rule-based and deterministic; no
 * opinion or reputation exists. Every action is an ordinary diplomatic command the server validates.
 */
public final class Statecraft {

	private Statecraft() {
	}

	// --- war -------------------------------------------------------------------------------------

	/** A neighbour worth attacking, judged by strength (including its likely helpers) and prize. */
	public record Target(UUID nation, long ratioBps, long prize) {
	}

	public static Optional<Target> warTarget(AiState state, AiRules rules, Strategy.Assessment a) {
		AiRules.Diplomacy d = rules.diplomacy();
		AiRules.Personality p = rules.personalityOf(state.meKey());
		long wars = state.diplomacy().wars().stream().filter(w -> w.sideOf(state.me()).isPresent()).count();
		if (state.round() < d.firstWarRound() || state.roundsLeft() <= d.noNewWarsInLastRounds() || wars >= d.maxWars()
				|| Strategy.RECOVER.equals(a.goal()) || Strategy.DEFEND.equals(a.goal()) || state.economy().inArrears()) {
			return Optional.empty();
		}
		long mine = a.myPower();
		Target best = null;
		for (UUID candidate : a.neighbours().stream().sorted().toList()) {
			AiState.Nation n = state.nations().get(candidate);
			if (n == null || n.eliminated() || !n.warBlockers().isEmpty() || !"PEACE".equals(n.status())) {
				continue;
			}
			long theirs = Strength.ofNation(state, candidate, rules);
			for (AiState.Alliance alliance : state.diplomacy().alliances()) {
				if ("ACTIVE".equals(alliance.status()) && alliance.involves(candidate) && !alliance.involves(state.me())) {
					theirs += Strength.ofNation(state, alliance.other(candidate), rules) * d.allyHelpExpectationBps() / 10_000;
				}
			}
			long ratio = Strength.ratioBps(mine, theirs);
			if (ratio < Strategy.warRatio(rules, p)) {
				continue;
			}
			long prize = 0;
			for (AiState.Province own : state.controlledBy(state.me())) {
				for (String key : own.neighbours()) {
					AiState.Province q = state.provinces().get(key);
					if (q != null && q.controller().equals(candidate)) {
						prize += 1 + q.population() / 1000;
					}
				}
			}
			if (prize == 0) {
				continue;
			}
			if (best == null || ratio * prize > best.ratioBps() * best.prize()) {
				best = new Target(candidate, ratio, prize);
			}
		}
		return Optional.ofNullable(best);
	}

	/** Declare only once the army stands ready at the target's border (staging), never by surprise from afar. */
	static List<AiAction> declareWar(AiState state, AiRules rules, Strategy.Assessment a, Optional<Target> target,
			Set<String> failed) {
		if (target.isEmpty()) {
			return List.of();
		}
		UUID enemy = target.get().nation();
		long staged = 0;
		for (AiState.Province own : state.controlledBy(state.me())) {
			boolean border = own.neighbours()
				.stream()
				.map(k -> state.provinces().get(k))
				.anyMatch(q -> q != null && q.controller().equals(enemy));
			if (border) {
				staged += Strength.of(state.unitsIn(own.key()).stream().filter(u -> u.nation().equals(state.me())).toList(), rules);
			}
		}
		if (staged * 10_000 < a.myPower() * 4000L) {
			return List.of();
		}
		// v8: invite every normal ally that is not tied to the target (each decides for itself).
		List<UUID> invite = !state.diplomacy().features().offensiveCallsToArms() ? List.of()
				: state.diplomacy()
					.alliances()
					.stream()
					.filter(x -> "ACTIVE".equals(x.status()) && "NORMAL".equals(x.kind()) && x.involves(state.me()))
					.map(x -> x.other(state.me()))
					.filter(ally -> !ally.equals(enemy) && state.diplomacy()
						.alliances()
						.stream()
						.noneMatch(x -> "ACTIVE".equals(x.status()) && x.involves(ally) && x.involves(enemy)))
					.sorted()
					.toList();
		AiAction action = new AiAction.DeclareWar(enemy, invite, "declare war on " + state.nations().get(enemy).name()
				+ ": strength " + target.get().ratioBps() / 100 + "% of theirs, " + target.get().prize() + " border prize"
				+ (invite.isEmpty() ? "" : ", inviting " + invite.size() + " ally(ies)"));
		return failed.contains(action.signature()) ? List.of() : List.of(action);
	}

	// --- alliances and calls ----------------------------------------------------------------------

	static List<AiAction> alliances(AiState state, AiRules rules, Strategy.Assessment a, Optional<Target> target,
			Set<String> failed) {
		AiRules.Diplomacy d = rules.diplomacy();
		AiRules.Personality p = rules.personalityOf(state.meKey());
		List<AiAction> actions = new ArrayList<>();
		long active = state.diplomacy()
			.alliances()
			.stream()
			.filter(x -> "ACTIVE".equals(x.status()) && x.involves(state.me()))
			.count();
		Set<UUID> enemies = state.enemies();
		// Answer proposals addressed to us.
		for (AiState.Alliance proposal : state.diplomacy().alliances()) {
			if (!proposal.canAccept()) {
				continue;
			}
			UUID other = proposal.other(state.me());
			boolean sharedThreat = a.strongestNeighbour() != null && !a.strongestNeighbour().equals(other)
					&& Strategy.neighbours(state, other).contains(a.strongestNeighbour());
			boolean commonEnemy = state.diplomacy()
				.wars()
				.stream()
				.anyMatch(w -> w.sideOf(other).isPresent()
						&& w.sideMembers(AiState.War.opposite(w.sideOf(other).get())).stream().anyMatch(enemies::contains));
			boolean target_ = target.isPresent() && target.get().nation().equals(other);
			boolean friendlyNeighbour = p.diplomacyBps() > 0 && a.neighbours().contains(other);
			boolean accept = active < d.maxAlliances() && !target_ && (sharedThreat || commonEnemy || friendlyNeighbour);
			AiAction action = new AiAction.AnswerAlliance(proposal.id(), accept,
					(accept ? "accept" : "reject") + " the alliance offered by " + state.nations().get(other).name()
							+ (sharedThreat ? " (shared threat)" : commonEnemy ? " (common enemy)" : ""));
			if (!failed.contains(action.signature())) {
				actions.add(action);
				if (accept) {
					active++;
				}
			}
		}
		// Seek a defensive partner against a threatening neighbour or an enemy.
		UUID threat = !enemies.isEmpty() ? enemies.stream().sorted().findFirst().orElse(null)
				: a.strongestNeighbourPower() * 10_000 > a.myPower() * (long) d.threatRatioBps() ? a.strongestNeighbour() : null;
		boolean pending = state.diplomacy()
			.alliances()
			.stream()
			.anyMatch(x -> "PROPOSED".equals(x.status()) && x.proposer().equals(state.me()));
		if (threat != null && active < d.maxAlliances() && !pending) {
			UUID best = null;
			long bestPower = 0;
			for (AiState.Nation n : state.nations().values().stream().sorted(Comparator.comparing(AiState.Nation::id)).toList()) {
				if (n.id().equals(state.me()) || n.id().equals(threat) || n.eliminated() || "NPC".equals(n.control())
						|| !n.allianceBlockers().isEmpty() || !"PEACE".equals(n.status())) {
					continue;
				}
				if (!Strategy.neighbours(state, n.id()).contains(threat)) {
					continue;
				}
				boolean refusedRecently = state.diplomacy()
					.alliances()
					.stream()
					.anyMatch(x -> "REJECTED".equals(x.status()) && x.proposer().equals(state.me()) && x.involves(n.id())
							&& x.proposedRound() > state.round() - d.allianceRetryRounds());
				if (refusedRecently) {
					continue;
				}
				long power = Strength.ofNation(state, n.id(), rules);
				if (power > bestPower) {
					bestPower = power;
					best = n.id();
				}
			}
			if (best != null) {
				AiAction action = new AiAction.ProposeAlliance(best, "DEFENSIVE", "propose a defensive pact to "
						+ state.nations().get(best).name() + " against " + state.nations().get(threat).name());
				if (!failed.contains(action.signature())) {
					actions.add(action);
				}
			}
		}
		// An expansionist breaks with a weak ally it means to conquer (war follows on a later turn).
		if (p.aggressionBps() > 0 && enemies.isEmpty() && Strategy.BUILDUP.equals(a.goal()) && target.isEmpty()) {
			for (AiState.Alliance alliance : state.diplomacy().alliances()) {
				if (!"ACTIVE".equals(alliance.status()) || !alliance.involves(state.me()) || !alliance.canEnd()) {
					continue;
				}
				UUID ally = alliance.other(state.me());
				long allyPower = Strength.ofNation(state, ally, rules);
				if (a.neighbours().contains(ally) && allyPower * 10_000 < a.myPower() * 4000L) {
					AiAction action = new AiAction.EndAlliance(alliance.id(),
							"end the alliance with the much weaker neighbour " + state.nations().get(ally).name());
					if (!failed.contains(action.signature())) {
						actions.add(action);
						break;
					}
				}
			}
		}
		return actions;
	}

	static List<AiAction> calls(AiState state, AiRules rules, Strategy.Assessment a, Set<String> failed) {
		List<AiAction> actions = new ArrayList<>();
		AiRules.Personality p = rules.personalityOf(state.meKey());
		// Answer calls addressed to us (on our turn).
		for (AiState.Call call : state.diplomacy().calls()) {
			if (!call.canAnswer()) {
				continue;
			}
			Optional<AiState.War> war = state.diplomacy().wars().stream().filter(w -> w.id().equals(call.warId())).findFirst();
			if (war.isEmpty()) {
				continue;
			}
			// The side we would join and the side we would fight (an invitation to attack joins the attackers).
			String ourSide = call.side() == null ? "DEFENDER" : call.side();
			List<UUID> attackers = war.get().sideMembers(AiState.War.opposite(ourSide));
			boolean truceBlocks = !state.diplomacy().features().truceAllowsCallsToArms();
			boolean blocked = attackers.stream().map(state.nations()::get).filter(Objects::nonNull)
				.anyMatch(n -> n.allied() || truceBlocks && n.truceUntilRound() != null || n.atWar());
			long attack = attackers.stream().mapToLong(n -> Strength.ofNation(state, n, rules)).sum();
			long defence = a.myPower()
					+ war.get().sideMembers(ourSide).stream().mapToLong(n -> Strength.ofNation(state, n, rules)).sum();
			int needed = rules.diplomacy().honourRatioBps() - p.diplomacyBps() / 4 - p.aggressionBps() / 6;
			boolean honour = !blocked && !Strategy.DEFEND.equals(a.goal()) && !Strategy.RECOVER.equals(a.goal())
					&& defence * 10_000 >= attack * needed;
			AiAction action = new AiAction.AnswerCall(call.id(), honour,
					(honour ? "honour" : "decline") + ("ATTACKER".equals(ourSide) ? " the invitation to attack of " : " the call to arms of ")
							+ state.nations().get(call.caller()).name() + " (our side " + defence + " vs theirs " + attack + ")");
			if (!failed.contains(action.signature())) {
				actions.add(action);
			}
		}
		// Call our allies when we are the attacked war leader.
		if (state.diplomacy().features().callsToArms()) {
			for (AiState.War war : state.diplomacy().wars()) {
				if (!war.defenderLeader().equals(state.me())) {
					continue;
				}
				for (AiState.Alliance alliance : state.diplomacy().alliances()) {
					if (!"ACTIVE".equals(alliance.status()) || !alliance.involves(state.me())) {
						continue;
					}
					UUID ally = alliance.other(state.me());
					boolean called = state.diplomacy().calls().stream().anyMatch(c -> c.warId().equals(war.id()) && c.ally().equals(ally));
					if (war.sideOf(ally).isPresent() || called) {
						continue;
					}
					AiAction action = new AiAction.CallToArms(war.id(), ally,
							"call " + state.nations().get(ally).name() + " to arms");
					if (!failed.contains(action.signature())) {
						actions.add(action);
					}
				}
			}
		}
		return actions;
	}

	/** Conservative support of a weaker partner fighting a common enemy. */
	static List<AiAction> gifts(AiState state, AiRules rules, Strategy.Assessment a, Set<String> failed) {
		AiRules.Diplomacy d = rules.diplomacy();
		long treasury = state.economy().treasuryMg();
		if (state.diplomacy().giftMaxMg() <= 0 || treasury < d.giftMinTreasuryMg() || state.enemies().isEmpty()) {
			return List.of();
		}
		for (UUID partner : state.friends().stream().sorted().toList()) {
			AiState.Nation n = state.nations().get(partner);
			if (n == null || n.eliminated() || n.atWar()) {
				continue;
			}
			boolean sharedWar = state.diplomacy()
				.wars()
				.stream()
				.anyMatch(w -> w.sideOf(partner).isPresent() && w.sideOf(partner).equals(w.sideOf(state.me())));
			long power = Strength.ofNation(state, partner, rules);
			boolean recent = state.diplomacy()
				.gifts()
				.stream()
				.anyMatch(g -> g.sender().equals(state.me()) && g.recipient().equals(partner)
						&& g.round() > state.round() - d.giftEveryRounds());
			if (!sharedWar || recent || power * 2 >= a.myPower()) {
				continue;
			}
			long amount = Math.min(state.diplomacy().giftMaxMg(), Strategy.spendable(state, rules) * d.giftShareBps() / 10_000);
			if (amount < 1000) {
				continue;
			}
			AiAction action = new AiAction.SendGold(partner, amount,
					"support " + n.name() + " against our common enemy with " + amount / 1000 + " gold");
			if (!failed.contains(action.signature())) {
				return List.of(action);
			}
		}
		return List.of();
	}

	// --- peace -------------------------------------------------------------------------------------

	/** How a war stands for the deciding nation. */
	/** @param favoured clearly the stronger side (expects to win if the war goes on) */
	public record Standing(String side, int score, int duration, long ourPower, long theirPower, int weLost,
			int weTook, boolean losing, boolean winning, boolean favoured) {
	}

	public static Standing standing(AiState state, AiRules rules, AiState.War war) {
		String side = war.sideOf(state.me()).orElseThrow();
		int score = war.scoreFor(side);
		long ours = war.sideMembers(side).stream().mapToLong(n -> Strength.ofNation(state, n, rules)).sum();
		long theirs = war.sideMembers(AiState.War.opposite(side)).stream().mapToLong(n -> Strength.ofNation(state, n, rules)).sum();
		Set<UUID> them = new HashSet<>(war.sideMembers(AiState.War.opposite(side)));
		Set<UUID> us = new HashSet<>(war.sideMembers(side));
		int lost = 0;
		int took = 0;
		for (AiState.Province p : state.provinces().values()) {
			if (p.owner().equals(state.me()) && them.contains(p.controller())) {
				lost++;
			}
			if (them.contains(p.owner()) && us.contains(p.controller())) {
				took++;
			}
		}
		AiRules.Diplomacy d = rules.diplomacy();
		boolean losing = score <= d.losingScore() || lost >= 2 || ours * 10_000 < theirs * 6000L;
		boolean winning = !losing && (score >= d.winningScore() || took >= 1 && ours >= theirs);
		boolean favoured = !losing && ours * 10_000 >= theirs * (long) d.favouredRatioBps();
		return new Standing(side, score, state.round() - war.declaredRound(), ours, theirs, lost, took, losing, winning,
				favoured);
	}

	/** On its turn: ask for peace when the war is lost, won, stuck or the game ends soon. */
	static List<AiAction> peace(AiState state, AiRules rules, Set<String> failed) {
		if (!state.diplomacy().features().peaceConferences()) {
			return List.of();
		}
		AiRules.Diplomacy d = rules.diplomacy();
		AiRules.Personality p = rules.personalityOf(state.meKey());
		boolean conferenceRunning = state.diplomacy().conferences().stream().anyMatch(AiState.Conference::active);
		int retry = Math.max(1, d.peaceAfterRounds() / 3);
		List<AiAction> actions = new ArrayList<>();
		for (AiState.War war : state.diplomacy().wars()) {
			if (war.sideOf(state.me()).isEmpty() || conferenceRunning) {
				continue;
			}
			boolean requested = state.diplomacy()
				.conferences()
				.stream()
				.anyMatch(c -> c.warId().equals(war.id()) && c.requester().equals(state.me())
						&& (!"REJECTED".equals(c.status()) || c.requestedRound() > state.round() - retry));
			if (requested) {
				continue;
			}
			Standing s = standing(state, rules, war);
			int patience = d.peaceAfterRounds() - p.diplomacyBps() / 1000 + p.aggressionBps() / 1000;
			boolean endgame = state.roundsLeft() <= d.endgameRounds() + 1 && s.weTook() > 0;
			boolean wants;
			if (rules.peace() == null) {
				wants = s.losing() || endgame || s.duration() >= patience
						|| s.winning() && s.weTook() >= 2 && s.duration() >= patience / 2;
			}
			else {
				// v8: a holder of enemy land asks to turn it into a treaty; a nation that can still take
				// land does not ask for an empty peace until the war is a stalemate.
				Outlook o = outlook(state, rules, war, s);
				boolean pressing = s.favoured() && (o.canTakeMore() || o.siegeNearlyDone())
						&& s.duration() < rules.peace().stalemateRounds();
				wants = s.losing() || endgame
						|| o.heldValueMg() > 0 && (s.duration() >= patience / 2 || o.gainsAtRisk())
						|| o.heldValueMg() == 0 && s.duration() >= patience && !pressing;
			}
			if (!wants) {
				continue;
			}
			String kind;
			if (war.leads(state.me())) {
				kind = "GENERAL";
			}
			else if (state.diplomacy().features().separatePeace() && s.losing()) {
				kind = "SEPARATE";
			}
			else {
				continue;
			}
			AiAction action = new AiAction.RequestConference(war.id(), kind,
					"ask for " + kind.toLowerCase() + " peace: score " + s.score() + ", " + s.duration() + " rounds, lost "
							+ s.weLost() + ", took " + s.weTook());
			if (!failed.contains(action.signature())) {
				actions.add(action);
				break;
			}
		}
		return actions;
	}

	/**
	 * Conference business. The counterpart answers a request. On a revision that needs its approval a
	 * nation approves if acceptable; otherwise a negotiator counters with its own terms, conceding one
	 * demand per draft it has already made, and a nation that may not draft rejects. A negotiator whose
	 * own draft was rejected moderates it, and ends the conference after
	 * {@code maxRevisionsPerConference} drafts. Every action is tied to the revision it answers.
	 */
	static List<AiAction> negotiate(AiState state, AiRules rules, Set<String> failed) {
		List<AiAction> actions = new ArrayList<>();
		int max = rules.budget().maxRevisionsPerConference();
		for (AiState.Conference c : state.diplomacy().conferences()) {
			Optional<AiState.War> war = state.diplomacy().wars().stream().filter(w -> w.id().equals(c.warId())).findFirst();
			if (war.isEmpty()) {
				continue;
			}
			if (c.canAnswer()) {
				Optional<Standing> s = war.get().sideOf(state.me()).map(x -> standing(state, rules, war.get()));
				// A nation far ahead with the game still young may refuse to talk yet.
				boolean young = s.isPresent() && s.get().duration() < rules.diplomacy().peaceAfterRounds() / 2
						&& state.roundsLeft() > rules.diplomacy().endgameRounds() + 2;
				boolean refuse = young && (s.get().favoured() || s.get().score() >= 2 * rules.diplomacy().winningScore());
				add(actions, failed, new AiAction.AnswerConference(c.id(), !refuse,
						(refuse ? "refuse" : "accept") + " the peace conference"));
				continue;
			}
			if (!c.active()) {
				continue;
			}
			boolean principal = c.requester().equals(state.me()) || c.counterpart().equals(state.me());
			// Revision 1 (a white peace) is created by the system in the requester's name.
			int drafts = c.revisionsByMe() - (c.requester().equals(state.me()) ? 1 : 0);
			boolean mine = state.me().equals(c.lastAuthor()) && c.currentRevision() > 1;
			if (c.mustDecide()) {
				Decision decision = evaluate(state, rules, c, war.get());
				if (!decision.approve() && principal && c.canRevise() && drafts < max) {
					List<AiState.Term> counter = moderate(desiredTerms(state, rules, c, war.get()), drafts);
					if (!sameTerms(counter, c.terms())) {
						add(actions, failed, new AiAction.SubmitDraft(c.id(), c.currentRevision(), counter,
								"counter with " + describe(counter) + " (" + decision.why() + ")"));
						continue;
					}
				}
				add(actions, failed, new AiAction.DecideDraft(c.id(), c.currentRevision(), decision.approve(),
						(decision.approve() ? "approve" : "reject") + " revision " + c.currentRevision() + ": " + decision.why()));
				continue;
			}
			if (principal && c.canRevise() && mine && c.approvals().containsValue("REJECTED")) {
				if (drafts < max) {
					List<AiState.Term> softer = moderate(desiredTerms(state, rules, c, war.get()), drafts);
					if (!sameTerms(softer, c.terms())) {
						add(actions, failed, new AiAction.SubmitDraft(c.id(), c.currentRevision(), softer,
								"moderate to " + describe(softer) + " after a rejection"));
						continue;
					}
				}
				add(actions, failed, new AiAction.CancelConference(c.id(), "no agreement after " + drafts + " drafts"));
			}
		}
		return actions;
	}

	private static void add(List<AiAction> actions, Set<String> failed, AiAction action) {
		if (!failed.contains(action.signature())) {
			actions.add(action);
		}
	}

	/**
	 * What the deciding nation asks for. Winning: provinces of the other side that its own side
	 * already occupies, ceded to the occupier (never anything from its own allies), more the higher
	 * the score; with a big lead also a tribute. Losing: it gives up its own provinces the enemy holds,
	 * no more. Otherwise a white peace.
	 */
	static List<AiState.Term> desiredTerms(AiState state, AiRules rules, AiState.Conference c, AiState.War war) {
		Optional<String> mySide = war.sideOf(state.me());
		if (mySide.isEmpty()) {
			return List.of();
		}
		return desiredTerms(state, rules, c, war, standing(state, rules, war));
	}

	private static List<AiState.Term> desiredTerms(AiState state, AiRules rules, AiState.Conference c, AiState.War war,
			Standing s) {
		Optional<String> mySide = war.sideOf(state.me());
		Map<UUID, String> parties = parties(c, war);
		String side = mySide.get();
		List<AiState.Term> terms = new ArrayList<>();
		AiRules.Diplomacy d = rules.diplomacy();
		boolean holder = rules.peace() != null && !s.losing() && s.weTook() > 0;
		if (s.winning() || s.score() > 0 && s.weTook() > 0 || holder) {
			// v8: everything it holds may be asked for (the evaluation values exactly that).
			int limit = rules.peace() != null ? d.maxDemandedProvinces()
					: Math.min(d.maxDemandedProvinces(), 1 + Math.max(0, s.score()) / 15);
			List<AiState.Province> prizes = state.provinces()
				.values()
				.stream()
				.filter(p -> parties.containsKey(p.owner()) && !parties.get(p.owner()).equals(side))
				.filter(p -> parties.containsKey(p.controller()) && parties.get(p.controller()).equals(side))
				.filter(p -> state.diplomacy().features().capitalsCedable() || !isCapital(state, p))
				.sorted(Comparator.comparing((AiState.Province p) -> -p.population()).thenComparing(AiState.Province::key))
				.toList();
			for (AiState.Province p : prizes) {
				if (terms.size() >= Math.min(limit, state.diplomacy().maxPeaceTerms() - 1)) {
					break;
				}
				// A capital only when that nation has nothing else left to give.
				if (isCapital(state, p) && prizes.stream().filter(q -> q.owner().equals(p.owner())).count()
						< state.ownedBy(p.owner()).size()) {
					continue;
				}
				terms.add(new AiState.Term("PROVINCE", p.owner(), p.controller(), p.key(), null, null, null, null));
			}
			if (s.score() >= 2 * d.winningScore() && war.leads(state.me())) {
				UUID loser = war.leaderOf(AiState.War.opposite(side));
				if (c.separate() && c.requester() != null && !c.requester().equals(state.me())) {
					loser = c.requester();
				}
				if (parties.containsKey(loser)) {
					terms.add(new AiState.Term("TRIBUTE", loser, state.me(), null, null, null, null, null));
				}
			}
		}
		else if (s.losing()) {
			int limit = Math.min(d.maxDemandedProvinces(), 1 + Math.max(0, -s.score()) / 15);
			state.ownedBy(state.me())
				.stream()
				.filter(p -> parties.containsKey(p.controller()) && !parties.get(p.controller()).equals(side))
				.filter(p -> state.diplomacy().features().capitalsCedable() || !isCapital(state, p))
				.filter(p -> !isCapital(state, p))
				.sorted(Comparator.comparing(AiState.Province::population).thenComparing(AiState.Province::key))
				.limit(limit)
				.forEach(p -> terms.add(new AiState.Term("PROVINCE", state.me(), p.controller(), p.key(), null, null, null,
						null)));
		}
		return terms;
	}

	/** After a rejection: drop the most valuable demand (one fewer each time), keep the rest. */
	static List<AiState.Term> moderate(List<AiState.Term> desired, int concessions) {
		int keep = Math.max(0, desired.size() - concessions);
		return List.copyOf(desired.subList(desired.size() - keep, desired.size()));
	}

	/** Who may give or receive, with their side (as the server's validation sees it). */
	static Map<UUID, String> parties(AiState.Conference c, AiState.War war) {
		if (!c.separate()) {
			return war.participants();
		}
		String exitingSide = war.participants().get(c.requester());
		Map<UUID, String> parties = new java.util.HashMap<>();
		if (exitingSide == null) {
			return parties;
		}
		parties.put(c.requester(), exitingSide);
		war.participants().forEach((n, side) -> {
			if (!side.equals(exitingSide)) {
				parties.put(n, side);
			}
		});
		return parties;
	}

	public record Decision(boolean approve, long valueMg, String why) {
	}

	/**
	 * Whether the deciding nation approves the current revision. Gains and losses are valued in gold
	 * (provinces with their people, reparations, tribute, people). It accepts a loss up to what it
	 * would probably lose anyway — its provinces the enemy already holds, more when the war is going
	 * badly — but not more; it never agrees to its own elimination while it still holds land of its
	 * own; a winner wants at least something unless the war has dragged on or the game ends soon.
	 */
	public static Decision evaluate(AiState state, AiRules rules, AiState.Conference c, AiState.War war) {
		AiRules.Diplomacy d = rules.diplomacy();
		long value = 0;
		int ceded = 0;
		long cededControlled = 0;
		for (AiState.Term t : c.terms()) {
			long v = termValue(state, rules, t);
			if (t.to().equals(state.me())) {
				value += v;
			}
			if (t.from().equals(state.me())) {
				value -= v;
				if ("PROVINCE".equals(t.type())) {
					ceded++;
					AiState.Province p = state.provinces().get(t.provinceKey());
					if (p != null && p.controller().equals(state.me())) {
						cededControlled += v;
					}
				}
			}
		}
		int owned = state.ownedBy(state.me()).size();
		boolean stillHoldsLand = state.ownedBy(state.me()).stream().anyMatch(p -> p.controller().equals(state.me()));
		if (ceded >= owned && owned > 0 && stillHoldsLand) {
			return new Decision(false, value, "it would leave us no province while we still hold our land");
		}
		Optional<String> side = war.sideOf(state.me());
		if (side.isEmpty()) {
			return new Decision(value >= 0, value, value >= 0 ? "it costs us nothing" : "we would only lose");
		}
		Standing s = standing(state, rules, war);
		long occupiedValue = 0;
		for (AiState.Province p : state.ownedBy(state.me())) {
			if (!p.controller().equals(state.me())) {
				occupiedValue += provinceValue(rules, p);
			}
		}
		AiRules.Personality personality = rules.personalityOf(state.meKey());
		if (s.losing()) {
			// Land we still control is worth twice as much to keep.
			long acceptable = occupiedValue + d.tributeValueMg() + d.provinceValueMg() * personality.diplomacyBps() / 10_000;
			boolean desperate = s.ourPower() * 10_000 < s.theirPower() * 4000L;
			long loss = -value + cededControlled;
			boolean approve = loss <= acceptable || desperate && loss <= acceptable * 2;
			return new Decision(approve, value, (approve ? "an acceptable price" : "too high a price") + " while losing (cost "
					+ -value / 1000 + " gold-equivalent)");
		}
		if (rules.peace() != null) {
			return evaluateV8(state, rules, c, war, s, value, occupiedValue);
		}
		if (s.winning() || s.favoured()) {
			boolean tired = s.duration() >= d.peaceAfterRounds() || state.roundsLeft() <= d.endgameRounds() + 1;
			boolean approve = value > 0 || value >= 0 && tired;
			return new Decision(approve, value, approve ? "we gain " + value / 1000 + " gold-equivalent" : "we are winning");
		}
		boolean approve = value >= -d.provinceValueMg() / 2 || -value <= occupiedValue;
		return new Decision(approve, value, approve ? "a fair settlement" : "not a fair settlement");
	}

	/**
	 * v8: what ending the war now means beyond the score.
	 *
	 * @param heldValueMg value of the enemy provinces the deciding nation itself holds and would ask
	 * for (exactly what {@link #desiredTerms} demands for it)
	 * @param gainsAtRisk it holds land but the enemy side is stronger by {@code gainsAtRiskRatioBps}
	 * @param canTakeMore favoured, and an enemy province borders land it controls
	 * @param siegeNearlyDone one of its sieges of an enemy city is about to succeed
	 */
	public record Outlook(long heldValueMg, boolean gainsAtRisk, boolean canTakeMore, boolean siegeNearlyDone) {
	}

	public static Outlook outlook(AiState state, AiRules rules, AiState.War war, Standing s) {
		AiRules.Peace peace = rules.peace();
		String side = war.sideOf(state.me()).orElseThrow();
		Set<UUID> them = new HashSet<>(war.sideMembers(AiState.War.opposite(side)));
		AiState.Conference general = new AiState.Conference(null, war.id(), "GENERAL", null, null, "ACTIVE", 0, List.of(),
				Map.of(), List.of(), false, true, false, true, 0, null, 0);
		long held = desiredTerms(state, rules, general, war, s)
			.stream()
			.filter(t -> "PROVINCE".equals(t.type()) && t.to().equals(state.me()))
			.mapToLong(t -> termValue(state, rules, t))
			.sum();
		boolean atRisk = held > 0 && s.theirPower() * 10_000 > s.ourPower() * (long) peace.gainsAtRiskRatioBps();
		boolean canTakeMore = s.favoured() && state.controlledBy(state.me())
			.stream()
			.flatMap(own -> own.neighbours().stream())
			.map(state.provinces()::get)
			.anyMatch(q -> q != null && them.contains(q.owner()) && them.contains(q.controller()));
		boolean siegeSoon = state.sieges()
			.stream()
			.anyMatch(x -> x.besieger().equals(state.me()) && them.contains(x.defender()) && x.roundsLeft() != null
					&& x.roundsLeft() <= peace.siegeNearlyDoneRounds());
		return new Outlook(held, atRisk, canTakeMore, siegeSoon);
	}

	/** The share of its held land a holder still insists on (v8); 0 once the war has dragged on long enough. */
	static int requiredShareBps(AiState state, AiRules rules, Standing s, Outlook o) {
		AiRules.Peace peace = rules.peace();
		AiRules.Diplomacy d = rules.diplomacy();
		int share = peace.keepGainsShareBps() - Math.max(0, s.duration() - d.peaceAfterRounds()) * peace.concessionPerRoundBps();
		if (o.gainsAtRisk()) {
			share /= 2;
		}
		if (state.roundsLeft() <= d.endgameRounds() + 1) {
			share /= 2;
		}
		return Math.max(0, share);
	}

	/** v8 evaluation of a side that is not losing (see {@link AiRules.Peace}). */
	private static Decision evaluateV8(AiState state, AiRules rules, AiState.Conference c, AiState.War war, Standing s,
			long value, long occupiedValue) {
		AiRules.Diplomacy d = rules.diplomacy();
		if (state.me().equals(c.lastAuthor()) && c.currentRevision() > 1) {
			return new Decision(true, value, "our own draft");
		}
		Outlook o = outlook(state, rules, war, s);
		boolean endgame = state.roundsLeft() <= d.endgameRounds() + 1;
		if (o.heldValueMg() > 0) {
			int share = requiredShareBps(state, rules, s, o);
			long needed = o.heldValueMg() * share / 10_000;
			boolean approve = share == 0 ? value >= 0 || -value <= occupiedValue : value > 0 && value >= needed;
			return new Decision(approve, value, approve
					? "we keep " + value / 1000 + " of the " + o.heldValueMg() / 1000 + " gold-equivalent we hold"
					: "we hold " + o.heldValueMg() / 1000 + " gold-equivalent of their land and want at least " + needed / 1000
							+ (o.gainsAtRisk() ? " (our gains are at risk)" : ""));
		}
		boolean pressing = s.favoured() && (o.canTakeMore() || o.siegeNearlyDone()) && s.duration() < rules.peace().stalemateRounds()
				&& !endgame;
		if (pressing) {
			boolean approve = value > 0;
			return new Decision(approve, value, approve ? "we gain " + value / 1000 + " gold-equivalent"
					: o.siegeNearlyDone() ? "our siege is about to succeed" : "we can still take their land");
		}
		boolean approve = value >= -d.provinceValueMg() / 2 || -value <= occupiedValue;
		return new Decision(approve, value, approve ? "a fair settlement of a stalemate" : "not a fair settlement");
	}

	static long termValue(AiState state, AiRules rules, AiState.Term t) {
		AiRules.Diplomacy d = rules.diplomacy();
		return switch (t.type()) {
			case "PROVINCE" -> {
				AiState.Province p = state.provinces().get(t.provinceKey());
				yield p == null ? d.provinceValueMg() : provinceValue(rules, p);
			}
			case "GOLD" -> t.amountMg() == null ? 0 : t.amountMg();
			case "TRIBUTE" -> d.tributeValueMg();
			case "POPULATION" -> t.people() == null ? 0 : t.people() * d.populationValueMgPerThousand() / 1000;
			default -> 0;
		};
	}

	static long provinceValue(AiRules rules, AiState.Province p) {
		AiRules.Diplomacy d = rules.diplomacy();
		return d.provinceValueMg() + (long) p.population() / 1000 * d.populationValueMgPerThousand();
	}

	private static boolean isCapital(AiState state, AiState.Province p) {
		AiState.Nation owner = state.nations().get(p.owner());
		return owner != null && p.key().equals(owner.capitalKey());
	}

	static boolean sameTerms(List<AiState.Term> a, List<AiState.Term> b) {
		return new HashSet<>(a).equals(new HashSet<>(b)) && a.size() == b.size();
	}

	private static String describe(List<AiState.Term> terms) {
		if (terms.isEmpty()) {
			return "a white peace";
		}
		long provinces = terms.stream().filter(t -> "PROVINCE".equals(t.type())).count();
		boolean tribute = terms.stream().anyMatch(t -> "TRIBUTE".equals(t.type()));
		return provinces + " province(s)" + (tribute ? " and a tribute" : "");
	}

}
