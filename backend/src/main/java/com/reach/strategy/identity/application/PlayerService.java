package com.reach.strategy.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.api.PlayerDirectory;
import com.reach.strategy.identity.api.PublicProfiles;
import com.reach.strategy.identity.domain.DisplayName;
import com.reach.strategy.identity.domain.Emblem;
import com.reach.strategy.identity.domain.Player;
import com.reach.strategy.identity.domain.PlayerRepository;
import com.reach.strategy.identity.domain.Username;
import com.reach.strategy.platform.web.ApiException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlayerService implements PlayerDirectory, PublicProfiles {

	private static final int GENERATED_NAME_ATTEMPTS = 3;

	private final PlayerRepository players;
	private final LegalTerms legal;
	private final Clock clock;

	PlayerService(PlayerRepository players, LegalTerms legal, Clock clock) {
		this.players = players;
		this.legal = legal;
		this.clock = clock;
	}

	/**
	 * Returns the player for a verified Supabase user, creating it on first login. The requested
	 * name (from sign-up metadata) is used when valid and free, otherwise a generated one; the
	 * handle is derived from it the same way. The email address is never used.
	 */
	@Transactional
	public CurrentPlayer resolve(AuthenticatedUser user) {
		return resolve(user, true);
	}

	/**
	 * Like {@link #resolve(AuthenticatedUser)}, and refuses a deleted account (its Supabase user may
	 * still hold a valid token until the operator removes it) and — when acceptance is required and the
	 * request is not one of the account's own endpoints — an account that has not accepted the current
	 * legal documents.
	 */
	@Transactional
	public CurrentPlayer resolve(AuthenticatedUser user, boolean termsExempt) {
		Player player = players.findBySupabaseUserId(user.supabaseUserId()).orElseGet(() -> provision(user));
		if (player.deleted()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DELETED", "This account has been deleted.");
		}
		if (!termsExempt && legal.requireAcceptance() && !legal.isCurrent(player.termsVersion(), player.privacyVersion())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "TERMS_NOT_ACCEPTED",
					"Please accept the current Terms of Service and Privacy Policy to continue.");
		}
		return new CurrentPlayer(player.id(), player.displayName());
	}

	@Transactional(readOnly = true)
	public PlayerProfile profile(UUID playerId) {
		return PlayerProfile.of(load(playerId), legal);
	}

	@Transactional
	public PlayerProfile rename(UUID playerId, String requestedName) {
		return update(playerId, new ProfileChanges(requestedName, null, null, null));
	}

	/** Fields left null are unchanged. Only the account's owner can call this (the id comes from the token). */
	public record ProfileChanges(String displayName, String username, String emblem, Boolean matchHistoryPublic) {
	}

	/**
	 * Changes the caller's own profile. Uniqueness of the display name and the handle is checked up
	 * front for a friendly error and enforced by the unique indexes, so two players racing for the
	 * same handle cannot both get it.
	 */
	@Transactional
	public PlayerProfile update(UUID playerId, ProfileChanges changes) {
		Instant now = clock.instant();
		Player player = load(playerId);
		if (changes.displayName() != null) {
			DisplayName name = DisplayName.parse(changes.displayName())
				.orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DISPLAY_NAME",
						"Display names have 3–24 letters, digits, spaces, '_' or '-'."));
			if (players.isDisplayNameTakenByOther(name.value(), playerId)) {
				throw displayNameTaken();
			}
			player.rename(name, now);
		}
		if (changes.username() != null) {
			Optional<Username.Problem> problem = Username.check(changes.username());
			if (problem.isPresent()) {
				throw problem.get() == Username.Problem.RESERVED
						? new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "USERNAME_RESERVED",
								"That username is reserved; please choose another.")
						: new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_USERNAME",
								"Usernames have 3–20 lowercase letters, digits or '_' and start with a letter.");
			}
			Username username = Username.parse(changes.username()).orElseThrow();
			if (!username.value().equals(player.username())) {
				if (players.isUsernameTakenByOther(username.value(), playerId)) {
					throw usernameTaken();
				}
				player.chooseUsername(username, now);
			}
		}
		if (changes.emblem() != null) {
			player.chooseEmblem(Emblem.parse(changes.emblem())
				.orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_EMBLEM",
						"Choose one of the offered emblems.")), now);
		}
		if (changes.matchHistoryPublic() != null) {
			player.showMatchHistory(changes.matchHistoryPublic(), now);
		}
		try {
			players.saveAndFlush(player);
		}
		catch (DataIntegrityViolationException raceWithAnotherPlayer) {
			// Whichever unique index fired, the message must name the field that was contested.
			throw changes.username() != null ? usernameTaken() : displayNameTaken();
		}
		return PlayerProfile.of(player, legal);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> displayNames(Collection<UUID> playerIds) {
		return players.findByIdIn(playerIds).stream().collect(Collectors.toMap(Player::id, Player::displayName));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PublicPlayer> byUsername(String username) {
		if (username == null || username.isBlank()) {
			return Optional.empty();
		}
		return players.findByUsername(Username.normalize(username)).filter(p -> !p.deleted()).map(PlayerService::publicView);
	}

	@Override
	@Transactional(readOnly = true)
	public List<PublicPlayer> search(String query, int limit) {
		String normalized = query == null ? "" : Username.normalize(query);
		if (!normalized.matches("[a-z0-9_]{1,20}")) {
			return List.of();
		}
		List<PublicPlayer> found = new ArrayList<>();
		byUsername(normalized).ifPresent(found::add);
		if (normalized.length() >= Username.MIN_LENGTH) {
			players.findByUsernamePrefix(normalized.replace("_", "\\_"), Limit.of(limit + 1))
				.stream()
				.filter(p -> !p.username().equals(normalized) && !p.deleted())
				.map(PlayerService::publicView)
				.forEach(found::add);
		}
		return found.stream().limit(limit).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, PublicPlayer> byIds(Collection<UUID> playerIds) {
		return players.findByIdIn(playerIds)
			.stream()
			.filter(p -> p.username() != null && !p.deleted())
			.collect(Collectors.toMap(Player::id, PlayerService::publicView));
	}

	private static PublicPlayer publicView(Player p) {
		return new PublicPlayer(p.id(), p.username(), p.displayName(), p.emblem(), p.createdAt(), p.matchHistoryPublic());
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
		Player player = players.findBySupabaseUserId(user.supabaseUserId())
			.orElseThrow(() -> new IllegalStateException("Could not provision player account"));
		recordSignUpConsent(player, user.signUpConsent(), now);
		return player;
	}

	/**
	 * The sign-up form asks for consent before the account exists here, so it travels in the user's
	 * Supabase metadata and is recorded on first login — only for the versions that are current now.
	 */
	private void recordSignUpConsent(Player player, AuthenticatedUser.SignUpConsent consent, Instant now) {
		if (consent == null || player.termsVersion() != null
				|| !legal.isCurrent(consent.termsVersion(), consent.privacyVersion())) {
			return;
		}
		player.acceptTerms(consent.termsVersion(), consent.privacyVersion(), now);
		players.saveAndFlush(player);
		players.recordAcceptance(player.id(), consent.termsVersion(), consent.privacyVersion(), now);
	}

	/** Tries the handle suggested by the name first, then generated handles, until one is free. */
	private int insert(AuthenticatedUser user, DisplayName name, Instant now) {
		Optional<Username> suggested = Username.suggestedFrom(name.value());
		if (suggested.isPresent()
				&& players.insertIfAbsent(UUID.randomUUID(), user.supabaseUserId(), name.value(), suggested.get().value(), now) == 1) {
			return 1;
		}
		for (int attempt = 0; attempt < GENERATED_NAME_ATTEMPTS; attempt++) {
			if (players.findBySupabaseUserId(user.supabaseUserId()).isPresent() || players.isDisplayNameTakenByOther(name.value(), new UUID(0, 0))) {
				return 0;
			}
			if (players.insertIfAbsent(UUID.randomUUID(), user.supabaseUserId(), name.value(), Username.generated().value(), now) == 1) {
				return 1;
			}
		}
		return 0;
	}

	private Player load(UUID playerId) {
		return players.findById(playerId)
			.orElseThrow(() -> new IllegalStateException("Authenticated player " + playerId + " does not exist"));
	}

	private static ApiException displayNameTaken() {
		return new ApiException(HttpStatus.CONFLICT, "DISPLAY_NAME_TAKEN", "That display name is already taken.");
	}

	private static ApiException usernameTaken() {
		return new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "That username is already taken.");
	}

}
