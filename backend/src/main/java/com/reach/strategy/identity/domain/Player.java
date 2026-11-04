package com.reach.strategy.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A player account in this game, linked one-to-one to a Supabase Auth user. Holds no credentials:
 * authentication is entirely Supabase's responsibility. The public profile (Phase 12A) is the
 * handle, display name, emblem and creation date; nothing else of the account is ever public.
 */
@Entity
@Table(name = "player")
public class Player {

	@Id
	private UUID id;

	@Column(name = "supabase_user_id", updatable = false)
	private UUID supabaseUserId;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	/** Null only for computer accounts. */
	@Column(name = "username")
	private String username;

	@Column(name = "username_chosen", nullable = false)
	private boolean usernameChosen;

	@Column(name = "emblem", nullable = false)
	private String emblem;

	@Column(name = "match_history_public", nullable = false)
	private boolean matchHistoryPublic;

	@Column(name = "terms_version")
	private String termsVersion;

	@Column(name = "privacy_version")
	private String privacyVersion;

	@Column(name = "terms_accepted_at")
	private Instant termsAcceptedAt;

	/** Set once the owner deleted the account; the anonymized row stays for match integrity. */
	@Column(name = "deleted_at")
	private Instant deletedAt;

	/** Set while an administrator has suspended the account (V24); the API refuses it. */
	@Column(name = "banned_at")
	private Instant bannedAt;

	@Column(name = "ban_reason")
	private String banReason;

	/** HUMAN or COMPUTER (V15). */
	@Column(name = "kind", nullable = false, insertable = false, updatable = false)
	private String kind;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Player() {
	}

	public UUID id() {
		return id;
	}

	public UUID supabaseUserId() {
		return supabaseUserId;
	}

	public String displayName() {
		return displayName;
	}

	public String username() {
		return username;
	}

	public boolean usernameChosen() {
		return usernameChosen;
	}

	public String emblem() {
		return emblem;
	}

	public boolean matchHistoryPublic() {
		return matchHistoryPublic;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	public String termsVersion() {
		return termsVersion;
	}

	public String privacyVersion() {
		return privacyVersion;
	}

	public Instant termsAcceptedAt() {
		return termsAcceptedAt;
	}

	public Instant deletedAt() {
		return deletedAt;
	}

	public boolean deleted() {
		return deletedAt != null;
	}

	public boolean banned() {
		return bannedAt != null;
	}

	public Instant bannedAt() {
		return bannedAt;
	}

	public String banReason() {
		return banReason;
	}

	public boolean computer() {
		return "COMPUTER".equals(kind);
	}

	/** Suspends the account; banning again keeps the first date and replaces the reason. */
	public void ban(String reason, Instant now) {
		if (bannedAt == null) {
			bannedAt = now;
		}
		banReason = reason;
		updatedAt = now;
	}

	public void unban(Instant now) {
		bannedAt = null;
		banReason = null;
		updatedAt = now;
	}

	/**
	 * Replaces an offensive name and handle with generated placeholders; the player chooses a new
	 * handle afterwards, as after first login.
	 */
	public void resetName(DisplayName placeholderName, Username placeholderUsername, Instant now) {
		this.displayName = placeholderName.value();
		this.username = placeholderUsername.value();
		this.usernameChosen = false;
		this.updatedAt = now;
	}

	public void acceptTerms(String termsVersion, String privacyVersion, Instant now) {
		this.termsVersion = termsVersion;
		this.privacyVersion = privacyVersion;
		this.termsAcceptedAt = now;
		this.updatedAt = now;
	}

	/**
	 * Removes everything that identifies the person (docs/DATA_PRIVACY_INVENTORY.md §4): name, handle and
	 * emblem are replaced by placeholders that no player can choose, and the match history is hidden. The
	 * Supabase user id stays so a still-valid token of the deleted user is refused instead of creating a
	 * fresh account; the operator removes the Supabase Auth user itself.
	 */
	public void anonymize(String placeholderName, String placeholderUsername, Instant now) {
		this.displayName = placeholderName;
		this.username = placeholderUsername;
		this.usernameChosen = false;
		this.emblem = Emblem.DEFAULT.key();
		this.matchHistoryPublic = false;
		this.deletedAt = now;
		this.updatedAt = now;
	}

	public void rename(DisplayName newName, Instant now) {
		this.displayName = newName.value();
		this.updatedAt = now;
	}

	/** A handle the player picked; the unique index rejects one taken in the meantime. */
	public void chooseUsername(Username newUsername, Instant now) {
		this.username = newUsername.value();
		this.usernameChosen = true;
		this.updatedAt = now;
	}

	public void chooseEmblem(Emblem newEmblem, Instant now) {
		this.emblem = newEmblem.key();
		this.updatedAt = now;
	}

	public void showMatchHistory(boolean visible, Instant now) {
		this.matchHistoryPublic = visible;
		this.updatedAt = now;
	}

}
