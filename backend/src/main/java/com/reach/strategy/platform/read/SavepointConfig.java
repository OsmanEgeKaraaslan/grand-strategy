package com.reach.strategy.platform.read;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

/**
 * A participating {@code @Transactional} method that throws no longer marks the whole transaction
 * rollback-only; the transaction's owner decides (it still rolls back when the exception reaches it).
 * A computer turn plays in one transaction (ENGINE_DESIGN S6, decision D2) and undoes a refused action
 * with a savepoint ({@link Savepoints}), after which the turn must be able to commit. Code that lets
 * an exception reach its transaction boundary behaves exactly as before.
 */
@Configuration
class SavepointConfig {

	@Bean
	static BeanPostProcessor ownerDecidesRollback() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String name) {
				if (bean instanceof AbstractPlatformTransactionManager manager) {
					manager.setGlobalRollbackOnParticipationFailure(false);
				}
				return bean;
			}
		};
	}

}
