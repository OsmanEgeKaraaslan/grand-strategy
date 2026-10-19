package com.reach.strategy.platform.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.reach.strategy.platform.web.ApiProblems;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Renders authentication (401) and authorization (403) failures raised inside the security
 * filter chain in the same problem format as {@code ApiExceptionHandler}.
 */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final JsonMapper jsonMapper;

	public ProblemSecurityHandlers(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		// RFC 6750: tell clients that bearer tokens are the (only) accepted scheme.
		response.setHeader("WWW-Authenticate", "Bearer");
		write(response, ApiProblems.create(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Unauthorized",
				"Authentication is required to access this resource.", request));
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		write(response, ApiProblems.create(HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden",
				"You are not allowed to perform this action.", request));
	}

	private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
		response.setStatus(problem.getStatus());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		jsonMapper.writeValue(response.getOutputStream(), problem);
	}

}
