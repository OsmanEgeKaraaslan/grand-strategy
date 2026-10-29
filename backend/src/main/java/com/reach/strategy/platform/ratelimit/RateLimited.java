package com.reach.strategy.platform.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Puts a controller endpoint under a named rate-limit policy ({@code strategy.rate-limits.policies}),
 * counted per authenticated account. On a method it applies to that endpoint. On a controller class it
 * applies to the class's state-changing endpoints (POST, PUT, PATCH, DELETE); reads stay under the
 * global {@code api} policy only. A method annotation replaces the class one.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface RateLimited {

	/** The policy name. */
	String value();

}
