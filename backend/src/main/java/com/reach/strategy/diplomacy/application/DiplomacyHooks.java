package com.reach.strategy.diplomacy.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.diplomacy.domain.DiplomacyEventLog;
import com.reach.strategy.diplomacy.domain.RelationsRepository;
import com.reach.strategy.diplomacy.domain.TreatyRepository;
import com.reach.strategy.diplomacy.rules.Diplomacy;
import com.reach.strategy.diplomacy.rules.DiplomacyRules;
import com.reach.strategy.diplomacy.rules.PeaceTerms;
import com.reach.strategy.diplomacy.rules.Victory;
import com.reach.strategy.diplomacy.rules.WarScore;
import com.reach.strategy.economy.api.EconomyApi;
import com.reach.strategy.game.api.GameStartInitializer;
import com.reach.strategy.game.api.RoundPhase;
import com.reach.strategy.game.api.VictoryCondition;
import com.reach.strategy.military.api.Hostility;
import com.reach.strategy.military.api.WarEvents;
import com.reach.strategy.world.api.WorldApi;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The diplomacy module's contributions to other modules' processes: relations for movement and
 * sieges, war-score facts from battles and sieges, diplomacy for new v5 games, tribute and
 * proposal expiry at round end, and the round-limit victory condition.
 */
@Configuration
class DiplomacyHooks {

	@Bean
	Hostility diplomaticRelations(DiplomacyState state) {
		return gameId -> state.load(gameId).map(DiplomacyState.Picture::hostility).orElse(Hostility.Relations.TOTAL_WAR);
	}

	@Bean
	WarEvents warScoreRecorder(DiplomacyState state, RelationsRepository relations, Clock clock) {
		return new WarScoreRecorder(state, relations, clock);
	}

	@Bean
	GameStartInitializer diplomacyStartInitializer(DiplomacyRulesets rulesets, RelationsRepository relations,
			Clock clock) {
		return (gameId, mapId, rulesetVersion) -> {
			// Every pair of nations starts at peace; war needs a declaration.
			if (rulesets.find(rulesetVersion).isPresent()) {
				relations.enable(gameId, rulesetVersion, clock.instant());
			}
		};
	}

	@Bean
	RoundPhase tributePhase(DiplomacyState state, TreatyRepository treaties, EconomyApi economy,
			DiplomacyEventLog events, JsonMapper jsonMapper) {
		return new TributePhase(state, treaties, economy, events, jsonMapper);
	}

	@Bean
	RoundPhase diplomacyExpiryPhase(DiplomacyState state, RelationsRepository relations, TreatyRepository treaties) {
		return new ExpiryPhase(state, relations, treaties);
	}

	@Bean
	VictoryCondition roundLimitVictory(DiplomacyState state, WorldApi world, EconomyApi economy,
			TreatyRepository treaties, DiplomacyEventLog events) {
		return new RoundLimitVictory(state, world, economy, treaties, events);
	}

	/**
	 * Records each battle and successful siege between nations at war once, with points fixed by the
	 * ruleset at that moment (only the winner of a battle scores).
	 */
	static class WarScoreRecorder implements WarEvents {

		private final DiplomacyState state;
		private final RelationsRepository relations;
		private final Clock clock;

