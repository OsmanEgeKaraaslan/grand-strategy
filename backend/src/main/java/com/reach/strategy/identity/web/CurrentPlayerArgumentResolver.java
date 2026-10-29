package com.reach.strategy.identity.web;

import java.util.Map;
import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.application.AuthenticatedUser;
import com.reach.strategy.identity.application.PlayerService;
import com.reach.strategy.platform.web.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies {@link CurrentPlayer} controller parameters from the verified JWT in the security
 * context — the only source of player identity. Client-sent ids are never consulted.
 */
@Component
class CurrentPlayerArgumentResolver implements HandlerMethodArgumentResolver {

	private static final String ACCOUNT_PATH = "/api/v1/me";

	private final PlayerService playerService;

	CurrentPlayerArgumentResolver(PlayerService playerService) {
		this.playerService = playerService;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return CurrentPlayer.class.equals(parameter.getParameterType());
	}

	@Override
	public CurrentPlayer resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
			throw new AuthenticationCredentialsNotFoundException("No verified access token");
		}
		CurrentPlayer player = playerService.resolve(new AuthenticatedUser(UUID.fromString(jwt.getSubject()),
				requestedDisplayName(jwt), signUpConsent(jwt)), isAccountEndpoint(webRequest));
		if (player != null) {
			// Correlates log lines with the account (the player id, never email); RequestIdFilter clears it.
			MDC.put(RequestIdFilter.USER_MDC_KEY, player.id().toString());
		}
		return player;
	}

	/**
	 * The account's own endpoints stay usable without accepting the current legal documents: reading
	 * the account, accepting them, and deleting the account instead.
	 */
	private static boolean isAccountEndpoint(NativeWebRequest webRequest) {
		HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
		if (request == null) {
			return false;
		}
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return path.equals(ACCOUNT_PATH) || path.startsWith(ACCOUNT_PATH + "/");
	}

	private static AuthenticatedUser.SignUpConsent signUpConsent(Jwt jwt) {
		Map<String, Object> metadata = jwt.getClaimAsMap("user_metadata");
		if (metadata != null && metadata.get("accepted_terms_version") instanceof String terms
				&& metadata.get("accepted_privacy_version") instanceof String privacy) {
			return new AuthenticatedUser.SignUpConsent(terms, privacy);
		}
		return null;
	}

	private static String requestedDisplayName(Jwt jwt) {
		Map<String, Object> metadata = jwt.getClaimAsMap("user_metadata");
		return metadata != null && metadata.get("display_name") instanceof String name ? name : null;
	}

}
