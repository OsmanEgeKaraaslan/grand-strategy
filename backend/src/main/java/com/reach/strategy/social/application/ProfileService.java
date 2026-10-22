package com.reach.strategy.social.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.reach.strategy.game.api.MatchRecords;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PublicProfiles;
import com.reach.strategy.social.rules.MatchStatistics;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public profiles (Phase 12A): the public account fields, statistics computed from the stored
 * results on every read (cheap: one indexed query per profile) and — when the owner allows it, or
 * for the owner — the most recent completed matches. Nothing private leaves this service.
 */
@Service
public class ProfileService {

	static final int RECENT_MATCHES = 10;

	private final PublicProfiles profiles;
	private final MatchRecords records;
	private final Relationships relationships;

	ProfileService(PublicProfiles profiles, MatchRecords records, Relationships relationships) {
		this.profiles = profiles;
		this.records = records;
		this.relationships = relationships;
	}

	@Transactional(readOnly = true)
	public ProfileView profile(CurrentPlayer me, String username) {
		PublicProfiles.PublicPlayer player = profiles.byUsername(username).orElseThrow(SocialErrors::playerNotFound);
		boolean you = player.id().equals(me.id());
		List<MatchRecords.CompletedMatch> matches = records.completedMatches(player.id());
		MatchStatistics.Summary summary = MatchStatistics.of(matches.stream()
			.map(m -> new MatchStatistics.Match(MatchStatistics.Outcome.valueOf(m.outcome().name()), m.factionKey(),
					m.factionName()))
			.toList());
		List<RecentMatch> recent = you || player.matchHistoryPublic()
				? matches.stream()
					.limit(RECENT_MATCHES)
					.map(m -> new RecentMatch(you ? m.gameId() : null, m.gameName(), m.mapName(), m.finishedAt(),
							m.finishedRound(), m.outcome().name(), m.rank(), m.factionName()))
					.toList()
				: null;
		return new ProfileView(player.username(), player.displayName(), player.emblem(), player.memberSince(), you,
				relationships.between(me.id(), player.id()).name(), player.matchHistoryPublic(),
				new Statistics(summary.completed(), summary.wins(), summary.losses(), summary.draws(), summary.winRateBps(),
						summary.favouriteFaction().map(ProfileService::faction).orElse(null),
						summary.factions().stream().map(ProfileService::faction).toList()),
				recent);
	}

	private static Faction faction(MatchStatistics.FactionRecord f) {
		return new Faction(f.key(), f.name(), f.played(), f.wins());
	}

	/**
	 * @param relation the viewer's relation to this player (YOU, NONE, FRIEND, REQUEST_SENT,
	 * REQUEST_RECEIVED, BLOCKED)
	 * @param recentMatches newest first; null when the owner hides the history from others
	 */
	public record ProfileView(String username, String displayName, String emblem, Instant memberSince, boolean you,
			String relation, boolean matchHistoryPublic, Statistics statistics, List<RecentMatch> recentMatches) {
	}

	/** @param winRateBps wins per 10 000 completed matches, rounded down; null without completed matches */
	public record Statistics(int completed, int wins, int losses, int draws, Integer winRateBps, Faction favouriteFaction,
			List<Faction> factions) {
	}

	public record Faction(String key, String name, int played, int wins) {
	}

	/** @param gameId only on the owner's own profile (games are private to their members) */
	public record RecentMatch(UUID gameId, String gameName, String mapName, Instant finishedAt, int finishedRound,
			String outcome, int rank, String factionName) {
	}

}