		WarScoreRecorder(DiplomacyState state, RelationsRepository relations, Clock clock) {
			this.state = state;
			this.relations = relations;
			this.clock = clock;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void battleFought(UUID gameId, Battle battle) {
			state.load(gameId).ifPresent(picture -> picture
				.activeWarBetween(battle.attackerNationId(), battle.defenderNationId())
				.ifPresent(war -> {
					UUID winner = battle.attackerWon() ? battle.attackerNationId() : battle.defenderNationId();
					int enemyLosses = battle.attackerWon() ? battle.defenderLosses() : battle.attackerLosses();
					String side = picture.sideOf(war.id(), winner).orElseThrow();
					int points = WarScore.battlePoints(enemyLosses, picture.rules().warScore());
					relations.insertScoreEvent(gameId, new RelationsRepository.ScoreEventRow(war.id(), side, "BATTLE",
							battle.battleId(), battle.round(), enemyLosses, points), clock.instant());
				}));
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void siegeWon(UUID gameId, SiegeWon siege) {
			state.load(gameId).ifPresent(picture -> picture
				.activeWarBetween(siege.besiegerNationId(), siege.defenderNationId())
				.ifPresent(war -> {
					String side = picture.sideOf(war.id(), siege.besiegerNationId()).orElseThrow();
					relations.insertScoreEvent(gameId, new RelationsRepository.ScoreEventRow(war.id(), side, "SIEGE",
							siege.siegeId(), siege.round(), 0, WarScore.siegePoints(picture.rules().warScore())),
							clock.instant());
				}));
		}

	}

	/**
	 * Step TRIBUTE (docs/DIPLOMACY.md §5.3): each active tribute, in id order, charges
	 * {@code floor(payer's gross income this round × rate / 10,000)}; the payer pays what its
	 * treasury holds after settlement (PROVISIONAL shortfall policy: the rest is forgiven, never
	 * debt), and exactly that arrives at the receiver. Each tribute is charged once per round (the
	 * payment's primary key) and completes after its last round.
	 */
	static class TributePhase implements RoundPhase {

		private final DiplomacyState state;
		private final TreatyRepository treaties;
		private final EconomyApi economy;
		private final DiplomacyEventLog events;
		private final JsonMapper jsonMapper;

		TributePhase(DiplomacyState state, TreatyRepository treaties, EconomyApi economy, DiplomacyEventLog events,
				JsonMapper jsonMapper) {
			this.state = state;
			this.treaties = treaties;
			this.economy = economy;
			this.events = events;
			this.jsonMapper = jsonMapper;
		}

		@Override
		public Step step() {
			return Step.TRIBUTE;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void apply(RoundContext context) {
			Optional<DiplomacyState.Picture> picture = state.load(context.gameId());
			if (picture.isEmpty()) {
				return;
			}
			treaties.tributes(context.gameId())
				.stream()
				.filter(TreatyRepository.TributeRow::active)
				.filter(t -> t.firstRound() <= context.round())
				.sorted(Comparator.comparing(TreatyRepository.TributeRow::id))
				.forEach(t -> {
					long gross = economy.grossIncome(context.gameId(), t.payer(), context.round()).orElse(0);
					// The rate stored with the tribute applies, so a later ruleset file never changes a running one.
					long due = PeaceTerms.tributeDue(gross, new DiplomacyRules.TributeRules(t.rateBps(), t.roundsTotal()));
					long paid = PeaceTerms.tributePaid(due, economy.treasury(t.payer()).treasuryMg());
					if (paid > 0) {
						economy.transfer(t.payer(), t.receiver(), paid);
					}
					boolean last = t.roundsCharged() + 1 == t.roundsTotal();
					treaties.recordPayment(context.gameId(),
							new TreatyRepository.PaymentRow(t.id(), context.round(), gross, due, paid), last, context.now());
					Map<String, Object> details = new LinkedHashMap<>();
					details.put("tributeId", t.id());
					details.put("round", t.roundsCharged() + 1);
					details.put("rounds", t.roundsTotal());
					details.put("grossIncomeMg", gross);
					details.put("dueMg", due);
					details.put("paidMg", paid);
					events.record(context.gameId(), context.round(), 0, "TRIBUTE_PAID", t.payer(), t.receiver(), null,
							jsonMapper.writeValueAsString(details), context.now());
					if (last) {
						events.record(context.gameId(), context.round(), 0, "TRIBUTE_COMPLETED", t.payer(), t.receiver(),
								null, jsonMapper.writeValueAsString(Map.of("tributeId", t.id(),
										"totalPaidMg", t.totalPaidMg() + paid)), context.now());
					}
				});
		}

	}

	/** Step DIPLOMACY: unanswered alliance and peace proposals expire (docs/DIPLOMACY.md §3.1, §5.1). */
	static class ExpiryPhase implements RoundPhase {

		private final DiplomacyState state;
		private final RelationsRepository relations;
		private final TreatyRepository treaties;

		ExpiryPhase(DiplomacyState state, RelationsRepository relations, TreatyRepository treaties) {
			this.state = state;
			this.relations = relations;
			this.treaties = treaties;
		}

		@Override
		public Step step() {
			return Step.DIPLOMACY;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void apply(RoundContext context) {
			Optional<DiplomacyState.Picture> picture = state.load(context.gameId());
			if (picture.isEmpty()) {
				return;
			}
			DiplomacyRules rules = picture.get().rules();
			picture.get()
				.alliances()
				.stream()
				.filter(a -> "PROPOSED".equals(a.status()))
				.filter(a -> Diplomacy.expires(a.proposedRound(), context.round(), rules))
				.forEach(a -> relations.closeAlliance(context.gameId(), a.id(), "PROPOSED", "EXPIRED", "EXPIRED",
						context.round(), context.now()));
			treaties.pendingProposals(context.gameId())
				.stream()
				.filter(p -> Diplomacy.expires(p.createdRound(), context.round(), rules))
				.forEach(p -> treaties.closeProposal(context.gameId(), p.id(), "EXPIRED", context.round(), context.now()));
		}

	}

	/**
	 * PROVISIONAL Q-T2: after the ruleset's last round the game ends; standings by legal ownership
	 * ({@link Victory}). Occupied provinces count for their legal owner, never the occupier.
	 */
	static class RoundLimitVictory implements VictoryCondition {

		private final DiplomacyState state;
		private final WorldApi world;
		private final EconomyApi economy;
		private final TreatyRepository treaties;
		private final DiplomacyEventLog events;

		RoundLimitVictory(DiplomacyState state, WorldApi world, EconomyApi economy, TreatyRepository treaties,
				DiplomacyEventLog events) {
			this.state = state;
			this.world = world;
			this.economy = economy;
			this.treaties = treaties;
			this.events = events;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public Optional<Outcome> evaluate(UUID gameId, int completedRound, String rulesetVersion) {
			Optional<DiplomacyState.Picture> picture = state.load(gameId);
			if (picture.isEmpty() || !Victory.due(completedRound, picture.get().rules().victory())) {
				return Optional.empty();
			}
			return Optional.of(outcome(gameId, picture.get(), completedRound, "ROUND_LIMIT", null));
		}

		/**
		 * Ruleset v6: domination — the only nation not eliminated wins at once; and a game in which every
		 * human player has been eliminated ends with the score standings (NPC nations cannot play on).
		 */
		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public Optional<Outcome> evaluateNow(UUID gameId, int currentRound, String rulesetVersion) {
			Optional<DiplomacyState.Picture> picture = state.load(gameId);
			if (picture.isEmpty() || !picture.get().rules().features().elimination()) {
				return Optional.empty();
			}
			List<Victory.NationFacts> facts = facts(gameId, world.snapshot(gameId));
			Optional<UUID> dominator = Victory.dominator(facts);
			if (dominator.isPresent() && picture.get().rules().victory().domination()) {
				return Optional.of(outcome(gameId, picture.get(), currentRound, "DOMINATION", dominator.get()));
			}
			boolean humansLeft = world.snapshot(gameId).nations().stream().anyMatch(n -> !n.npc() && !n.eliminated());
			if (!humansLeft) {
				return Optional.of(outcome(gameId, picture.get(), currentRound, "NO_HUMAN_PLAYERS", null));
			}
			return Optional.empty();
		}

		private List<Victory.NationFacts> facts(UUID gameId, WorldApi.WorldSnapshot snapshot) {
			Map<UUID, int[]> provinces = new HashMap<>();
			Map<UUID, Long> population = new HashMap<>();
			snapshot.nations().forEach(n -> {
				provinces.put(n.id(), new int[1]);
				population.put(n.id(), 0L);
			});
			snapshot.provinces().forEach(p -> {
				provinces.get(p.ownerNationId())[0]++;
				population.merge(p.ownerNationId(), (long) p.population(), Long::sum);
			});
			return snapshot.nations()
				.stream()
				.map(n -> new Victory.NationFacts(n.id(), provinces.get(n.id())[0], population.get(n.id()),
						economy.treasury(n.id()).treasuryMg(), n.eliminated()))
				.toList();
		}

		/** @param winner forced winner (domination), or null to take the score ranking's */
		private Outcome outcome(UUID gameId, DiplomacyState.Picture picture, int round, String reason, UUID winner) {
			List<Victory.NationFacts> facts = facts(gameId, world.snapshot(gameId));
			Victory.Outcome ranked = Victory.rank(facts, picture.rules().victory());
			List<Standing> standings = ranked.standings()
				.stream()
				.map(r -> new Standing(r.nationId(), r.rank(), r.score(), r.ownedProvinces(), r.ownedPopulation(),
						economy.treasury(r.nationId()).treasuryMg()))
				.toList();
			Map<String, Long> statistics = new LinkedHashMap<>();
			statistics.put("roundsPlayed", (long) round);
			statistics.put("warsDeclared", (long) picture.wars().size());
			statistics.put("peaceTreaties", (long) treaties.treaties(gameId).size());
			statistics.put("battlesInWars", relationsBattles(picture));
			statistics.put("provincesCeded", (long) events.count(gameId, "PROVINCE_CEDED"));
			statistics.put("tributePaidMg",
					treaties.payments(gameId).stream().mapToLong(TreatyRepository.PaymentRow::paidMg).sum());
			statistics.put("nationsEliminated", facts.stream().filter(Victory.NationFacts::eliminated).count());
			return new Outcome(reason, winner != null ? winner : ranked.winnerNationId(), standings, statistics);
		}

		private long relationsBattles(DiplomacyState.Picture picture) {
			return state.scoreEvents(picture.gameId()).stream().filter(e -> "BATTLE".equals(e.kind())).count();
		}

	}

}
