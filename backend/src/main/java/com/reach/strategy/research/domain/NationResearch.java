package com.reach.strategy.research.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import com.reach.strategy.research.rules.InstitutionType.Pool;
import com.reach.strategy.research.rules.ResearchRules.Track;

import org.springframework.data.domain.Persistable;

/** A nation's research: three point pools and two technology levels. Points never go negative. */
@Entity
@Table(name = "nation_research")
public class NationResearch implements Persistable<UUID> {

	@Id
	@Column(name = "nation_id")
	private UUID nationId;

	@Column(name = "game_id", nullable = false, updatable = false)
	private UUID gameId;

	@Column(name = "military_points", nullable = false)
	private int militaryPoints;

	@Column(name = "civilian_points", nullable = false)
	private int civilianPoints;

	@Column(name = "doctrine_points", nullable = false)
	private int doctrinePoints;

	@Column(name = "military_level", nullable = false)
	private int militaryLevel;

	@Column(name = "civilian_level", nullable = false)
	private int civilianLevel;

	@Column(name = "ruleset_version", nullable = false, updatable = false)
	private String rulesetVersion;

	@Transient
	private boolean isNew = true;

	protected NationResearch() {
	}

	public static NationResearch start(UUID gameId, UUID nationId, String rulesetVersion) {
		NationResearch r = new NationResearch();
		r.gameId = gameId;
		r.nationId = nationId;
		r.militaryLevel = 1;
		r.civilianLevel = 1;
		r.rulesetVersion = rulesetVersion;
		return r;
	}

	public int points(Pool pool) {
		return switch (pool) {
			case MILITARY -> militaryPoints;
			case CIVILIAN -> civilianPoints;
			case DOCTRINE -> doctrinePoints;
		};
	}

	public void add(Pool pool, int points) {
		if (points < 0) {
			throw new IllegalArgumentException("Negative research income");
		}
		set(pool, Math.addExact(points(pool), points));
	}

	/** Deducts points the caller has validated; a shortfall is a bug and fails the command. */
	public void spend(Pool pool, int points) {
		if (points <= 0 || points > points(pool)) {
			throw new IllegalStateException("Cannot spend " + points + " of " + points(pool) + " " + pool + " points");
		}
		set(pool, points(pool) - points);
	}

	private void set(Pool pool, int value) {
		switch (pool) {
			case MILITARY -> militaryPoints = value;
			case CIVILIAN -> civilianPoints = value;
			case DOCTRINE -> doctrinePoints = value;
		}
	}

	public int level(Track track) {
		return track == Track.MILITARY ? militaryLevel : civilianLevel;
	}

	public void raise(Track track, int newLevel) {
		if (newLevel != level(track) + 1) {
			throw new IllegalStateException("Technology levels rise one at a time");
		}
		if (track == Track.MILITARY) {
			militaryLevel = newLevel;
		}
		else {
			civilianLevel = newLevel;
		}
	}

	@Override
	public UUID getId() {
		return nationId;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		isNew = false;
	}

	public UUID nationId() {
		return nationId;
	}

	public UUID gameId() {
		return gameId;
	}

	public String rulesetVersion() {
		return rulesetVersion;
	}

}
