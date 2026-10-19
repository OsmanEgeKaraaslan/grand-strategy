package com.reach.strategy.platform.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id: reused from a well-formed {@code X-Request-Id} header,
 * otherwise generated. Exposed in the MDC for logs, as a request attribute for error responses,
 * and echoed in the response header.
 * <p>
 * Runs before the Spring Security filter chain so that 401/403 responses carry the id too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";
	public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

	// Client-supplied ids end up in logs, so only short, harmless values are accepted.
	private static final Pattern ACCEPTED_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = resolveRequestId(request.getHeader(HEADER));
		request.setAttribute(ATTRIBUTE, requestId);
		response.setHeader(HEADER, requestId);
		MDC.put(MDC_KEY, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	static String resolveRequestId(String headerValue) {
		if (headerValue != null && ACCEPTED_ID.matcher(headerValue).matches()) {
			return headerValue;
		}
		return UUID.randomUUID().toString();
	}

}
