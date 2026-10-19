package com.reach.strategy.game.rules;

/**
 * Classic sequential turn order (docs/TURN_SYSTEM.md §3.2): seats act one after another in fixed
 * seat order (seat 0 = host first); after the last seat the global round completes and the next
 * round starts again at seat 0. Every seat is active in the MVP (no concede, no elimination).
 */
public final class TurnOrder {

	private TurnOrder() {
	}

	public static Advance next(int round, int activeSeat, int seatCount) {
		if (seatCount < 1 || activeSeat < 0 || activeSeat >= seatCount || round < 1) {
			throw new IllegalArgumentException(
					"Invalid turn state: round " + round + ", seat " + activeSeat + " of " + seatCount);
		}
		boolean lastSeat = activeSeat == seatCount - 1;
		return lastSeat ? new Advance(true, round, round + 1, 0) : new Advance(false, round, round, activeSeat + 1);
	}

	/**
	 * @param roundCompleted whether this end of turn completes the global round
	 * @param endedRound the round the ended turn belonged to
	 */
	public record Advance(boolean roundCompleted, int endedRound, int nextRound, int nextSeat) {
	}

}
