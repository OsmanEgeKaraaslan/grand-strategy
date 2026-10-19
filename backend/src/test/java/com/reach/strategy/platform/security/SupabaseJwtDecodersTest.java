package com.reach.strategy.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.reach.strategy.support.TestJwtIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** The production decoder against tokens from {@link TestJwtIssuer}'s JWKS. */
class SupabaseJwtDecodersTest {

	static final TestJwtIssuer ISSUER = TestJwtIssuer.get();

	final JwtDecoder decoder = SupabaseJwtDecoders.create(new SupabaseAuthProperties(
			URI.create(ISSUER.projectUrl()), "authenticated", List.of("ES256", "RS256")));

	@Test
	void acceptsAValidSupabaseAccessToken() {
		UUID user = UUID.randomUUID();
		Jwt jwt = decoder.decode(ISSUER.tokenFor(user).sign());
		assertThat(jwt.getSubject()).isEqualTo(user.toString());
		assertThat(jwt.getIssuer()).hasToString(ISSUER.issuer());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rejectedTokens")
	void rejects(String description, String token) {
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	static Stream<Arguments> rejectedTokens() {
		UUID user = UUID.randomUUID();
		return Stream.of(
				Arguments.of("expired", ISSUER.tokenFor(user).expiresAt(Instant.now().minusSeconds(3600)).sign()),
				Arguments.of("without expiry", ISSUER.tokenFor(user).expiresAt(null).sign()),
				Arguments.of("wrong issuer", ISSUER.tokenFor(user).claim("iss", "https://other.supabase.co/auth/v1").sign()),
				Arguments.of("wrong audience", ISSUER.tokenFor(user).claim("aud", "service").sign()),
				Arguments.of("missing audience", ISSUER.tokenFor(user).without("aud").sign()),
				Arguments.of("anon role", ISSUER.tokenFor(user).claim("role", "anon").sign()),
				Arguments.of("service_role", ISSUER.tokenFor(user).claim("role", "service_role").sign()),
				Arguments.of("anonymous user", ISSUER.tokenFor(user).claim("is_anonymous", true).sign()),
				Arguments.of("non-UUID subject", ISSUER.tokenForSubject("1-1-1-1-1").sign()),
				Arguments.of("signed with an unknown key", ISSUER.tokenFor(user).signWithForeignKey()),
				Arguments.of("HS256 (symmetric)", ISSUER.tokenFor(user).signHs256()),
				Arguments.of("alg none", ISSUER.tokenFor(user).unsigned()),
				Arguments.of("tampered payload", tamper(ISSUER.tokenFor(user).sign())));
	}

	private static String tamper(String token) {
		String[] parts = token.split("\\.");
		String otherPayload = ISSUER.tokenFor(UUID.randomUUID()).sign().split("\\.")[1];
		return parts[0] + "." + otherPayload + "." + parts[2];
	}

}
