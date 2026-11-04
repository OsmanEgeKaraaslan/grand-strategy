package com.reach.strategy.social.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.game.api.Lobbies;
import com.reach.strategy.game.api.MatchCreation;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PublicProfiles;
import com.reach.strategy.social.domain.TicketStore;
import com.reach.strategy.social.rules.QuickMatchQueue;

import com.reach.strategy.platform.web.ApiException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unranked Quick Match queue (Phase 13B, docs/TURN_SYSTEM.md §9): two human players, every other
 * Grand Campaign nation played by the computer.
 * <p>
 * Consistency: every change to the queue first takes a PostgreSQL transaction-level advisory lock
 * for the queue, so joins, heartbeats (which also look for an opponent) and cancellations are
 * serialized across threads and backend instances. A pairing creates the game and marks both
 * tickets MATCHED in one transaction: a ticket is matched at most once, a pair yields exactly one
 * game, and a failure rolls everything back. A player has at most one WAITING ticket (unique index),
 * so a repeated join returns the same search.
 * <p>
 * Abandoned searches: a WAITING ticket must be refreshed by heartbeats; one without a heartbeat for
 * the search timeout is never matched and becomes EXPIRED. This only concerns the search — games
 * themselves have no turn timer and stay resumable forever.
 */
@Service
public class MatchmakingService {

	static final String QUEUE = "QUICK_2P";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final TicketStore tickets;
	private final MatchCreation matches;
	private final Lobbies lobbies;
	private final PublicProfiles profiles;
	private final Notifications notifications;
	private final Clock clock;
	private final String mapKey;
	private final Duration searchTimeout;
	private final int maxUnfinishedGames;

	MatchmakingService(TicketStore tickets, MatchCreation matches, Lobbies lobbies, PublicProfiles profiles,
			Notifications notifications, Clock clock,
			@Value("${strategy.social.matchmaking.map-key:grand-campaign}") String mapKey,
			@Value("${strategy.social.matchmaking.search-timeout:PT90S}") Duration searchTimeout,
			@Value("${strategy.social.matchmaking.max-unfinished-games:5}") int maxUnfinishedGames) {
		this.tickets = tickets;
		this.matches = matches;
		this.lobbies = lobbies;
		this.profiles = profiles;
		this.notifications = notifications;
		this.clock = clock;
		this.mapKey = mapKey;
		this.searchTimeout = searchTimeout;
		this.maxUnfinishedGames = maxUnfinishedGames;
	}

	/**
	 * @param state NONE (never searched or the last match was opened), SEARCHING, MATCHED (not yet
	 * opened, or just now), CANCELLED or EXPIRED
	 * @param match the created game, for MATCHED
	 */
	public record QuickMatchView(String state, UUID ticketId, Instant searchingSince, Match match) {
	}

	public record Match(UUID gameId, String gameName, String mapName, Faction yourFaction, Opponent opponent,
			boolean opened) {
	}

	public record Faction(String key, String name, String color) {
	}

	public record Opponent(String username, String displayName, String emblem, Faction faction) {
	}

	/** Read-only: the caller's current Quick Match state (used on page load, never starts a search). */
	@Transactional(readOnly = true)
	public QuickMatchView status(CurrentPlayer me) {
		return view(me, tickets.latest(me.id(), QUEUE));
	}

