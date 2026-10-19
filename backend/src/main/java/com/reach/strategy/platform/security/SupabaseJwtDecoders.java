package com.reach.strategy.platform.security;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the decoder that verifies Supabase access tokens (docs/ARCHITECTURE.md §4.1):
 * signature against the project's JWKS (asymmetric keys only), issuer, expiry/not-before,
 * audience, the {@code authenticated} role, a UUID subject, and non-anonymous users.
 */
public final class SupabaseJwtDecoders {

	private SupabaseJwtDecoders() {
	}

	public static JwtDecoder create(SupabaseAuthProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
			.jwsAlgorithms(algorithms -> properties.jwsAlgorithms().forEach(name -> algorithms.add(asymmetric(name))))
			.build();
		decoder.setJwtValidator(validator(properties));
		return decoder;
	}

	static OAuth2TokenValidator<Jwt> validator(SupabaseAuthProperties properties) {
		return new DelegatingOAuth2TokenValidator<>(List.of(new JwtTimestampValidator(),
				new JwtIssuerValidator(properties.issuer()),
				require("exp", jwt -> jwt.getExpiresAt() != null),
				require("aud", jwt -> jwt.getAudience() != null && jwt.getAudience().contains(properties.audience())),
				require("role", jwt -> "authenticated".equals(jwt.getClaimAsString("role"))),
				require("sub", jwt -> isUuid(jwt.getSubject())),
				// Anonymous Supabase sessions are not player accounts.
				require("is_anonymous", jwt -> !Boolean.TRUE.equals(jwt.getClaimAsBoolean("is_anonymous")))));
	}

	private static SignatureAlgorithm asymmetric(String name) {
		SignatureAlgorithm algorithm = SignatureAlgorithm.from(name);
		if (algorithm == null) {
			throw new IllegalArgumentException("Unsupported JWS algorithm: " + name
					+ " (allowed: asymmetric algorithms such as ES256 or RS256)");
		}
		return algorithm;
	}

	private static OAuth2TokenValidator<Jwt> require(String claim, Predicate<Jwt> check) {
		OAuth2Error error = new OAuth2Error("invalid_token", "The '" + claim + "' claim is missing or not accepted", null);
		return jwt -> check.test(jwt) ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(error);
	}

	private static boolean isUuid(String value) {
		if (value == null) {
			return false;
		}
		try {
			// Reject lenient forms such as "1-1-1-1-1" that UUID.fromString would accept.
			return UUID.fromString(value).toString().equals(value.toLowerCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

}
