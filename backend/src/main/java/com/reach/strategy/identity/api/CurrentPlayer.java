package com.reach.strategy.identity.api;

import java.util.UUID;

/**
 * The player behind the current request, derived only from the verified access token. Declare it
 * as a controller method parameter to receive it; the account is provisioned on first use.
 * Never construct it from client input.
 */
public record CurrentPlayer(UUID id, String displayName) {
}
