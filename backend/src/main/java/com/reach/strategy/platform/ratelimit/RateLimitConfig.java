package com.reach.strategy.platform.ratelimit;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig implements WebMvcConfigurer {

	private final RateLimiter limiter;

	RateLimitConfig(RateLimitProperties properties, ObjectProvider<Clock> clock) {
		this.limiter = new RateLimiter(properties, clock.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	RateLimiter rateLimiter() {
		return limiter;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new RateLimitInterceptor(limiter)).addPathPatterns("/api/**");
	}

	/** A typo in a policy name would silently disable a limit, so it stops the application instead. */
	@Bean
	SmartInitializingSingleton rateLimitPoliciesExist(ApplicationContext context, RateLimitProperties properties) {
		return () -> {
			if (!limiter.knows(RateLimitProperties.GLOBAL_POLICY)) {
				throw new IllegalStateException("strategy.rate-limits.policies.api is required");
			}
			context.getBeansOfType(RequestMappingHandlerMapping.class).values().forEach(mapping ->
				mapping.getHandlerMethods().values().stream().map(HandlerMethod::getMethod).forEach(method -> {
					for (RateLimited limit : new RateLimited[] { method.getAnnotation(RateLimited.class),
							method.getDeclaringClass().getAnnotation(RateLimited.class) }) {
						if (limit != null && !limiter.knows(limit.value())) {
							throw new IllegalStateException("Unknown rate-limit policy '" + limit.value() + "' on " + method);
						}
					}
				}));
		};
	}

}