	/**
	 * Joins the queue, or returns the existing search or the not-yet-opened match (so a reconnecting
	 * player recovers their match instead of starting another). Looks for an opponent at once.
	 */
	@Transactional
	public QuickMatchView join(CurrentPlayer me) {
		Instant now = clock.instant();
		tickets.lockQueue(QUEUE);
		tickets.expireStale(QUEUE, now.minus(searchTimeout), now);
		Optional<TicketStore.Ticket> latest = tickets.latest(me.id(), QUEUE);
		boolean unopenedMatchRunning = latest.isPresent() && "MATCHED".equals(latest.get().status())
				&& latest.get().acknowledgedAt() == null
				&& lobbies.facts(latest.get().matchedGameId()).filter(f -> !"FINISHED".equals(f.status()) && !"ABANDONED".equals(f.status())).isPresent();
		Optional<TicketStore.Ticket> waiting = tickets.waiting(me.id(), QUEUE);
		return switch (QuickMatchQueue.decideJoin(unopenedMatchRunning, waiting.isPresent(),
				waiting.isPresent() || unopenedMatchRunning ? 0 : lobbies.unfinishedGames(me.id()), maxUnfinishedGames)) {
			case RECOVER_MATCH -> view(me, latest);
			case CONTINUE_SEARCH -> {
				tickets.heartbeat(waiting.get().id(), now);
				yield tryMatch(me, waiting.get().id(), now);
			}
			case REFUSE_TOO_MANY_GAMES -> throw SocialErrors.conflict("TOO_MANY_ACTIVE_GAMES",
					"Finish some of your " + maxUnfinishedGames + " open games before searching for another.");
			case START_SEARCH -> {
				if (!lobbies.capacityAvailable()) {
					throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GAME_CAPACITY_REACHED",
							"The server is running as many games as it can right now. Please try again later.");
				}
				UUID id = UUID.randomUUID();
				tickets.insertWaiting(id, me.id(), QUEUE, now);
				yield tryMatch(me, id, now);
			}
		};
	}

	/** Keeps a search alive and looks for an opponent; outside a search it just reports the state. */
	@Transactional
	public QuickMatchView heartbeat(CurrentPlayer me) {
		Instant now = clock.instant();
		tickets.lockQueue(QUEUE);
		tickets.expireStale(QUEUE, now.minus(searchTimeout), now);
		Optional<TicketStore.Ticket> waiting = tickets.waiting(me.id(), QUEUE);
		if (waiting.isEmpty()) {
			return view(me, tickets.latest(me.id(), QUEUE));
		}
		tickets.heartbeat(waiting.get().id(), now);
		return tryMatch(me, waiting.get().id(), now);
	}

	/**
	 * Stops searching. Serialized with matching by the queue lock: if a match was already committed,
	 * it is not undone (the opponent is in that game too) — the caller gets the MATCHED state instead.
	 */
	@Transactional
	public QuickMatchView cancel(CurrentPlayer me) {
		Instant now = clock.instant();
		tickets.lockQueue(QUEUE);
		tickets.waiting(me.id(), QUEUE).ifPresent(t -> tickets.cancel(t.id(), now));
		return view(me, tickets.latest(me.id(), QUEUE));
	}

	/** The player opened the matched game: a later join starts a new search. */
	@Transactional
	public QuickMatchView acknowledge(CurrentPlayer me) {
		tickets.latest(me.id(), QUEUE).ifPresent(t -> tickets.acknowledge(t.id(), clock.instant()));
		return view(me, tickets.latest(me.id(), QUEUE));
	}

	/** Pairs the caller's waiting ticket with the longest-waiting live opponent, if any (queue lock held). */
	private QuickMatchView tryMatch(CurrentPlayer me, UUID myTicket, Instant now) {
		Optional<TicketStore.Ticket> opponent = tickets.oldestOpponent(me.id(), QUEUE, now.minus(searchTimeout));
		if (opponent.isPresent()) {
			UUID other = opponent.get().playerId();
			// The player who waited longer takes the first seat (and the first turn).
			MatchCreation.CreatedMatch created = matches.createQuickMatch(mapKey, List.of(other, me.id()), RANDOM.nextLong());
			tickets.markMatched(opponent.get().id(), created.gameId(), me.id(), now);
			tickets.markMatched(myTicket, created.gameId(), other, now);
			notifications.notify(other, Notifications.Kind.MATCH_FOUND, me.id(), created.gameId(), opponent.get().id(), now);
		}
		return view(me, tickets.latest(me.id(), QUEUE));
	}

	private QuickMatchView view(CurrentPlayer me, Optional<TicketStore.Ticket> latest) {
		if (latest.isEmpty()) {
			return new QuickMatchView("NONE", null, null, null);
		}
		TicketStore.Ticket t = latest.get();
		String state = QuickMatchQueue.displayedState(t.status(), t.heartbeatAt(), t.acknowledgedAt() != null, clock.instant(),
				searchTimeout);
		return new QuickMatchView(state, t.id(), t.createdAt(), "MATCHED".equals(t.status()) ? match(me, t) : null);
	}

	private Match match(CurrentPlayer me, TicketStore.Ticket t) {
		Lobbies.LobbyFacts facts = lobbies.facts(t.matchedGameId()).orElseThrow();
		Map<UUID, Lobbies.Faction> factions = lobbies.factions(t.matchedGameId());
		PublicProfiles.PublicPlayer opponent = profiles.byIds(List.of(t.opponentId())).get(t.opponentId());
		return new Match(t.matchedGameId(), facts.name(), facts.mapName(), faction(factions.get(me.id())),
				opponent == null ? null
						: new Opponent(opponent.username(), opponent.displayName(), opponent.emblem(),
								faction(factions.get(t.opponentId()))),
				t.acknowledgedAt() != null);
	}

	private static Faction faction(Lobbies.Faction f) {
		return f == null ? null : new Faction(f.key(), f.name(), f.color());
	}

}
