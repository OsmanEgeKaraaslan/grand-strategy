package com.reach.strategy.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SQL statements per request on a Grand Campaign game (72 provinces, 10 nations). Against a hosted
 * database every statement is a network round trip, so a view that asks once per city or per nation
 * (N+1) turns into seconds: the economy overview once took 273 statements (2026-11-20). The budgets
 * are about 1.5x the measured counts; raise one only with a reason, never to hide a per-row query.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v8", "strategy.world.default-map=grand-campaign",
		"strategy.ai.execution=manual" })
class QueryBudgetIT {

	private static final AtomicInteger STATEMENTS = new AtomicInteger();

	@TestConfiguration
	static class CountStatements {

		@Bean
		static BeanPostProcessor statementCounter() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String name) {
					return bean instanceof DataSource dataSource ? counting(dataSource) : bean;
				}
			};
		}

		private static DataSource counting(DataSource target) {
			return (DataSource) Proxy.newProxyInstance(QueryBudgetIT.class.getClassLoader(), new Class<?>[] { DataSource.class },
					(proxy, method, args) -> {
						Object result = invoke(method, target, args);
						return result instanceof Connection connection ? counting(connection) : result;
					});
		}

		private static Connection counting(Connection target) {
			return (Connection) Proxy.newProxyInstance(QueryBudgetIT.class.getClassLoader(), new Class<?>[] { Connection.class },
					(proxy, method, args) -> {
						String name = method.getName();
						if (name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("createStatement")) {
							STATEMENTS.incrementAndGet();
						}
						return invoke(method, target, args);
					});
		}

		private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
			try {
				return method.invoke(target, args);
			}
			catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void everyEndpointStaysWithinItsQueryBudget() throws Exception {
		ApiDriver api = new ApiDriver(mockMvc);
		CampaignScenario c = new CampaignScenario(api, jdbc);
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		String token = c.activeToken(game);
		String g = "/api/v1/games/" + game.gameId();
		String cityId = api.cityId(token, game.gameId(), "meseta");

		Map<String, Integer> budgets = new LinkedHashMap<>();
		budgets.put(g + "/state", 14);
		budgets.put(g + "/economy", 30);
		budgets.put(g + "/cities/" + cityId + "/economy", 17);
		budgets.put(g + "/military", 29);
		budgets.put(g + "/battles", 23);
		budgets.put(g + "/sieges", 15);
		budgets.put(g + "/research", 15);
		budgets.put(g + "/diplomacy", 32);
		for (Map.Entry<String, Integer> budget : budgets.entrySet()) {
			statusOf(token, budget.getKey()); // warm-up: caches and lazy initialisation are not what is measured
			STATEMENTS.set(0);
			assertThat(statusOf(token, budget.getKey())).as(budget.getKey()).isEqualTo(200);
			assertThat(STATEMENTS.get()).as("SQL statements for GET " + budget.getKey()).isLessThanOrEqualTo(budget.getValue());
		}

		STATEMENTS.set(0);
		assertThat(api.recruit(token, game.gameId(), cityId, "INFANTRY", UUID.randomUUID()).getResponse().getStatus())
			.isEqualTo(201);
		assertThat(STATEMENTS.get()).as("SQL statements for a recruitment").isLessThanOrEqualTo(27);

		String battalion = c.place(game, "castavar", "meseta", "INFANTRY", 500);
		STATEMENTS.set(0);
		assertThat(api.move(token, game.gameId(), List.of(battalion), "valcaro", UUID.randomUUID()).getResponse().getStatus())
			.isEqualTo(200);
		assertThat(STATEMENTS.get()).as("SQL statements for a move").isLessThanOrEqualTo(36);
	}

	private int statusOf(String token, String path) throws Exception {
		return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse().getStatus();
	}

}
