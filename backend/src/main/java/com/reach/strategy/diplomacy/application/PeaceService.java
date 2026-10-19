package com.reach.strategy.diplomacy.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.diplomacy.domain.RelationsRepository;
import com.reach.strategy.diplomacy.domain.TreatyRepository;
import com.reach.strategy.diplomacy.rules.PeaceTerms;
import com.reach.strategy.economy.api.EconomyApi;
import com.reach.strategy.game.api.GameAccess;
import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.Hostility;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.stereotype.Service;

/**
 * Peace negotiation and settlement (docs/DIPLOMACY.md §5–6). Only the two leaders of a war
 * negotiate; each answers on its own turn. Acceptance re-validates every term against the state at
 * that moment and applies all of them, ends the war, starts the truce, returns occupied provinces
 * to their owners and withdraws battalions — in the command's single transaction, so either the
 * whole treaty takes effect or nothing does. There is no surrender: a war ends only by agreement.
 */
@Service
public class PeaceService {

	private final GameCommands gameCommands;
	private final GameAccess gameAccess;
	private final DiplomacyState state;
	private final RelationsRepository relations;
	private final TreatyRepository treaties;
	private final DiplomacyQueries queries;
	private final DiplomacyService diplomacy;
	private final WorldApi world;
	private final EconomyApi economy;

	PeaceService(GameCommands gameCommands, GameAccess gameAccess, DiplomacyState state, RelationsRepository relations,
			TreatyRepository treaties, DiplomacyQueries queries, DiplomacyService diplomacy, WorldApi world,
			EconomyApi economy) {
		this.gameCommands = gameCommands;
		this.gameAccess = gameAccess;
		this.state = state;
		this.relations = relations;
		this.treaties = treaties;
		this.queries = queries;
		this.diplomacy = diplomacy;
		this.world = world;
		this.economy = economy;
	}

	// --- proposals ------------------------------------------------------------------------------

