package com.reach.strategy.military.application;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.military.api.MilitaryPresence;
import com.reach.strategy.military.domain.MilitaryStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PresenceService implements MilitaryPresence {

	private final MilitaryStore store;

	PresenceService(MilitaryStore store) {
		this.store = store;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Presence> presence(UUID gameId) {
		return store.units(gameId)
			.stream()
			.map(b -> new Presence(b.id(), b.nationId(), b.provinceStateId(), b.unitType().name(), b.manpower()))
			.toList();
	}

}
