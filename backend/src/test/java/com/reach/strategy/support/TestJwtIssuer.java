package com.reach.strategy.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Stands in for Supabase Auth in tests: serves a JWKS at
 * {@code <url>/auth/v1/.well-known/jwks.json} from an in-process HTTP server and signs ES256
 * access tokens shaped like Supabase's. The application under test verifies them with its real
 * production decoder — nothing in the security chain is mocked or bypassed.
 */
public final class TestJwtIssuer {

	public static final String FRONTEND_ORIGIN = "http://localhost:5173";

	private static final TestJwtIssuer INSTANCE = new TestJwtIssuer();

	private final ECKey signingKey;
	private final ECKey foreignKey;
	private final String baseUrl;

	private TestJwtIssuer() {
		try {
			signingKey = new ECKeyGenerator(Curve.P_256).keyID("test-key").generate();
			foreignKey = new ECKeyGenerator(Curve.P_256).keyID("test-key").generate();
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
			server.createContext("/auth/v1/.well-known/jwks.json", exchange -> {
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, jwks.length);
				try (OutputStream body = exchange.getResponseBody()) {
					body.write(jwks);
				}
			});
			server.setExecutor(null);
			server.start();
			baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		}
		catch (JOSEException | IOException ex) {
			throw new IllegalStateException("Could not start test JWT issuer", ex);
		}
	}

	public static TestJwtIssuer get() {
		return INSTANCE;
	}

	/** The value for {@code strategy.supabase.url}. */
	public String projectUrl() {
		return baseUrl;
	}

	public String issuer() {
		return baseUrl + "/auth/v1";
	}

	public Token tokenFor(UUID supabaseUserId) {
		return new Token(supabaseUserId.toString());
	}

	public Token tokenForSubject(String subject) {
		return new Token(subject);
	}

	/** A valid token for a brand-new random user. */
	public String newUserToken() {
		return tokenFor(UUID.randomUUID()).sign();
	}

	/** Builder for Supabase-shaped tokens; defaults are valid, setters break one aspect. */
	public final class Token {

		private final Map<String, Object> claims = new LinkedHashMap<>();
		private Instant expiresAt = Instant.now().plus(Duration.ofHours(1));

		private Token(String subject) {
			claims.put("sub", subject);
			claims.put("iss", issuer());
			claims.put("aud", "authenticated");
			claims.put("role", "authenticated");
			claims.put("is_anonymous", false);
		}

		public Token displayName(String name) {
			claims.put("user_metadata", Map.of("display_name", name));
			return this;
		}

		public Token claim(String name, Object value) {
			claims.put(name, value);
			return this;
		}

		public Token without(String name) {
			claims.remove(name);
			return this;
		}

		public Token expiresAt(Instant instant) {
			expiresAt = instant;
			return this;
		}

		public String sign() {
			return signEs256(signingKey);
		}

		/** Same key id and algorithm, but a key that is not in the JWKS. */
		public String signWithForeignKey() {
			return signEs256(foreignKey);
		}

		/** Symmetric HS256, as with Supabase's legacy shared secret; must be rejected. */
		public String signHs256() {
			byte[] secret = new byte[32];
			new SecureRandom().nextBytes(secret);
			try {
				SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet());
				jwt.sign(new MACSigner(secret));
				return jwt.serialize();
			}
			catch (JOSEException ex) {
				throw new IllegalStateException(ex);
			}
		}

		/** {@code alg: none}; must be rejected. */
		public String unsigned() {
			return new PlainJWT(claimsSet()).serialize();
		}

		private String signEs256(ECKey key) {
			try {
				SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(),
						claimsSet());
				jwt.sign(new ECDSASigner(key));
				return jwt.serialize();
			}
			catch (JOSEException ex) {
				throw new IllegalStateException(ex);
			}
		}

		private JWTClaimsSet claimsSet() {
			JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
			claims.forEach(builder::claim);
			Instant now = Instant.now();
			builder.issueTime(Date.from(now.minusSeconds(5)));
			if (expiresAt != null) {
				builder.expirationTime(Date.from(expiresAt));
			}
			return builder.build();
		}

	}

	/** Points the application at this issuer and allows the local frontend origin. */
	public static class PropertiesInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

		@Override
		public void initialize(ConfigurableApplicationContext context) {
			TestPropertyValues.of("strategy.supabase.url=" + get().projectUrl(), "strategy.cors.allowed-origins=" + FRONTEND_ORIGIN)
				.applyTo(context);
		}

	}

}
