package com.reach.strategy.economy.rules;

/**
 * Round-end treasury settlement (APPROVED order Q-E1: income, then upkeep; deficit rule Q-E1b).
 */
public final class Treasury {

	private Treasury() {
	}

	/**
	 * @param treasuryAfterMg never negative
	 * @param deficitMg unpaid amount when income and treasury could not cover upkeep, else 0
	 * @param inArrears set by a deficit; cleared by the next round end that settles without one
	 */
	public record Settlement(long grossIncomeMg, long upkeepMg, long treasuryAfterMg, long deficitMg, boolean inArrears) {
	}

	/**
	 * {@code treasury + income − upkeep}. If that would be negative, the treasury floors at 0, the
	 * shortfall is recorded as deficit and the nation is in arrears for the following round
	 * (Q-E1b; no debt and no interest). Exact long arithmetic; overflow throws.
	 */
	public static Settlement settle(long treasuryMg, long grossIncomeMg, long upkeepMg) {
		if (treasuryMg < 0 || grossIncomeMg < 0 || upkeepMg < 0) {
			throw new IllegalArgumentException("Negative amount in settlement");
		}
		long balance = Math.subtractExact(Math.addExact(treasuryMg, grossIncomeMg), upkeepMg);
		if (balance < 0) {
			return new Settlement(grossIncomeMg, upkeepMg, 0, -balance, true);
		}
		return new Settlement(grossIncomeMg, upkeepMg, balance, 0, false);
	}

}
