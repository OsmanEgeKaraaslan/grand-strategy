package com.reach.strategy.identity.application;

import java.util.UUID;

/**
 * Facts taken from a verified Supabase access token.
 *
 * @param supabaseUserId the token's {@code sub}
 * @param requestedDisplayName optional name chosen at sign-up ({@code user_metadata.display_name});
 * user-controlled, so it is only a suggestion and is validated before use
 * @param signUpConsent the legal document versions the person agreed to on the sign-up form
 * ({@code user_metadata.accepted_terms_version} / {@code accepted_privacy_version}); recorded only when
 * the account is created and only if they are the current versions; null when absent
 */
public record AuthenticatedUser(UUID supabaseUserId, String requestedDisplayName, SignUpConsent signUpConsent) {

	public record SignUpConsent(String termsVersion, String privacyVersion) {
	}

	public AuthenticatedUser(UUID supabaseUserId, String requestedDisplayName) {
		this(supabaseUserId, requestedDisplayName, null);
	}

}
