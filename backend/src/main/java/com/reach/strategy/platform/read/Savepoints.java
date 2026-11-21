package com.reach.strategy.platform.read;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.function.Supplier;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs work in a JDBC savepoint of the current transaction (ENGINE_DESIGN S6): if it throws, its
 * database changes are rolled back to the savepoint and the transaction continues. The savepoint is
 * set on the transaction's own connection (the JPA dialect offers none). JPA entities are not part of
 * a savepoint, so pending entity changes are flushed before it and the persistence context is cleared
 * after a rollback (entities are read again when next needed); a turn scope forgets everything it
 * remembered, since the work may have changed remembered values before it failed.
 * <p>
 * For the transaction to go on after the work failed, a participating {@code @Transactional} method
 * that throws must not mark the whole transaction rollback-only ({@link SavepointConfig}).
 */
@Component
public class Savepoints {

	private final DataSource dataSource;
	private final EntityManager entityManager;

	Savepoints(DataSource dataSource, EntityManager entityManager) {
		this.dataSource = dataSource;
		this.entityManager = entityManager;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public <T> T run(Supplier<T> work) {
		entityManager.flush();
		Connection connection = DataSourceUtils.getConnection(dataSource);
		Savepoint savepoint = savepoint(connection);
		T result;
		try {
			result = work.get();
		}
		catch (RuntimeException failed) {
			rollback(connection, savepoint);
			entityManager.clear();
			ReadScope.forgetAll();
			throw failed;
		}
		release(connection, savepoint);
		return result;
	}

	private static Savepoint savepoint(Connection connection) {
		try {
			return connection.setSavepoint();
		}
		catch (SQLException e) {
			throw new DataAccessResourceFailureException("Could not set a savepoint", e);
		}
	}

	private static void rollback(Connection connection, Savepoint savepoint) {
		try {
			connection.rollback(savepoint);
		}
		catch (SQLException e) {
			throw new DataAccessResourceFailureException("Could not roll back to a savepoint", e);
		}
	}

	private static void release(Connection connection, Savepoint savepoint) {
		try {
			connection.releaseSavepoint(savepoint);
		}
		catch (SQLException e) {
			throw new DataAccessResourceFailureException("Could not release a savepoint", e);
		}
	}

}
