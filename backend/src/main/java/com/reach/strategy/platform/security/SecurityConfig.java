package com.reach.strategy.platform.security;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * API security policy: default deny.
 * <ul>
 * <li>Only health endpoints are public.</li>
 * <li>Every other path — including APIs that do not exist yet — requires a valid Supabase
 * access token ({@link SupabaseJwtDecoders}). Missing or invalid tokens get 401.</li>
 * <li>Stateless bearer-token API: no sessions, no form login, no HTTP Basic, no CSRF tokens (CSRF
 * protection is only needed for cookie-based authentication, which this API never uses).</li>
 * <li>Authorization for game resources (membership, ownership) is enforced in the application
 * services, never by trusting client-supplied ids.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties({ SupabaseAuthProperties.class, CorsProperties.class })
public class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, ProblemSecurityHandlers problemHandlers) throws Exception {
		http.authorizeHttpRequests(authorize -> authorize
			.requestMatchers(HttpMethod.GET, "/api/v1/health", "/actuator/health", "/actuator/health/**")
			.permitAll()
			.anyRequest()
			.authenticated())
			.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(problemHandlers)
				.accessDeniedHandler(problemHandlers))
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(problemHandlers)
				.accessDeniedHandler(problemHandlers));
		return http.build();
	}

	@Bean
	JwtDecoder supabaseJwtDecoder(SupabaseAuthProperties properties) {
		return SupabaseJwtDecoders.create(properties);
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.allowedOrigins());
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "If-None-Match", "X-Request-Id"));
		cors.setExposedHeaders(List.of("ETag", "Location", "X-Request-Id"));
		cors.setAllowCredentials(false);
		cors.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);
		return source;
	}

	/**
	 * Explicitly no local users. Without this bean Spring Boot would create a default user with a
	 * generated password; this application never authenticates users itself (Supabase does).
	 */
	@Bean
	UserDetailsService noLocalUsers() {
		return new InMemoryUserDetailsManager();
	}

}
