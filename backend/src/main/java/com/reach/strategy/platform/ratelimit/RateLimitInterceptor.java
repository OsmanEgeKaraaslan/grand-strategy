package com.reach.strategy.platform.ratelimit;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.reach.strategy.platform.web.ApiException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the global {@code api} budget and an endpoint's {@link RateLimited} policy before the
 * controller runs. Budgets are counted per account (the verified token's subject), so neither a shared
 * address nor a forged forwarding header can exhaust or dodge another player's budget. Requests
 * without an account never get here: the security chain answers them with 401 first.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

	private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

	private final RateLimiter limiter;

	public RateLimitInterceptor(RateLimiter limiter) {
		this.limiter = limiter;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		String account = account();
		if (account == null || !(handler instanceof HandlerMethod method)) {
			return true;
		}
		check(RateLimitProperties.GLOBAL_POLICY, account, response);
		String policy = policyOf(method, request.getMethod());
		if (policy != null) {
			check(policy, account, response);
		}
		return true;
	}

	static String policyOf(HandlerMethod method, String httpMethod) {
		RateLimited onMethod = method.getMethodAnnotation(RateLimited.class);
		if (onMethod != null) {
			return onMethod.value();
		}
		RateLimited onClass = method.getBeanType().getAnnotation(RateLimited.class);
		return onClass != null && WRITES.contains(httpMethod) ? onClass.value() : null;
	}

	private void check(String policy, String account, HttpServletResponse response) {
		long retryAfter = limiter.tryAcquire(policy, account);
		if (retryAfter > 0) {
			response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
					"Too many requests. Please wait " + retryAfter + " s and try again.");
		}
	}

	private static String account() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null && authentication.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : null;
	}

}
