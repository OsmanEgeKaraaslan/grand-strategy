package com.reach.strategy.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.campaign.CampaignScenario;
import com.reach.strategy.game.api.ComputerSeats;
import com.reach.strategy.identity.api.ComputerPlayers;
import com.reach.strategy.support.ApiDriver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SQL statements of the computer opponents on a Grand Campaign game (8 computer seats). Every
 * statement is a round trip to a hosted database, so these budgets keep computer rounds fast
 * (docs/ENGINE_DESIGN.md). Measured 2026-11-20 after S1: one observation 42 statements (was 84), one round of 8 computer turns 2,176 (was ~4,500).
 * Raise a budget only with a reason; lower it when a stage of the engine design lands.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v8", "strategy.world.default-map=grand-campaign",
		"strategy.ai.execution=manual" })
class ComputerQueryBudgetIT {

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
			return (DataSource) Proxy.newProxyInstance(ComputerQueryBudgetIT.class.getClassLoader(),
					new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
						Object result = invoke(method, target, args);
						return result instanceof Connection connection ? counting(connection) : result;
					});
		}

		private static Connection counting(Connection target) {
			return (Connection) Proxy.newProxyInstance(ComputerQueryBudgetIT.class.getClassLoader(),
					new Class<?>[] { Connection.class }, (proxy, method, args) -> {
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

	@Autowired
	AiTurnRunner runner;

	@Autowired
	AiObserver observer;

	@Autowired
	ComputerSeats seats;

	@Autowired
	ComputerPlayers computers;

	@Autowired
	PlatformTransactionManager transactions;

	@Test
	void computerOpponentsStayWithinTheirQueryBudgets() throws Exception {
		CampaignScenario c = new CampaignScenario(new ApiDriver(mockMvc), jdbc);
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		UUID gameId = UUID.fromString(game.gameId());
		c.endTurn(game);
		c.endTurn(game);

		TransactionTemplate readOnly = new TransactionTemplate(transactions);
		readOnly.setReadOnly(true);
		ComputerSeats.Turn turn = readOnly.execute(s -> seats.activeComputerTurn(gameId)).orElseThrow();
		var actor = computers.actor(turn.seat().playerId());
		readOnly.execute(s -> observer.observe(actor, gameId, turn.round(), turn.turnNumber())); // warm-up
		STATEMENTS.set(0);
		readOnly.execute(s -> observer.observe(actor, gameId, turn.round(), turn.turnNumber()));
		int observation = STATEMENTS.get();

		STATEMENTS.set(0);
		runner.run(gameId);
		int round = STATEMENTS.get();
		System.out.printf("COMPUTER BUDGET observation=%d round=%d%n", observation, round);

		assertThat(c.round(game)).as("the computers finished round 1").isEqualTo(2);
		assertThat(observation).as("SQL statements for one computer observation").isLessThanOrEqualTo(50);
		assertThat(round).as("SQL statements for one round of 8 computer turns").isLessThanOrEqualTo(ROUND_BUDGET);
	}

	/** Measured 1,989 after S2a (2,176 after S1; ~4,500 before D-AI1 and S1). */
	private static final int ROUND_BUDGET = 2_600;

}
