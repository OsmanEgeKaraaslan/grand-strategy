package com.reach.strategy.game.rules;

import java.util.Set;

/**
 * Classic sequential turn order (docs/TURN_SYSTEM.md §3.2): seats act one after another in fixed
 * seat order (seat 0 = host first); after the last seat the global round completes and the next
 * round starts again at the first seat. Seats of eliminated players are skipped (Phase 10); nations
 * without a human player have no seat and take no turn.
 */
public final class TurnOrder {

	private TurnOrder() {
	}

	public static Advance next(int round, int activeSeat, int seatCount) {
		return next(round, activeSeat, seatCount, Set.of());
	}

	/**
	 * @param skippedSeats seats that take no turn any more (eliminated players); at least one seat
	 * must remain
	 */
	public static Advance next(int round, int activeSeat, int seatCount, Set<Integer> skippedSeats) {
		if (seatCount < 1 || activeSeat < 0 || activeSeat >= seatCount || round < 1) {
			throw new IllegalArgumentException(
					"Invalid turn state: round " + round + ", seat " + activeSeat + " of " + seatCount);
		}
		if (skippedSeats.size() >= seatCount && skippedSeats.stream().allMatch(s -> s >= 0 && s < seatCount)) {
			throw new IllegalArgumentException("No seat left to take a turn");
		}
		for (int seat = activeSeat + 1; seat < seatCount; seat++) {
			if (!skippedSeats.contains(seat)) {
				return new Advance(false, round, round, seat);
			}
		}
		return new Advance(true, round, round + 1, firstSeat(seatCount, skippedSeats));
	}

	/** The first seat that still takes turns. */
	public static int firstSeat(int seatCount, Set<Integer> skippedSeats) {
		for (int seat = 0; seat < seatCount; seat++) {
			if (!skippedSeats.contains(seat)) {
				return seat;
			}
		}
		throw new IllegalArgumentException("No seat left to take a turn");
	}

	/**
	 * @param roundCompleted whether this end of turn completes the global round
	 * @param endedRound the round the ended turn belonged to
	 */
	public record Advance(boolean roundCompleted, int endedRound, int nextRound, int nextSeat) {
	}

}
