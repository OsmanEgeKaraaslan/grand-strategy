package com.reach.strategy.research.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import com.reach.strategy.research.rules.InstitutionType;

import org.springframework.data.domain.Persistable;

/** A research institution of a city: under construction until its round end (Q-E8), then ACTIVE. */
@Entity
@Table(name = "institution")
public class Institution implements Persistable<UUID> {

	public enum Status {

		UNDER_CONSTRUCTION, ACTIVE

	}

	@Id
	private UUID id;

	@Column(name = "game_id", nullable = false, updatable = false)
	private UUID gameId;

	@Column(name = "city_id", nullable = false, updatable = false)
	private UUID cityId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private InstitutionType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(name = "ordered_round", nullable = false, updatable = false)
	private int orderedRound;

	@Column(name = "completes_after_round", nullable = false, updatable = false)
	private int completesAfterRound;

	@Column(name = "cost_paid_mg", nullable = false, updatable = false)
	private long costPaidMg;

	@Column(name = "ruleset_version", nullable = false, updatable = false)
	private String rulesetVersion;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew = true;

	protected Institution() {
	}

	public static Institution project(UUID gameId, UUID cityId, InstitutionType type, int orderedRound,
			int completesAfterRound, long costMg, String rulesetVersion, Instant now) {
		Institution i = new Institution();
		i.id = UUID.randomUUID();
		i.gameId = gameId;
		i.cityId = cityId;
		i.type = type;
		i.status = Status.UNDER_CONSTRUCTION;
		i.orderedRound = orderedRound;
		i.completesAfterRound = completesAfterRound;
		i.costPaidMg = costMg;
		i.rulesetVersion = rulesetVersion;
		i.createdAt = now;
		return i;
	}

	public void complete() {
		if (status != Status.UNDER_CONSTRUCTION) {
			throw new IllegalStateException("Institution " + id + " is already active");
		}
		status = Status.ACTIVE;
	}

	@Override
	public UUID getId() {
		return id;
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

	public UUID id() {
		return id;
	}

	public UUID cityId() {
		return cityId;
	}

	public InstitutionType type() {
		return type;
	}

	public Status status() {
		return status;
	}

	public boolean active() {
		return status == Status.ACTIVE;
	}

	public int completesAfterRound() {
		return completesAfterRound;
	}

	public long costPaidMg() {
		return costPaidMg;
	}

	public String rulesetVersion() {
		return rulesetVersion;
	}

}
