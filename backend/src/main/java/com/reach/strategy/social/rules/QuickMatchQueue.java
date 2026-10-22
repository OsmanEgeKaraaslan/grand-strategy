package com.reach.strategy.social.rules;

import java.time.Duration;
import java.time.Instant;

/**
 * Decisions of the Quick Match queue (Phase 13B, docs/TURN_SYSTEM.md §9). Ticket states:
 *
 * <pre>
 * WAITING ──paired──────────────▶ MATCHED   (game created in the same transaction)
 * WAITING ──cancel──────────────▶ CANCELLED
 * WAITING ──no heartbeat────────▶ EXPIRED   (never matched afterwards)
 * </pre>
 */
public final class QuickMatchQueue {

	private QuickMatchQueue() {
	}

	public enum Join {
		/** An unopened match exists whose game is still running: show it, do not search again. */
		RECOVER_MATCH,
		/** Already searching: refresh the heartbeat and look for an opponent. */
		CONTINUE_SEARCH,
		/** Too many unfinished games: no new search (bounds game creation). */
		REFUSE_TOO_MANY_GAMES,
		START_SEARCH
	}

	/**
	 * @param unopenedMatchRunning the latest ticket is MATCHED, not yet opened, and its game is unfinished
	 * @param waiting the player holds a live WAITING ticket
	 */
	public static Join decideJoin(boolean unopenedMatchRunning, boolean waiting, int unfinishedGames, int maxUnfinishedGames) {
		if (unopenedMatchRunning) {
			return Join.RECOVER_MATCH;
		}
		if (waiting) {
			return Join.CONTINUE_SEARCH;
		}
		return unfinishedGames >= maxUnfinishedGames ? Join.REFUSE_TOO_MANY_GAMES : Join.START_SEARCH;
	}

	/** A WAITING ticket can be matched only while its last heartbeat is within the search timeout. */
	public static boolean live(Instant heartbeatAt, Instant now, Duration searchTimeout) {
		return !heartbeatAt.isBefore(now.minus(searchTimeout));
	}

	/**
	 * What the player sees: NONE, SEARCHING, MATCHED, CANCELLED or EXPIRED. An opened match reads as
	 * NONE (the next join searches again); a WAITING ticket past its timeout reads as EXPIRED even
	 * before the next queue change marks it so.
	 */
	public static String displayedState(String status, Instant heartbeatAt, boolean opened, Instant now,
			Duration searchTimeout) {
		return switch (status) {
			case "WAITING" -> live(heartbeatAt, now, searchTimeout) ? "SEARCHING" : "EXPIRED";
			case "MATCHED" -> opened ? "NONE" : "MATCHED";
			default -> status;
		};
	}

}
