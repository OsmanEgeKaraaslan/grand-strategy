package com.reach.strategy.military.application;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.military.api.MilitaryPresence;
import com.reach.strategy.military.domain.BattalionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PresenceService implements MilitaryPresence {

	private final BattalionRepository battalions;

	PresenceService(BattalionRepository battalions) {
		this.battalions = battalions;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Presence> presence(UUID gameId) {
		return battalions.findByGameIdOrderById(gameId)
			.stream()
			.map(b -> new Presence(b.id(), b.nationId(), b.provinceStateId(), b.unitType().name(), b.manpower()))
			.toList();
	}

}
