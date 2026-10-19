package com.reach.strategy.diplomacy.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.reach.strategy.diplomacy.domain.RelationsRepository;
import com.reach.strategy.diplomacy.rules.Diplomacy;
import com.reach.strategy.diplomacy.rules.DiplomacyRules;
import com.reach.strategy.military.api.Hostility;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Loads a game's diplomatic relations once per request or round step, as the rules see them. */
@Component
class DiplomacyState {

	private final RelationsRepository relations;
	private final DiplomacyRulesets rulesets;

	DiplomacyState(RelationsRepository relations, DiplomacyRulesets rulesets) {
		this.relations = relations;
		this.rulesets = rulesets;
	}

	/** Empty for games without diplomacy (rulesets before v5). */
	@Transactional(readOnly = true)
	Optional<Picture> load(UUID gameId) {
		return relations.rulesetVersion(gameId)
			.map(version -> new Picture(gameId, rulesets.rules(version), relations.alliances(gameId),
					relations.wars(gameId), relations.participants(gameId), relations.activeFronts(gameId),
					relations.truces(gameId)));
	}

	@Transactional(readOnly = true)
	List<RelationsRepository.ScoreEventRow> scoreEvents(UUID gameId) {
		return relations.scoreEvents(gameId);
	}

	/** Everything diplomatic about one game at one moment. */
	record Picture(UUID gameId, DiplomacyRules rules, List<RelationsRepository.AllianceRow> alliances,
			List<RelationsRepository.WarRow> wars, List<RelationsRepository.ParticipantRow> participants,
			List<RelationsRepository.FrontRow> fronts, List<RelationsRepository.TruceRow> truces) {

		Diplomacy.Standing standing() {
			Set<Diplomacy.Pair> wars = new HashSet<>();
			fronts.forEach(f -> wars.add(Diplomacy.Pair.of(f.nationA(), f.nationB())));
			Map<Diplomacy.Pair, Diplomacy.AllianceKind> active = new HashMap<>();
			Set<Diplomacy.Pair> pending = new HashSet<>();
			for (RelationsRepository.AllianceRow a : alliances) {
				if ("ACTIVE".equals(a.status())) {
					active.put(Diplomacy.Pair.of(a.nationA(), a.nationB()), Diplomacy.AllianceKind.valueOf(a.kind()));
				}
				else if ("PROPOSED".equals(a.status())) {
					pending.add(Diplomacy.Pair.of(a.nationA(), a.nationB()));
				}
			}
			Map<Diplomacy.Pair, Integer> truce = new HashMap<>();
			truces.forEach(t -> truce.merge(Diplomacy.Pair.of(t.nationA(), t.nationB()), t.untilRound(), Math::max));
			Set<Diplomacy.Pair> together = new HashSet<>();
			for (RelationsRepository.WarRow war : this.wars) {
				if (!war.active()) {
					continue;
				}
				for (RelationsRepository.ParticipantRow x : participantsOf(war.id())) {
					for (RelationsRepository.ParticipantRow y : participantsOf(war.id())) {
						if (!x.nationId().equals(y.nationId()) && x.side().equals(y.side())) {
							together.add(Diplomacy.Pair.of(x.nationId(), y.nationId()));
						}
					}
				}
			}
			return new Diplomacy.Standing(wars, active, pending, truce, together);
		}

		/** The military module's view: wars and directed military access. */
		Hostility.Relations hostility() {
			Set<Hostility.Pair> warPairs = new HashSet<>();
			fronts.forEach(f -> warPairs.add(Hostility.Pair.of(f.nationA(), f.nationB())));
			Set<Hostility.Access> access = new HashSet<>();
			for (RelationsRepository.AllianceRow a : alliances) {
				if ("ACTIVE".equals(a.status())
						&& rules.militaryAccess().grantedBy(Diplomacy.AllianceKind.valueOf(a.kind()))) {
					access.add(new Hostility.Access(a.nationA(), a.nationB()));
					access.add(new Hostility.Access(a.nationB(), a.nationA()));
				}
			}
			if (rules.militaryAccess().coBelligerents()) {
				for (RelationsRepository.WarRow war : wars) {
					if (!war.active()) {
						continue;
					}
					List<RelationsRepository.ParticipantRow> members = participantsOf(war.id());
					for (RelationsRepository.ParticipantRow x : members) {
						for (RelationsRepository.ParticipantRow y : members) {
							if (!x.nationId().equals(y.nationId()) && x.side().equals(y.side())) {
								access.add(new Hostility.Access(x.nationId(), y.nationId()));
							}
						}
					}
				}
			}
			return new Hostility.Relations(false, warPairs, access);
		}

		List<RelationsRepository.ParticipantRow> participantsOf(UUID warId) {
			return participants.stream().filter(p -> p.warId().equals(warId)).toList();
		}

		Optional<RelationsRepository.WarRow> war(UUID warId) {
			return wars.stream().filter(w -> w.id().equals(warId)).findFirst();
		}

		/** The active war in which these two nations fight each other. */
		Optional<RelationsRepository.WarRow> activeWarBetween(UUID x, UUID y) {
			if (x.equals(y)) {
				return Optional.empty();
			}
			Diplomacy.Pair pair = Diplomacy.Pair.of(x, y);
			return fronts.stream()
				.filter(f -> Diplomacy.Pair.of(f.nationA(), f.nationB()).equals(pair))
				.findFirst()
				.flatMap(f -> war(f.warId()));
		}

		Optional<String> sideOf(UUID warId, UUID nationId) {
			return participantsOf(warId).stream()
				.filter(p -> p.nationId().equals(nationId))
				.map(RelationsRepository.ParticipantRow::side)
				.findFirst();
		}

		/** Whether two nations fight on the same side of some active war. */
		boolean fightingTogether(UUID x, UUID y) {
			return wars.stream().filter(RelationsRepository.WarRow::active).anyMatch(w -> {
				Optional<String> a = sideOf(w.id(), x);
				Optional<String> b = sideOf(w.id(), y);
				return a.isPresent() && a.equals(b);
			});
		}

	}

}
