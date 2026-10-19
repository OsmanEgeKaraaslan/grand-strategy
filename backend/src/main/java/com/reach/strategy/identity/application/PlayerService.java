package com.reach.strategy.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PlayerDirectory;
import com.reach.strategy.identity.domain.DisplayName;
import com.reach.strategy.identity.domain.Player;
import com.reach.strategy.identity.domain.PlayerRepository;
import com.reach.strategy.platform.web.ApiException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlayerService implements PlayerDirectory {

	private static final int GENERATED_NAME_ATTEMPTS = 3;

	private final PlayerRepository players;
	private final Clock clock;

	PlayerService(PlayerRepository players, Clock clock) {
		this.players = players;
		this.clock = clock;
	}

	/**
	 * Returns the player for a verified Supabase user, creating it on first login. The requested
	 * name (from sign-up metadata) is used when valid and free, otherwise a generated one.
	 */
	@Transactional
	public CurrentPlayer resolve(AuthenticatedUser user) {
		Player player = players.findBySupabaseUserId(user.supabaseUserId()).orElseGet(() -> provision(user));
		return new CurrentPlayer(player.id(), player.displayName());
	}

	@Transactional(readOnly = true)
	public PlayerProfile profile(UUID playerId) {
		return PlayerProfile.of(load(playerId));
	}

	@Transactional
	public PlayerProfile rename(UUID playerId, String requestedName) {
		DisplayName name = DisplayName.parse(requestedName)
			.orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DISPLAY_NAME",
					"Display names have 3–24 letters, digits, spaces, '_' or '-'."));
		if (players.isDisplayNameTakenByOther(name.value(), playerId)) {
			throw displayNameTaken();
		}
		Player player = load(playerId);
		player.rename(name, clock.instant());
		try {
			players.saveAndFlush(player);
		}
		catch (DataIntegrityViolationException raceWithAnotherRename) {
			throw displayNameTaken();
		}
		return PlayerProfile.of(player);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> displayNames(Collection<UUID> playerIds) {
		return players.findByIdIn(playerIds).stream().collect(Collectors.toMap(Player::id, Player::displayName));
	}

	private Player provision(AuthenticatedUser user) {
		Instant now = clock.instant();
		Optional<DisplayName> requested = DisplayName.parse(user.requestedDisplayName());
		if (requested.isEmpty() || insert(user, requested.get(), now) == 0) {
			for (int attempt = 0; attempt < GENERATED_NAME_ATTEMPTS; attempt++) {
				if (players.findBySupabaseUserId(user.supabaseUserId()).isPresent()
						|| insert(user, DisplayName.generated(), now) == 1) {
					break;
				}
			}
		}
		// Either our insert succeeded or a concurrent first request of the same user committed first.
		return players.findBySupabaseUserId(user.supabaseUserId())
			.orElseThrow(() -> new IllegalStateException("Could not provision player account"));
	}

	private int insert(AuthenticatedUser user, DisplayName name, Instant now) {
		return players.insertIfAbsent(UUID.randomUUID(), user.supabaseUserId(), name.value(), now);
	}

	private Player load(UUID playerId) {
		return players.findById(playerId)
			.orElseThrow(() -> new IllegalStateException("Authenticated player " + playerId + " does not exist"));
	}

	private static ApiException displayNameTaken() {
		return new ApiException(HttpStatus.CONFLICT, "DISPLAY_NAME_TAKEN", "That display name is already taken.");
	}

}
