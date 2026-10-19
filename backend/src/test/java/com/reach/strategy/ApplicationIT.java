package com.reach.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boots the whole application against PostgreSQL 17 (Testcontainers) and checks the Phase 1
 * foundation end to end: database connectivity, Flyway, the public health endpoints and the
 * default-deny security policy.
 */
@PostgresIntegrationTest
class ApplicationIT {

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Flyway flyway;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ApplicationContext context;

	@Test
	void applicationContextStarts() {
		assertThat(context.getBean(StrategyBackendApplication.class)).isNotNull();
	}

	@Test
	void connectsToPostgres() throws Exception {
		try (var connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
			assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
		}
		assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
	}

	@Test
	void appliesAllFlywayMigrationsToTheGameSchema() {
		MigrationInfo[] applied = flyway.info().applied();
		assertThat(applied).isNotEmpty().allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
		assertThat(flyway.info().pending()).isEmpty();
		// Flyway also records an unversioned "schema creation" row; only versioned migrations matter here.
		assertThat(applied).filteredOn(info -> info.getVersion() != null)
			.extracting(info -> info.getVersion().getVersion())
			.startsWith("1", "2");

		assertThat(jdbc.queryForObject("SELECT obj_description('game'::regnamespace, 'pg_namespace')", String.class))
			.isEqualTo("Authoritative game state. Accessed only by the Spring backend.");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM information_schema.tables WHERE table_schema = 'game' AND table_name = 'flyway_schema_history'",
				Integer.class))
			.isEqualTo(1);
	}

	@Test
	void publicHealthEndpointReportsUp() throws Exception {
		mockMvc.perform(get("/api/v1/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.service").value("strategy-backend"));
	}

	@Test
	void readinessProbeIncludesTheDatabase() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void futureApiPathsAreProtectedByDefault() throws Exception {
		mockMvc.perform(get("/api/v1/games"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void actuatorInternalsAreNotExposedEvenToAuthenticatedUsers() throws Exception {
		mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/actuator/env").with(user("someone"))).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/beans").with(user("someone"))).andExpect(status().isNotFound());
	}

}
