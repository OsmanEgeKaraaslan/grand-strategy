package com.reach.strategy.diplomacy.api;

import java.util.UUID;

/**
 * Published (delivered after commit) whenever a peace conference changed: requested, answered,
 * revised, decided, settled or cancelled. Lets computer participants react at once (Phase 11).
 */
public record ConferenceActivity(UUID gameId) {
}
