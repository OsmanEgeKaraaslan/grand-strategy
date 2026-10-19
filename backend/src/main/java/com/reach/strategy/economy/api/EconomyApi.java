package com.reach.strategy.economy.api;

import java.util.OptionalLong;
import java.util.UUID;

/** Treasury access for other modules' commands (e.g. paying for recruitment). */
public interface EconomyApi {

	/** Current treasury of a nation in milligold, and whether it is in arrears (Q-E1b). */
	TreasuryState treasury(UUID nationId);

	/**
	 * Deducts an amount the caller has already validated as affordable. Must run inside the
	 * caller's command transaction; the non-negative treasury constraint is the backstop.
	 */
	void spend(UUID nationId, long amountMg);

	/**
	 * Moves gold between two treasuries of the same game (reparations, tribute): exactly what leaves
	 * one arrives in the other, so no gold is created or destroyed. Must run inside the caller's
	 * transaction.
	 *
	 * @throws IllegalStateException if the payer's treasury holds less than {@code amountMg}
	 */
	void transfer(UUID fromNationId, UUID toNationId, long amountMg);

	/** Gross income credited to a nation at the end of {@code round}, if that round was settled. */
	OptionalLong grossIncome(UUID gameId, UUID nationId, int round);

	record TreasuryState(long treasuryMg, boolean inArrears) {
	}

}
