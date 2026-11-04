package com.reach.strategy.identity.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.identity.api.Administrators;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.domain.Player;
import com.reach.strategy.identity.domain.PlayerRepository;
import com.reach.strategy.platform.web.ApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminAccess implements Administrators {

	private static final Logger log = LoggerFactory.getLogger(AdminAccess.class);

	private final Set<UUID> adminSupabaseUserIds;
	private final PlayerRepository players;
	private final JdbcTemplate jdbc;
	private final Clock clock;

	AdminAccess(@Value("${strategy.admin.supabase-user-ids:}") String adminSupabaseUserIds, PlayerRepository players,
			JdbcTemplate jdbc, Clock clock) {
		this.adminSupabaseUserIds = Arrays.stream(adminSupabaseUserIds.split(","))
			.map(String::strip)
			.filter(id -> !id.isEmpty())
			.map(UUID::fromString)
			.collect(Collectors.toUnmodifiableSet());
		this.players = players;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isAdmin(UUID playerId) {
		return !adminSupabaseUserIds.isEmpty() && players.findById(playerId)
			.map(Player::supabaseUserId)
			.filter(adminSupabaseUserIds::contains)
			.isPresent();
	}

	@Override
	public void requireAdmin(CurrentPlayer me) {
		if (!isAdmin(me.id())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ONLY", "Only administrators can do that.");
		}
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(CurrentPlayer admin, Action action, UUID targetPlayerId, UUID targetGameId, String reason) {
		jdbc.update("""
				INSERT INTO game.moderation_action (id, admin_player_id, action, target_player_id, target_game_id, reason, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), admin.id(), action.name(), targetPlayerId, targetGameId, reason,
				Timestamp.from(clock.instant()));
		// Ids only (no names or reasons): who did what stays in the moderation_action table.
		log.info("Moderation {} by admin {} on player {} game {}", action, admin.id(), targetPlayerId, targetGameId);
	}

}
