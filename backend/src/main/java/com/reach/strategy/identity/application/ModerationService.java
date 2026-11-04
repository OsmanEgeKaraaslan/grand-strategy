package com.reach.strategy.identity.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.reach.strategy.identity.api.AccountSuspensionParticipant;
import com.reach.strategy.identity.api.Administrators;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.domain.DisplayName;
import com.reach.strategy.identity.domain.Player;
import com.reach.strategy.identity.domain.PlayerRepository;
import com.reach.strategy.identity.domain.Username;
import com.reach.strategy.platform.web.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moderation of player accounts by administrators (Q-MOD1): find accounts, suspend and reinstate them,
 * reset offensive names. Every action is recorded with the administrator who took it.
 */
@Service
public class ModerationService {

	private static final int SEARCH_LIMIT = 25;
	private static final int PLACEHOLDER_ATTEMPTS = 5;

	private final PlayerRepository players;
	private final Administrators admins;
	private final List<AccountSuspensionParticipant> participants;
	private final JdbcTemplate jdbc;
	private final Clock clock;

	ModerationService(PlayerRepository players, Administrators admins, List<AccountSuspensionParticipant> participants,
			JdbcTemplate jdbc, Clock clock) {
		this.players = players;
		this.admins = admins;
		// A stable order, so the suspending transaction always takes locks in the same sequence.
		this.participants = participants.stream().sorted(Comparator.comparing(p -> p.getClass().getName())).toList();
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** What an administrator sees of an account. Never email or credentials (those are Supabase's). */
	public record AdminPlayer(UUID id, String username, String displayName, Instant createdAt, boolean deleted,
			Instant bannedAt, String banReason, boolean admin) {
	}

	/**
	 * Human accounts whose handle or display name contains the text, or whose id it is; the newest
	 * accounts without a query.
	 */
	@Transactional(readOnly = true)
	public List<AdminPlayer> search(CurrentPlayer me, String query) {
		admins.requireAdmin(me);
		String text = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
		String like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		return jdbc.query("""
				SELECT id FROM game.player
				WHERE kind = 'HUMAN'
				  AND (? = '' OR lower(username) LIKE ? OR lower(display_name) LIKE ? OR id::text = ?)
				ORDER BY created_at DESC, id
				LIMIT ?
				""", (rs, row) -> rs.getObject("id", UUID.class), text, like, like, text, SEARCH_LIMIT)
			.stream()
			.map(id -> view(players.findById(id).orElseThrow()))
			.toList();
	}

	@Transactional
	public AdminPlayer ban(CurrentPlayer me, UUID playerId, String reason) {
		admins.requireAdmin(me);
		Player player = moderatable(me, playerId);
		Instant now = clock.instant();
		player.ban(reason.strip(), now);
		players.saveAndFlush(player);
		for (AccountSuspensionParticipant participant : participants) {
			participant.accountSuspended(playerId, now);
		}
		admins.record(me, Administrators.Action.BAN, playerId, null, reason.strip());
		return view(player);
	}

	/** Reinstates the account; seats already handed to the computer stay with it. */
	@Transactional
	public AdminPlayer unban(CurrentPlayer me, UUID playerId) {
		admins.requireAdmin(me);
		Player player = moderatable(me, playerId);
		if (player.banned()) {
			player.unban(clock.instant());
			players.saveAndFlush(player);
			admins.record(me, Administrators.Action.UNBAN, playerId, null, null);
		}
		return view(player);
	}

	/** Generated placeholders that are free right now; the unique indexes are the backstop. */
	@Transactional
	public AdminPlayer resetName(CurrentPlayer me, UUID playerId) {
		admins.requireAdmin(me);
		Player player = moderatable(me, playerId);
		for (int attempt = 0; attempt < PLACEHOLDER_ATTEMPTS; attempt++) {
			DisplayName name = DisplayName.generated();
			Username username = Username.generated();
			if (!players.isDisplayNameTakenByOther(name.value(), playerId)
					&& !players.isUsernameTakenByOther(username.value(), playerId)) {
				player.resetName(name, username, clock.instant());
				players.saveAndFlush(player);
				admins.record(me, Administrators.Action.RESET_NAME, playerId, null, null);
				return view(player);
			}
		}
		throw new IllegalStateException("No free placeholder name after " + PLACEHOLDER_ATTEMPTS + " attempts");
	}

	/** Locked; administrators cannot moderate themselves or each other, nor deleted or computer accounts. */
	private Player moderatable(CurrentPlayer me, UUID playerId) {
		Player player = players.findByIdForUpdate(playerId)
			.filter(p -> !p.computer() && !p.deleted())
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "No such player."));
		if (player.id().equals(me.id()) || admins.isAdmin(player.id())) {
			throw new ApiException(HttpStatus.CONFLICT, "CANNOT_MODERATE_ADMIN",
					"Administrators cannot be moderated here; remove them from ADMIN_SUPABASE_USER_IDS first.");
		}
		return player;
	}

	private AdminPlayer view(Player p) {
		return new AdminPlayer(p.id(), p.username(), p.displayName(), p.createdAt(), p.deleted(), p.bannedAt(),
				p.banReason(), admins.isAdmin(p.id()));
	}

}
