package com.reach.strategy.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.reach.strategy.identity.api.AccountDeletionParticipant;
import com.reach.strategy.identity.api.Administrators;
import com.reach.strategy.identity.domain.Player;
import com.reach.strategy.identity.domain.PlayerRepository;
import com.reach.strategy.identity.domain.Username;
import com.reach.strategy.platform.web.ApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account lifecycle of the authenticated player (Phase 15A): accepting the legal documents and
 * deleting the account. Passwords, email and sessions stay entirely with Supabase Auth.
 */
@Service
public class AccountService {

	private static final Logger log = LoggerFactory.getLogger(AccountService.class);

	private final PlayerRepository players;
	private final LegalTerms legal;
	private final List<AccountDeletionParticipant> participants;
	private final Duration reauthenticationWindow;
	private final Clock clock;
	private final Administrators admins;

	AccountService(PlayerRepository players, LegalTerms legal, List<AccountDeletionParticipant> participants,
			@Value("${strategy.account.deletion-reauthentication-window:PT10M}") Duration reauthenticationWindow,
			Clock clock, Administrators admins) {
		this.admins = admins;
		this.players = players;
		this.legal = legal;
		// A stable order, so the deleting transaction always takes locks in the same sequence.
		this.participants = participants.stream().sorted(Comparator.comparing(p -> p.getClass().getName())).toList();
		this.reauthenticationWindow = reauthenticationWindow;
		this.clock = clock;
	}

	/**
	 * Records that the player accepted exactly the versions they were shown. A stale page (the operator
	 * published a new version meanwhile) gets 409 so the player reads the new text first.
	 */
	@Transactional
	public PlayerProfile acceptTerms(UUID playerId, String termsVersion, String privacyVersion) {
		if (!legal.isCurrent(termsVersion, privacyVersion)) {
			throw new ApiException(HttpStatus.CONFLICT, "TERMS_VERSION_OUTDATED",
					"The legal documents have changed. Please review the current versions.");
		}
		Instant now = clock.instant();
		Player player = players.findByIdForUpdate(playerId).orElseThrow();
		if (!legal.isCurrent(player.termsVersion(), player.privacyVersion())) {
			player.acceptTerms(termsVersion, privacyVersion, now);
		}
		players.recordAcceptance(playerId, termsVersion, privacyVersion, now);
		return PlayerProfile.of(player, legal, admins.isAdmin(playerId));
	}

	public record DeletedAccount(Instant deletedAt) {
	}

	/**
	 * Deletes the caller's account: refused while another module reports a blocker (unfinished games),
	 * and unless the player signed in again recently, so a borrowed or stolen session alone cannot do
	 * it. The player row is anonymized rather than removed so finished matches keep their participants.
	 *
	 * @param lastSignInAt when the token's session was last authenticated with a credential (Supabase
	 * {@code amr} claim); null if unknown
	 */
	@Transactional
	public DeletedAccount deleteAccount(UUID playerId, Instant lastSignInAt) {
		Instant now = clock.instant();
		if (!reauthenticationWindow.isZero()
				&& (lastSignInAt == null || lastSignInAt.isBefore(now.minus(reauthenticationWindow)))) {
			throw new ApiException(HttpStatus.FORBIDDEN, "REAUTHENTICATION_REQUIRED",
					"Please confirm your password again before deleting your account.");
		}
		Player player = players.findByIdForUpdate(playerId).orElseThrow();
		List<String> blockers = new ArrayList<>();
		for (AccountDeletionParticipant participant : participants) {
			participant.blocker(playerId).ifPresent(blockers::add);
		}
		if (!blockers.isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_DELETION_BLOCKED", String.join(" ", blockers));
		}
		for (AccountDeletionParticipant participant : participants) {
			participant.accountDeleted(playerId, now);
		}
		String suffix = placeholderSuffix(player.id());
		player.anonymize("Deleted#" + suffix, Username.DELETED_PREFIX + suffix, now);
		players.saveAndFlush(player);
		// The operator removes the Supabase Auth user (email, password) using this id (docs/PRODUCTION_READINESS.md).
		log.info("Account deleted: player {} (Supabase user {}) awaits removal from Supabase Auth", player.id(),
				player.supabaseUserId());
		return new DeletedAccount(now);
	}

	/** 12 hex digits of the player id: unique by construction, as for migrated handles (V16). */
	private static String placeholderSuffix(UUID playerId) {
		return playerId.toString().replace("-", "").substring(0, 12);
	}

}
