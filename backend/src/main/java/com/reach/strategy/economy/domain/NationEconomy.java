package com.reach.strategy.economy.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import com.reach.strategy.economy.rules.Treasury;

import org.springframework.data.domain.Persistable;

/** A nation's treasury in one game, in milligold. Never negative (also a database CHECK). */
@Entity
@Table(name = "nation_economy")
public class NationEconomy implements Persistable<UUID> {

	@Id
	@Column(name = "nation_id")
	private UUID nationId;

	@Column(name = "game_id", nullable = false, updatable = false)
	private UUID gameId;

	@Column(name = "treasury_mg", nullable = false)
	private long treasuryMg;

	@Column(name = "in_arrears", nullable = false)
	private boolean inArrears;

	@Transient
	private boolean isNew = true;

	protected NationEconomy() {
	}

	public static NationEconomy opening(UUID gameId, UUID nationId, long treasuryMg) {
		NationEconomy economy = new NationEconomy();
		economy.gameId = gameId;
		economy.nationId = nationId;
		economy.treasuryMg = treasuryMg;
		return economy;
	}

	/** Pays for something; the caller has checked affordability (the CHECK constraint is the backstop). */
	public void spend(long amountMg) {
		if (amountMg < 0 || amountMg > treasuryMg) {
			throw new IllegalStateException("Cannot spend " + amountMg + " mg from " + treasuryMg + " mg");
		}
		treasuryMg -= amountMg;
	}

	/** Receives gold another nation paid (reparations, tribute) — never created, only moved. */
	public void receive(long amountMg) {
		if (amountMg < 0) {
			throw new IllegalArgumentException("Cannot receive " + amountMg + " mg");
		}
		treasuryMg = Math.addExact(treasuryMg, amountMg);
	}

	public void apply(Treasury.Settlement settlement) {
		treasuryMg = settlement.treasuryAfterMg();
		inArrears = settlement.inArrears();
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

	public long treasuryMg() {
		return treasuryMg;
	}

	public boolean inArrears() {
		return inArrears;
	}

}
