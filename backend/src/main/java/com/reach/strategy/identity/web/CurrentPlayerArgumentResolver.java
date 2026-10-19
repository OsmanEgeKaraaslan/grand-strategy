package com.reach.strategy.identity.web;

import java.util.Map;
import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.identity.application.AuthenticatedUser;
import com.reach.strategy.identity.application.PlayerService;

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
		return playerService.resolve(new AuthenticatedUser(UUID.fromString(jwt.getSubject()), requestedDisplayName(jwt)));
	}

	private static String requestedDisplayName(Jwt jwt) {
		Map<String, Object> metadata = jwt.getClaimAsMap("user_metadata");
		return metadata != null && metadata.get("display_name") instanceof String name ? name : null;
	}

}
