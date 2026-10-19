package com.reach.strategy.diplomacy.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.diplomacy.domain.DiplomacyEventLog;
import com.reach.strategy.diplomacy.domain.RelationsRepository;
import com.reach.strategy.diplomacy.domain.TreatyRepository;
import com.reach.strategy.diplomacy.rules.Diplomacy;
import com.reach.strategy.diplomacy.rules.DiplomacyRules;
import com.reach.strategy.diplomacy.rules.PeaceTerms;
import com.reach.strategy.diplomacy.rules.WarScore;
import com.reach.strategy.economy.api.EconomyApi;
import com.reach.strategy.game.api.GameAccess;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.Hostility;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Diplomacy read models and the view building shared with the command services. Queries run as one
 * REPEATABLE READ snapshot, so war score, ownership and treaties never mix two states.
 */
@Service
public class DiplomacyQueries {

	static final int EVENT_LIMIT = 40;

	private final GameAccess gameAccess;
	private final DiplomacyState state;
	private final RelationsRepository relations;
	private final TreatyRepository treaties;
	private final DiplomacyEventLog events;
	private final WorldApi world;
	private final EconomyApi economy;
	private final JsonMapper jsonMapper;

	DiplomacyQueries(GameAccess gameAccess, DiplomacyState state, RelationsRepository relations,
			TreatyRepository treaties, DiplomacyEventLog events, WorldApi world, EconomyApi economy,
			JsonMapper jsonMapper) {
		this.gameAccess = gameAccess;
		this.state = state;
		this.relations = relations;
		this.treaties = treaties;
		this.events = events;
		this.world = world;
		this.economy = economy;
		this.jsonMapper = jsonMapper;
	}

