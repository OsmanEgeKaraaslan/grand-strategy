package com.reach.strategy.platform.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.application.AuthenticatedUser;
import com.reach.strategy.identity.application.PlayerProfile;
import com.reach.strategy.identity.application.PlayerService;
import com.reach.strategy.support.ApiSliceTest;
import com.reach.strategy.support.TestJwtIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The security policy through the real filter chain and the real Supabase JWT decoder, with
 * tokens signed by {@link TestJwtIssuer}. Application services are mocked.
 */
@ApiSliceTest
class SecurityConfigTest {

	static final TestJwtIssuer ISSUER = TestJwtIssuer.get();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	PlayerService playerService;

	@Test
	void healthEndpointIsPublic() throws Exception {
		mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
	}

	@Test
	void anonymousRequestToAProtectedApiGets401Problem() throws Exception {
		mockMvc.perform(get("/api/v1/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.instance").value("/api/v1/me"))
			.andExpect(jsonPath("$.requestId").isNotEmpty())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
			.andExpect(header().exists("X-Request-Id"));
	}

	@Test
	void unknownApiPathIsRejectedBeforeRoutingWhenAnonymous() throws Exception {
		mockMvc.perform(get("/api/v1/games/123/secret")).andExpect(status().isUnauthorized());
	}

	@Test
	void validTokenIsAcceptedAndIdentityComesFromItsSubject() throws Exception {
		UUID supabaseUser = UUID.randomUUID();
		UUID playerId = UUID.randomUUID();
		when(playerService.resolve(any())).thenReturn(new CurrentPlayer(playerId, "Aldric"));
		when(playerService.profile(playerId))
			.thenReturn(new PlayerProfile(playerId, "aldric", "Aldric", "crown", true, true, Instant.EPOCH, Instant.EPOCH));

		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION,
				bearer(ISSUER.tokenFor(supabaseUser).displayName("Aldric").sign())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Aldric"));

		verify(playerService).resolve(new AuthenticatedUser(supabaseUser, "Aldric"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("com.reach.strategy.platform.security.SupabaseJwtDecodersTest#rejectedTokens")
	void invalidTokensGet401(String description, String token) throws Exception {
		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void garbageBearerValueGets401() throws Exception {
		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void onlyGetIsPublicOnTheHealthPath() throws Exception {
		mockMvc.perform(post("/api/v1/health")).andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/v1/health")).andExpect(status().isUnauthorized());
	}

	@Test
	void noOtherLoginMechanismIsOffered() throws Exception {
		mockMvc.perform(get("/login")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNzd29yZA=="))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
	}

	@Test
	void noSessionIsCreated() throws Exception {
		mockMvc.perform(get("/api/v1/health")).andExpect(header().doesNotExist("Set-Cookie"));
		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(ISSUER.newUserToken())))
			.andExpect(header().doesNotExist("Set-Cookie"));
	}

	@Test
	void corsPreflightIsAllowedForTheConfiguredFrontendOrigin() throws Exception {
		mockMvc.perform(options("/api/v1/games").header(HttpHeaders.ORIGIN, TestJwtIssuer.FRONTEND_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, TestJwtIssuer.FRONTEND_ORIGIN))
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
	}

	@Test
	void corsPreflightIsRejectedForOtherOrigins() throws Exception {
		mockMvc.perform(options("/api/v1/games").header(HttpHeaders.ORIGIN, "https://evil.example")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	static String bearer(String token) {
		return "Bearer " + token;
	}

}
