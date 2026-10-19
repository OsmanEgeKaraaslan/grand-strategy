package com.reach.strategy.identity.application;

import java.util.UUID;

/**
 * Facts taken from a verified Supabase access token.
 *
 * @param supabaseUserId the token's {@code sub}
 * @param requestedDisplayName optional name chosen at sign-up ({@code user_metadata.display_name});
 * user-controlled, so it is only a suggestion and is validated before use
 */
public record AuthenticatedUser(UUID supabaseUserId, String requestedDisplayName) {
}