	// --- queries --------------------------------------------------------------------------------

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public DiplomacyViews.Overview overview(CurrentPlayer me, UUID gameId) {
		GameAccess.Membership membership = startedGame(me, gameId);
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		UUID nationId = nationOf(snapshot, membership.gamePlayerId());
		Optional<DiplomacyState.Picture> loaded = state.load(gameId);
		if (loaded.isEmpty()) {
			return new DiplomacyViews.Overview(gameId, false, membership.rulesetVersion(), membership.round(),
					membership.yourTurn(), nationId, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
					List.of());
		}
		Ctx ctx = context(gameId, loaded.get(), snapshot, nationId, membership.round());
		List<DiplomacyViews.NationRelation> nations = snapshot.nations()
			.stream()
			.sorted(Comparator.comparing(WorldApi.NationState::name))
			.map(n -> relation(ctx, n))
			.toList();
		List<DiplomacyViews.WarView> wars = ctx.picture()
			.wars()
			.stream()
			.sorted(Comparator.comparing((RelationsRepository.WarRow w) -> !w.active())
				.thenComparing(RelationsRepository.WarRow::declaredTurn, Comparator.reverseOrder()))
			.map(w -> warView(ctx, w))
			.toList();
		List<DiplomacyViews.AllianceView> alliances = ctx.picture()
			.alliances()
			.stream()
			// Proposals are private: only their two nations see a pending or unanswered one.
			.filter(a -> "ACTIVE".equals(a.status()) || "ENDED".equals(a.status()) || a.involves(nationId))
			.map(a -> allianceView(ctx, a))
			.toList();
		List<DiplomacyViews.ProposalView> proposals = treaties.proposalsOf(gameId, nationId)
			.stream()
			.map(p -> proposalView(ctx, p))
			.toList();
		return new DiplomacyViews.Overview(gameId, true, membership.rulesetVersion(), membership.round(),
				membership.yourTurn(), nationId, rulesView(ctx.rules()), nations, wars, alliances, proposals,
				treatyViews(ctx), tributeViews(gameId), eventViews(events.recent(gameId, EVENT_LIMIT)));
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public DiplomacyViews.WarDetail war(CurrentPlayer me, UUID gameId, UUID warId) {
		GameAccess.Membership membership = startedGame(me, gameId);
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		Ctx ctx = context(gameId, picture, snapshot, nationOf(snapshot, membership.gamePlayerId()), membership.round());
		RelationsRepository.WarRow war = picture.war(warId).orElseThrow(DiplomacyErrors::warNotFound);
		List<DiplomacyViews.ScoreEvent> scoreEvents = ctx.scoreEvents()
			.stream()
			.filter(e -> e.warId().equals(warId))
			.map(e -> new DiplomacyViews.ScoreEvent(e.kind(), e.side(), e.sourceId(), e.round(), e.enemyLosses(),
					e.points()))
			.toList();
		List<DiplomacyViews.EventView> history = eventViews(events.recent(gameId, 500)
			.stream()
			.filter(e -> warId.equals(e.warId()))
			.toList());
		return new DiplomacyViews.WarDetail(warView(ctx, war), scoreEvents, history);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<DiplomacyViews.ProposalView> proposals(CurrentPlayer me, UUID gameId) {
		GameAccess.Membership membership = startedGame(me, gameId);
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		UUID nationId = nationOf(snapshot, membership.gamePlayerId());
		Ctx ctx = context(gameId, picture, snapshot, nationId, membership.round());
		return treaties.proposalsOf(gameId, nationId).stream().map(p -> proposalView(ctx, p)).toList();
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public DiplomacyViews.ProposalView proposal(CurrentPlayer me, UUID gameId, UUID proposalId) {
		GameAccess.Membership membership = startedGame(me, gameId);
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		UUID nationId = nationOf(snapshot, membership.gamePlayerId());
		// A third nation gets the same 404 as for a proposal that does not exist.
		TreatyRepository.ProposalRow row = treaties.proposal(gameId, proposalId)
			.filter(p -> p.involves(nationId))
			.orElseThrow(DiplomacyErrors::proposalNotFound);
		return proposalView(context(gameId, picture, snapshot, nationId, membership.round()), row);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<DiplomacyViews.TreatyView> treaties(CurrentPlayer me, UUID gameId) {
		GameAccess.Membership membership = startedGame(me, gameId);
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		return treatyViews(context(gameId, picture, snapshot, nationOf(snapshot, membership.gamePlayerId()),
				membership.round()));
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<DiplomacyViews.TributeView> tributes(CurrentPlayer me, UUID gameId) {
		startedGame(me, gameId);
		state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		return tributeViews(gameId);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<DiplomacyViews.EventView> events(CurrentPlayer me, UUID gameId) {
		startedGame(me, gameId);
		state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		return eventViews(events.recent(gameId, 200));
	}

	/** Validation only: would these terms be accepted for this war right now? Changes nothing. */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public DiplomacyViews.TermsCheck check(CurrentPlayer me, UUID gameId, UUID warId, List<DiplomacyViews.Term> terms) {
		GameAccess.Membership membership = startedGame(me, gameId);
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		UUID nationId = nationOf(snapshot, membership.gamePlayerId());
		RelationsRepository.WarRow war = picture.war(warId).orElseThrow(DiplomacyErrors::warNotFound);
		UUID other = otherLeader(war, nationId);
		List<PeaceTerms.Violation> violations = PeaceTerms.validate(toRules(terms), facts(gameId, snapshot, nationId, other),
				picture.rules());
		return new DiplomacyViews.TermsCheck(violations.isEmpty(),
				violations.stream().map(v -> new DiplomacyViews.Violation(v.term(), v.code().name())).toList());
	}

	// --- shared with the command services --------------------------------------------------------

	record Ctx(UUID gameId, DiplomacyState.Picture picture, WorldApi.WorldSnapshot snapshot, UUID viewer, int round,
			List<RelationsRepository.ScoreEventRow> scoreEvents, Diplomacy.Standing standing,
			Hostility.Relations hostility) {

		DiplomacyRules rules() {
			return picture.rules();
		}

		String nameOf(UUID nationId) {
			return snapshot.nations()
				.stream()
				.filter(n -> n.id().equals(nationId))
				.map(WorldApi.NationState::name)
				.findFirst()
				.orElse("Unknown nation");
		}

	}

	Ctx context(UUID gameId, DiplomacyState.Picture picture, WorldApi.WorldSnapshot snapshot, UUID viewer, int round) {
		return new Ctx(gameId, picture, snapshot, viewer, round, relations.scoreEvents(gameId), picture.standing(),
				picture.hostility());
	}

	GameAccess.Membership startedGame(CurrentPlayer me, UUID gameId) {
		GameAccess.Membership membership = gameAccess.membership(me, gameId);
		if (!membership.started()) {
			throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_IN_PROGRESS", "The game has not started.");
		}
		return membership;
	}

	static UUID nationOf(WorldApi.WorldSnapshot snapshot, UUID gamePlayerId) {
		return snapshot.nations()
			.stream()
			.filter(n -> gamePlayerId.equals(n.gamePlayerId()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Player " + gamePlayerId + " has no nation"))
			.id();
	}

	static UUID otherLeader(RelationsRepository.WarRow war, UUID nationId) {
		if (war.attackerLeader().equals(nationId)) {
			return war.defenderLeader();
		}
		if (war.defenderLeader().equals(nationId)) {
			return war.attackerLeader();
		}
		throw DiplomacyErrors.forbidden("NOT_WAR_LEADER", "Only the two leaders of a war negotiate its peace.");
	}

	/** The validation's view of the world: owners, cities, populations, capitals and both treasuries. */
	PeaceTerms.Facts facts(UUID gameId, WorldApi.WorldSnapshot snapshot, UUID partyA, UUID partyB) {
		Set<String> capitals = world.mapSummary(snapshot.mapId())
			.nations()
			.stream()
			.map(WorldApi.NationOption::capitalProvinceKey)
			.collect(Collectors.toSet());
		List<PeaceTerms.ProvinceFact> provinces = snapshot.provinces()
			.stream()
			.map(p -> new PeaceTerms.ProvinceFact(p.provinceKey(), p.ownerNationId(), p.cityId(), p.population(),
					capitals.contains(p.provinceKey())))
			.toList();
		return new PeaceTerms.Facts(partyA, partyB, provinces,
				Map.of(partyA, economy.treasury(partyA).treasuryMg(), partyB, economy.treasury(partyB).treasuryMg()));
	}

	DiplomacyViews.WarView warView(Ctx ctx, RelationsRepository.WarRow war) {
		List<RelationsRepository.ParticipantRow> participants = ctx.picture().participantsOf(war.id());
		WarScores.Detailed detailed = WarScores.of(war, participants, ctx.scoreEvents(), ctx.snapshot(), ctx.picture());
		WarScore.Score s = detailed.score();
		DiplomacyViews.Score score = new DiplomacyViews.Score(side(s.attacker(), detailed.attacker()),
				side(s.defender(), detailed.defender()), s.net(), ctx.rules().warScore().maxScore());
		int last = war.active() ? ctx.round() : war.endedRound();
		String yourSide = ctx.picture().sideOf(war.id(), ctx.viewer()).orElse(null);
		boolean youLead = war.attackerLeader().equals(ctx.viewer()) || war.defenderLeader().equals(ctx.viewer());
		return new DiplomacyViews.WarView(war.id(), war.status(), war.attackerLeader(), war.defenderLeader(),
				participants.stream()
					.map(p -> new DiplomacyViews.Participant(p.nationId(), ctx.nameOf(p.nationId()), p.side(),
							p.joinReason(), p.joinedRound()))
					.toList(),
				war.declaredRound(), war.endedRound(), war.endReason(), last - war.declaredRound() + 1, score, yourSide,
				youLead);
	}

	private static DiplomacyViews.SideScore side(WarScore.SideScore score, WarScores.Facts facts) {
		return new DiplomacyViews.SideScore(score.battles(), score.sieges(), score.occupation(), score.total(),
				facts.battlesWon(), facts.siegesWon(), facts.facts().occupiedEnemyPopulation(),
				facts.facts().enemyPopulation());
	}

	DiplomacyViews.AllianceView allianceView(Ctx ctx, RelationsRepository.AllianceRow a) {
		boolean member = a.involves(ctx.viewer());
		boolean proposed = "PROPOSED".equals(a.status());
		boolean active = "ACTIVE".equals(a.status());
		List<String> endBlockers = active ? Diplomacy
			.terminationBlockers(true, ctx.picture().fightingTogether(a.nationA(), a.nationB()))
			.stream()
			.map(Enum::name)
			.toList() : List.of();
		boolean canAccept = member && proposed && !a.proposer().equals(ctx.viewer());
		boolean canEnd = member && ((proposed && a.proposer().equals(ctx.viewer())) || (active && endBlockers.isEmpty()));
		return new DiplomacyViews.AllianceView(a.id(), a.kind(), a.nationA(), a.nationB(), a.proposer(), a.status(),
				a.proposedRound(), a.acceptedRound(), a.endedRound(), a.endReason(),
				a.proposedRound() + ctx.rules().proposalExpiryRounds(),
				ctx.rules().militaryAccess().grantedBy(Diplomacy.AllianceKind.valueOf(a.kind())), canAccept, canEnd,
				endBlockers);
	}

	DiplomacyViews.ProposalView proposalView(Ctx ctx, TreatyRepository.ProposalRow p) {
		List<DiplomacyViews.Term> terms = withTributeRules(readTerms(p.termsJson()), ctx.rules());
		List<String> violations = List.of();
		if (p.pending()) {
			violations = PeaceTerms
				.validate(toRules(terms), facts(ctx.gameId(), ctx.snapshot(), p.proposer(), p.recipient()), ctx.rules())
				.stream()
				.map(v -> (v.term() < 0 ? "proposal" : "term " + (v.term() + 1)) + ": " + v.code().name())
				.toList();
		}
		return new DiplomacyViews.ProposalView(p.id(), p.warId(), p.proposer(), p.recipient(), p.status(), terms,
				p.createdRound(), p.createdTurn(), p.createdStateVersion(), p.respondedRound(), p.counterOfId(),
				p.createdRound() + ctx.rules().proposalExpiryRounds(), p.recipient().equals(ctx.viewer()), violations,
				p.createdAt());
	}

	List<DiplomacyViews.TreatyView> treatyViews(Ctx ctx) {
		return treaties.treaties(ctx.gameId())
			.stream()
			.map(t -> new DiplomacyViews.TreatyView(t.id(), t.warId(), t.proposalId(), t.signedRound(), t.signedTurn(),
					readTerms(t.termsJson()), t.truceUntilRound()))
			.toList();
	}

	List<DiplomacyViews.TributeView> tributeViews(UUID gameId) {
		Map<UUID, List<TreatyRepository.PaymentRow>> payments = treaties.payments(gameId)
			.stream()
			.collect(Collectors.groupingBy(TreatyRepository.PaymentRow::tributeId));
		return treaties.tributes(gameId)
			.stream()
			.map(t -> new DiplomacyViews.TributeView(t.id(), t.treatyId(), t.payer(), t.receiver(), t.rateBps(),
					t.roundsTotal(), t.roundsCharged(), t.firstRound(), t.totalPaidMg(), t.status(), t.endReason(),
					t.endedRound(),
					payments.getOrDefault(t.id(), List.of())
						.stream()
						.map(p -> new DiplomacyViews.Payment(p.round(), p.grossIncomeMg(), p.dueMg(), p.paidMg()))
						.toList()))
			.toList();
	}

	List<DiplomacyViews.EventView> eventViews(List<DiplomacyEventLog.Event> rows) {
		return rows.stream()
			.map(e -> new DiplomacyViews.EventView(e.id(), e.round(), e.kind(), e.actorNationId(), e.targetNationId(),
					e.warId(), jsonMapper.readValue(e.detailsJson(), new TypeReference<Map<String, Object>>() {
					}), e.createdAt()))
			.toList();
	}

	private DiplomacyViews.NationRelation relation(Ctx ctx, WorldApi.NationState n) {
		UUID me = ctx.viewer();
		if (n.id().equals(me)) {
			return new DiplomacyViews.NationRelation(n.id(), n.name(), n.color(), true, "SELF", null, null, null, null,
					true, true, List.of(), List.of());
		}
		Diplomacy.Standing standing = ctx.standing();
		Diplomacy.AllianceKind kind = standing.alliance(me, n.id());
		UUID allianceId = ctx.picture()
			.alliances()
			.stream()
			.filter(a -> ("ACTIVE".equals(a.status()) || "PROPOSED".equals(a.status())) && a.involves(me)
					&& a.involves(n.id()))
			.map(RelationsRepository.AllianceRow::id)
			.findFirst()
			.orElse(null);
		UUID warId = ctx.picture().activeWarBetween(me, n.id()).map(RelationsRepository.WarRow::id).orElse(null);
		String status = warId != null ? "WAR" : kind != null ? "ALLIED" : "PEACE";
		Integer truce = standing.truceUntilRound().get(Diplomacy.Pair.of(me, n.id()));
		Integer truceUntil = truce != null && truce >= ctx.round() ? truce : null;
		List<UUID> nations = ctx.snapshot().nations().stream().map(WorldApi.NationState::id).toList();
		List<String> warBlockers = Diplomacy.warBlockers(me, n.id(), nations, standing, ctx.round())
			.stream()
			.map(Enum::name)
			.toList();
		List<String> allianceBlockers = Diplomacy.allianceBlockers(me, n.id(), nations, standing, true)
			.stream()
			.map(Enum::name)
			.toList();
		return new DiplomacyViews.NationRelation(n.id(), n.name(), n.color(), false, status,
				kind == null ? null : kind.name(), allianceId, warId, truceUntil, ctx.hostility().mayEnter(me, n.id(), n.id()),
				ctx.hostility().mayEnter(n.id(), me, me), warBlockers, allianceBlockers);
	}

	static DiplomacyViews.Rules rulesView(DiplomacyRules r) {
		return new DiplomacyViews.Rules(r.version(), r.truceRounds(), r.proposalExpiryRounds(), r.maxPeaceTerms(),
				r.tribute().rateBps(), r.tribute().rounds(), r.populationTransfer().minPeople(),
				r.populationTransfer().maxPeople(), r.populationTransfer().maxShareBps(),
				r.populationTransfer().minResidualPopulation(), r.reparations().maxMg(), r.warScore().maxScore(),
				r.victory().maxRounds(), r.victory().provincePoints(), r.victory().pointsPerThousandPopulation(),
				r.militaryAccess().normalAlliance(), r.militaryAccess().defensiveAlliance(),
				r.militaryAccess().coBelligerents());
	}

	// --- terms <-> JSON -------------------------------------------------------------------------

	List<DiplomacyViews.Term> readTerms(String json) {
		return new ArrayList<>(jsonMapper.readValue(json, new TypeReference<List<DiplomacyViews.Term>>() {
		}));
	}

	String writeTerms(List<DiplomacyViews.Term> terms) {
		return jsonMapper.writeValueAsString(terms);
	}

	/** Stored terms carry no tribute numbers; views show the ruleset's rate and duration. */
	static List<DiplomacyViews.Term> withTributeRules(List<DiplomacyViews.Term> terms, DiplomacyRules rules) {
		return terms.stream()
			.map(t -> "TRIBUTE".equals(t.type()) ? new DiplomacyViews.Term(t.type(), t.fromNationId(), t.toNationId(),
					null, null, null, null, null, rules.tribute().rateBps(), rules.tribute().rounds()) : t)
			.toList();
	}

	/**
	 * Client terms → rule terms. Only the fields of each type are used; anything a client might add
	 * (amounts for a tribute, a province key for gold) is ignored.
	 */
	static List<PeaceTerms.Term> toRules(List<DiplomacyViews.Term> terms) {
		List<PeaceTerms.Term> out = new ArrayList<>();
		for (DiplomacyViews.Term t : terms) {
			if (t == null || t.type() == null || t.fromNationId() == null || t.toNationId() == null) {
				throw DiplomacyErrors.invalidTerm("Every term needs a type, fromNationId and toNationId.");
			}
			out.add(switch (t.type()) {
				case "PROVINCE" -> {
					if (t.provinceKey() == null || t.provinceKey().isBlank()) {
						throw DiplomacyErrors.invalidTerm("A PROVINCE term needs a provinceKey.");
					}
					yield new PeaceTerms.ProvinceCession(t.fromNationId(), t.toNationId(), t.provinceKey());
				}
				case "GOLD" -> {
					if (t.amountMg() == null) {
						throw DiplomacyErrors.invalidTerm("A GOLD term needs amountMg (integer milligold).");
					}
					yield new PeaceTerms.Reparations(t.fromNationId(), t.toNationId(), t.amountMg());
				}
				case "TRIBUTE" -> new PeaceTerms.Tribute(t.fromNationId(), t.toNationId());
				case "POPULATION" -> {
					if (t.sourceCityId() == null || t.destinationCityId() == null || t.people() == null) {
						throw DiplomacyErrors
							.invalidTerm("A POPULATION term needs sourceCityId, destinationCityId and people.");
					}
					yield new PeaceTerms.PopulationTransfer(t.fromNationId(), t.toNationId(), t.sourceCityId(),
							t.destinationCityId(), t.people());
				}
				default -> throw DiplomacyErrors.invalidTerm("Unknown term type " + t.type() + ".");
			});
		}
		return out;
	}

	/** The canonical stored form of validated terms (only the fields each type uses). */
	static List<DiplomacyViews.Term> canonical(List<PeaceTerms.Term> terms) {
		return terms.stream().map(t -> switch (t) {
			case PeaceTerms.ProvinceCession c -> new DiplomacyViews.Term("PROVINCE", c.from(), c.to(), c.provinceKey(),
					null, null, null, null, null, null);
			case PeaceTerms.Reparations r -> new DiplomacyViews.Term("GOLD", r.from(), r.to(), null, r.amountMg(), null,
					null, null, null, null);
			case PeaceTerms.Tribute tr -> new DiplomacyViews.Term("TRIBUTE", tr.from(), tr.to(), null, null, null, null,
					null, null, null);
			case PeaceTerms.PopulationTransfer p -> new DiplomacyViews.Term("POPULATION", p.from(), p.to(), null, null,
					p.sourceCityId(), p.destinationCityId(), p.people(), null, null);
		}).toList();
	}

}