	/**
	 * A war leader proposes terms to the other leader. A newer proposal from the same leader
	 * supersedes its pending one. Terms are validated now and again at acceptance.
	 */
	public DiplomacyViews.ProposalResult propose(CurrentPlayer me, UUID gameId, UUID warId,
			List<DiplomacyViews.Term> terms, UUID idempotencyKey) {
		List<PeaceTerms.Term> parsed = DiplomacyQueries.toRules(terms);
		return gameCommands.execute(me, gameId, idempotencyKey, "PROPOSE_PEACE", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.ProposalResult> execute(GameCommands.CommandContext context) {
				UUID id = create(me, gameId, context, warId, parsed, null);
				return new GameCommands.Outcome<>(proposalResult(gameId, context, id), id);
			}

			@Override
			public DiplomacyViews.ProposalResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return proposalResult(gameId, context, resultRef);
			}

		});
	}

	/** The recipient answers a proposal with its own terms; the original is superseded. */
	public DiplomacyViews.ProposalResult counter(CurrentPlayer me, UUID gameId, UUID proposalId,
			List<DiplomacyViews.Term> terms, UUID idempotencyKey) {
		List<PeaceTerms.Term> parsed = DiplomacyQueries.toRules(terms);
		return gameCommands.execute(me, gameId, idempotencyKey, "COUNTER_PEACE", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.ProposalResult> execute(GameCommands.CommandContext context) {
				UUID nation = nationOf(gameId, context);
				TreatyRepository.ProposalRow original = incoming(gameId, proposalId, nation);
				if (!treaties.closeProposal(gameId, original.id(), "SUPERSEDED", context.round(), context.now())) {
					throw notPending();
				}
				UUID id = create(me, gameId, context, original.warId(), parsed, original.id());
				return new GameCommands.Outcome<>(proposalResult(gameId, context, id), id);
			}

			@Override
			public DiplomacyViews.ProposalResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return proposalResult(gameId, context, resultRef);
			}

		});
	}

	public DiplomacyViews.ProposalResult reject(CurrentPlayer me, UUID gameId, UUID proposalId, UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "REJECT_PEACE", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.ProposalResult> execute(GameCommands.CommandContext context) {
				TreatyRepository.ProposalRow p = incoming(gameId, proposalId, nationOf(gameId, context));
				if (!treaties.closeProposal(gameId, p.id(), "REJECTED", context.round(), context.now())) {
					throw notPending();
				}
				return new GameCommands.Outcome<>(proposalResult(gameId, context, p.id()), p.id());
			}

			@Override
			public DiplomacyViews.ProposalResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return proposalResult(gameId, context, resultRef);
			}

		});
	}

	/** The proposer withdraws its own pending proposal. */
	public DiplomacyViews.ProposalResult cancel(CurrentPlayer me, UUID gameId, UUID proposalId, UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "CANCEL_PEACE", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.ProposalResult> execute(GameCommands.CommandContext context) {
				UUID nation = nationOf(gameId, context);
				TreatyRepository.ProposalRow p = visible(gameId, proposalId, nation);
				if (!p.proposer().equals(nation)) {
					throw DiplomacyErrors.forbidden("NOT_PROPOSAL_PROPOSER",
							"Only the proposing nation can cancel this proposal; reject it instead.");
				}
				if (!treaties.closeProposal(gameId, p.id(), "CANCELLED", context.round(), context.now())) {
					throw notPending();
				}
				return new GameCommands.Outcome<>(proposalResult(gameId, context, p.id()), p.id());
			}

			@Override
			public DiplomacyViews.ProposalResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return proposalResult(gameId, context, resultRef);
			}

		});
	}

	private UUID create(CurrentPlayer me, UUID gameId, GameCommands.CommandContext context, UUID warId,
			List<PeaceTerms.Term> terms, UUID counterOf) {
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		UUID proposer = DiplomacyQueries.nationOf(snapshot, context.gamePlayerId());
		RelationsRepository.WarRow war = activeWar(picture, warId);
		UUID recipient = DiplomacyQueries.otherLeader(war, proposer);
		List<PeaceTerms.Violation> violations = PeaceTerms.validate(terms,
				queries.facts(gameId, snapshot, proposer, recipient), picture.rules());
		if (!violations.isEmpty()) {
			throw DiplomacyErrors.termsInvalid(violations, false);
		}
		for (TreatyRepository.ProposalRow previous : treaties.pendingProposals(gameId)) {
			if (previous.warId().equals(warId) && previous.proposer().equals(proposer)) {
				treaties.closeProposal(gameId, previous.id(), "SUPERSEDED", context.round(), context.now());
			}
		}
		UUID id = UUID.randomUUID();
		long stateVersion = gameAccess.membership(me, gameId).stateVersion();
		treaties.insertProposal(gameId, new TreatyRepository.ProposalRow(id, warId, proposer, recipient, "PENDING",
				queries.writeTerms(DiplomacyQueries.canonical(terms)), context.round(), context.turnNumber(), stateVersion,
				null, counterOf, context.now()), context.now());
		return id;
	}

	// --- settlement -----------------------------------------------------------------------------

	/**
	 * The recipient accepts: everything below happens in this one transaction, in this order, or not
	 * at all — re-validation; reparations; cessions; population transfers; tribute; the war ends with
	 * all its fronts; the treaty and the truce are stored; provinces held by a former enemy return to
	 * their legal owner; battalions without access withdraw and sieges are re-judged (lifted).
	 */
	public DiplomacyViews.PeaceResult accept(CurrentPlayer me, UUID gameId, UUID proposalId, UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "ACCEPT_PEACE", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<DiplomacyViews.PeaceResult> execute(GameCommands.CommandContext context) {
				return settle(gameId, context, proposalId);
			}

			@Override
			public DiplomacyViews.PeaceResult replay(GameCommands.CommandContext context, UUID resultRef) {
				DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
				UUID nation = nationOf(gameId, context);
				TreatyRepository.TreatyRow treaty = treaties.treaties(gameId)
					.stream()
					.filter(t -> t.id().equals(resultRef))
					.findFirst()
					.orElseThrow();
				DiplomacyQueries.Ctx ctx = queries.context(gameId, picture, world.snapshot(gameId), nation, context.round());
				return new DiplomacyViews.PeaceResult(treatyView(treaty), queries.warView(ctx,
						picture.war(treaty.warId()).orElseThrow()), List.of(), List.of());
			}

		});
	}

	private GameCommands.Outcome<DiplomacyViews.PeaceResult> settle(UUID gameId, GameCommands.CommandContext context,
			UUID proposalId) {
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		UUID nation = DiplomacyQueries.nationOf(snapshot, context.gamePlayerId());
		TreatyRepository.ProposalRow proposal = incoming(gameId, proposalId, nation);
		RelationsRepository.WarRow war = activeWar(picture, proposal.warId());
		List<DiplomacyViews.Term> stored = queries.readTerms(proposal.termsJson());
		List<PeaceTerms.Term> terms = DiplomacyQueries.toRules(stored);
		List<PeaceTerms.Violation> violations = PeaceTerms.validate(terms,
				queries.facts(gameId, snapshot, proposal.proposer(), proposal.recipient()), picture.rules());
		if (!violations.isEmpty()) {
			throw DiplomacyErrors.termsInvalid(violations, true);
		}
		if (!treaties.closeProposal(gameId, proposal.id(), "ACCEPTED", context.round(), context.now())) {
			throw notPending();
		}
		for (TreatyRepository.ProposalRow other : treaties.pendingProposals(gameId)) {
			if (other.warId().equals(war.id())) {
				treaties.closeProposal(gameId, other.id(), "SUPERSEDED", context.round(), context.now());
			}
		}

		UUID treatyId = UUID.randomUUID();
		int truceUntil = context.round() + picture.rules().truceRounds();
		treaties.insertTreaty(gameId, new TreatyRepository.TreatyRow(treatyId, war.id(), proposal.id(), context.round(),
				context.turnNumber(), proposal.termsJson(), truceUntil, context.now()));
		for (PeaceTerms.Term term : PeaceTerms.settlementOrder(terms)) {
			apply(gameId, context, war, treatyId, term, snapshot, picture);
		}
		if (!relations.endWar(gameId, war.id(), context.round(), context.turnNumber(), "PEACE_TREATY")) {
			throw DiplomacyErrors.conflict("WAR_NOT_ACTIVE", "This war has already ended.");
		}
		List<RelationsRepository.ParticipantRow> participants = picture.participantsOf(war.id());
		for (RelationsRepository.ParticipantRow x : participants) {
			for (RelationsRepository.ParticipantRow y : participants) {
				if ("ATTACKER".equals(x.side()) && "DEFENDER".equals(y.side())) {
					relations.insertTruce(gameId,
							new RelationsRepository.TruceRow(treatyId, x.nationId(), y.nationId(), truceUntil));
				}
			}
		}
		diplomacy.event(gameId, context, "PEACE_SIGNED", proposal.proposer(), proposal.recipient(), war.id(),
				Map.of("treatyId", treatyId, "terms", stored.size(), "truceUntilRound", truceUntil));

		List<String> returned = returnControl(gameId, context, war.id());
		List<DiplomacyViews.Withdrawal> withdrawals = diplomacy.withdraw(gameId, context, nation, war.id());

		DiplomacyState.Picture after = state.load(gameId).orElseThrow();
		DiplomacyQueries.Ctx ctx = queries.context(gameId, after, world.snapshot(gameId), nation, context.round());
		TreatyRepository.TreatyRow treaty = treaties.treaties(gameId)
			.stream()
			.filter(t -> t.id().equals(treatyId))
			.findFirst()
			.orElseThrow();
		return new GameCommands.Outcome<>(new DiplomacyViews.PeaceResult(treatyView(treaty),
				queries.warView(ctx, after.war(war.id()).orElseThrow()), withdrawals, returned), treatyId);
	}

	private void apply(UUID gameId, GameCommands.CommandContext context, RelationsRepository.WarRow war, UUID treatyId,
			PeaceTerms.Term term, WorldApi.WorldSnapshot before, DiplomacyState.Picture picture) {
		switch (term) {
			case PeaceTerms.Reparations r -> {
				economy.transfer(r.from(), r.to(), r.amountMg());
				diplomacy.event(gameId, context, "REPARATIONS_PAID", r.from(), r.to(), war.id(),
						Map.of("amountMg", r.amountMg(), "treatyId", treatyId));
			}
			case PeaceTerms.ProvinceCession c -> {
				world.transferOwnership(gameId, c.provinceKey(), c.from(), c.to());
				diplomacy.event(gameId, context, "PROVINCE_CEDED", c.from(), c.to(), war.id(),
						Map.of("provinceKey", c.provinceKey(), "treatyId", treatyId));
			}
			case PeaceTerms.PopulationTransfer p -> {
				int source = world.changePopulation(gameId, p.sourceCityId(), -p.people());
				int destination = world.changePopulation(gameId, p.destinationCityId(), p.people());
				String sourceKey = before.provinceOfCity(p.sourceCityId()).orElseThrow().provinceKey();
				String destinationKey = before.provinceOfCity(p.destinationCityId()).orElseThrow().provinceKey();
				diplomacy.event(gameId, context, "POPULATION_TRANSFERRED", p.from(), p.to(), war.id(),
						Map.of("people", p.people(), "fromProvinceKey", sourceKey, "toProvinceKey", destinationKey,
								"sourcePopulationAfter", source, "destinationPopulationAfter", destination,
								"treatyId", treatyId));
			}
			case PeaceTerms.Tribute t -> treaties.insertTribute(gameId,
					new TreatyRepository.TributeRow(UUID.randomUUID(), treatyId, t.from(), t.to(),
							picture.rules().tribute().rateBps(), picture.rules().tribute().rounds(), 0, context.round(), 0,
							"ACTIVE", null, null),
					context.now());
		}
	}

	/**
	 * Provinces controlled by a nation that is no longer at war with their legal owner return to the
	 * owner's control (occupation ends with the war; only ceded provinces change hands).
	 */
	private List<String> returnControl(UUID gameId, GameCommands.CommandContext context, UUID warId) {
		Hostility.Relations now = state.load(gameId).orElseThrow().hostility();
		List<String> returned = new ArrayList<>();
		world.snapshot(gameId)
			.provinces()
			.stream()
			.filter(p -> !p.ownerNationId().equals(p.controllerNationId()))
			.filter(p -> !now.atWar(p.ownerNationId(), p.controllerNationId()))
			.sorted(Comparator.comparing(WorldApi.ProvinceState::provinceKey))
			.forEach(p -> {
				world.setController(gameId, p.provinceKey(), p.ownerNationId());
				returned.add(p.provinceKey());
				diplomacy.event(gameId, context, "CONTROL_RETURNED", p.ownerNationId(), p.controllerNationId(), warId,
						Map.of("provinceKey", p.provinceKey()));
			});
		return returned;
	}

	// --- helpers --------------------------------------------------------------------------------

	private UUID nationOf(UUID gameId, GameCommands.CommandContext context) {
		return DiplomacyQueries.nationOf(world.snapshot(gameId), context.gamePlayerId());
	}

	private static RelationsRepository.WarRow activeWar(DiplomacyState.Picture picture, UUID warId) {
		RelationsRepository.WarRow war = picture.war(warId).orElseThrow(DiplomacyErrors::warNotFound);
		if (!war.active()) {
			throw DiplomacyErrors.conflict("WAR_NOT_ACTIVE", "This war has already ended.");
		}
		return war;
	}

	/** A proposal visible to this nation (proposer or recipient); others get a 404. */
	private TreatyRepository.ProposalRow visible(UUID gameId, UUID proposalId, UUID nation) {
		state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		return treaties.proposal(gameId, proposalId)
			.filter(p -> p.involves(nation))
			.orElseThrow(DiplomacyErrors::proposalNotFound);
	}

	/** A pending proposal addressed to this nation. */
	private TreatyRepository.ProposalRow incoming(UUID gameId, UUID proposalId, UUID nation) {
		TreatyRepository.ProposalRow p = visible(gameId, proposalId, nation);
		if (!p.recipient().equals(nation)) {
			throw DiplomacyErrors.forbidden("NOT_PROPOSAL_RECIPIENT", "Only the receiving nation can answer this proposal.");
		}
		if (!p.pending()) {
			throw notPending();
		}
		return p;
	}

	private static ApiException notPending() {
		return DiplomacyErrors.conflict("PROPOSAL_NOT_PENDING", "This peace proposal is no longer pending.");
	}

	private DiplomacyViews.ProposalResult proposalResult(UUID gameId, GameCommands.CommandContext context, UUID id) {
		DiplomacyState.Picture picture = state.load(gameId).orElseThrow(DiplomacyErrors::notAvailable);
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		UUID nation = DiplomacyQueries.nationOf(snapshot, context.gamePlayerId());
		DiplomacyQueries.Ctx ctx = queries.context(gameId, picture, snapshot, nation, context.round());
		return new DiplomacyViews.ProposalResult(queries.proposalView(ctx, treaties.proposal(gameId, id).orElseThrow()));
	}

	private DiplomacyViews.TreatyView treatyView(TreatyRepository.TreatyRow t) {
		return new DiplomacyViews.TreatyView(t.id(), t.warId(), t.proposalId(), t.signedRound(), t.signedTurn(),
				queries.readTerms(t.termsJson()), t.truceUntilRound());
	}

}
