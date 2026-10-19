package com.reach.strategy.diplomacy.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.diplomacy.domain.RelationsRepository;
import com.reach.strategy.diplomacy.rules.WarScore;
import com.reach.strategy.world.api.WorldApi;

/**
 * Builds a war's score from its recorded battle and siege points and the current world (who owns
 * and who controls every city). Nothing here is stored, so the score always matches the state.
 */
final class WarScores {

	private WarScores() {
	}

	/** @param events every war-score event of the game (filtered here by war) */
	static Detailed of(RelationsRepository.WarRow war, List<RelationsRepository.ParticipantRow> participants,
			List<RelationsRepository.ScoreEventRow> events, WorldApi.WorldSnapshot world, DiplomacyState.Picture picture) {
		Set<UUID> attackers = side(participants, "ATTACKER");
		Set<UUID> defenders = side(participants, "DEFENDER");
		List<RelationsRepository.ScoreEventRow> mine = events.stream().filter(e -> e.warId().equals(war.id())).toList();
		Facts a = facts("ATTACKER", attackers, defenders, mine, world);
		Facts d = facts("DEFENDER", defenders, attackers, mine, world);
		WarScore.Score score = WarScore.of(a.facts(), d.facts(), picture.rules().warScore());
		return new Detailed(score, a, d);
	}

	record Facts(WarScore.SideFacts facts, int battlesWon, int siegesWon) {
	}

	record Detailed(WarScore.Score score, Facts attacker, Facts defender) {
	}

	private static Facts facts(String side, Set<UUID> us, Set<UUID> them, List<RelationsRepository.ScoreEventRow> events,
			WorldApi.WorldSnapshot world) {
		long battlePoints = 0;
		long siegePoints = 0;
		int battles = 0;
		int sieges = 0;
		for (RelationsRepository.ScoreEventRow e : events) {
			if (!e.side().equals(side)) {
				continue;
			}
			if ("BATTLE".equals(e.kind())) {
				battlePoints += e.points();
				battles++;
			}
			else {
				siegePoints += e.points();
				sieges++;
			}
		}
		long enemyPopulation = 0;
		long occupied = 0;
		for (WorldApi.ProvinceState p : world.provinces()) {
			if (them.contains(p.ownerNationId())) {
				enemyPopulation += p.population();
				if (us.contains(p.controllerNationId())) {
					occupied += p.population();
				}
			}
		}
		return new Facts(new WarScore.SideFacts(battlePoints, siegePoints, occupied, enemyPopulation), battles, sieges);
	}

	private static Set<UUID> side(List<RelationsRepository.ParticipantRow> participants, String side) {
		return participants.stream()
			.filter(p -> p.side().equals(side))
			.map(RelationsRepository.ParticipantRow::nationId)
			.collect(Collectors.toSet());
	}

}
