package com.reach.strategy.platform.security;

import java.net.URI;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Supabase Auth settings used to verify access tokens. Only public information is needed
 * (project URL, expected audience); the backend never holds a Supabase secret or service key.
 *
 * @param url project URL, e.g. {@code https://<project-ref>.supabase.co}
 * @param audience required {@code aud} claim ({@code authenticated} for signed-in users)
 * @param jwsAlgorithms accepted signing algorithms; asymmetric only (Supabase signing keys)
 */
@Validated
@ConfigurationProperties("strategy.supabase")
public record SupabaseAuthProperties(@NotNull URI url, @NotBlank String audience, @NotEmpty List<String> jwsAlgorithms) {

	/** Supabase Auth's token issuer and JWKS location are both derived from the project URL. */
	public String issuer() {
		return trimTrailingSlash(url.toString()) + "/auth/v1";
	}

	public String jwkSetUri() {
		return issuer() + "/.well-known/jwks.json";
	}

	private static String trimTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

}
