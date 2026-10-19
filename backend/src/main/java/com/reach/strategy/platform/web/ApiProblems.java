package com.reach.strategy.platform.web;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds the API's RFC 9457 error bodies. Every problem carries a stable machine-readable
 * {@code code} and the {@code requestId} used in logs (docs/API_DESIGN.md §3).
 */
public final class ApiProblems {

	public static final String CODE = "code";
	public static final String REQUEST_ID = "requestId";

	private ApiProblems() {
	}

	public static ProblemDetail create(HttpStatusCode status, String code, String title, String detail,
			HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty(CODE, code);
		decorate(problem, request);
		return problem;
	}

	/** Adds the request id to a problem created elsewhere (e.g. by Spring MVC's own handlers). */
	public static void decorate(ProblemDetail problem, HttpServletRequest request) {
		Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
		if (requestId != null) {
			problem.setProperty(REQUEST_ID, requestId);
		}
	}

}
