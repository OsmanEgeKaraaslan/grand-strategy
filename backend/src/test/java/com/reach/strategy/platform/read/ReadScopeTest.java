package com.reach.strategy.platform.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ReadScopeTest {

	private final AtomicInteger loads = new AtomicInteger();

	@AfterEach
	void clearTransaction() {
		TransactionSynchronizationManager.setActualTransactionActive(false);
		TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
	}

	@Test
	void outsideAScopeEveryReadRuns() {
		assertThat(ReadScope.active()).isFalse();
		ReadScope.remember(List.of("x", 1), loads::incrementAndGet);
		ReadScope.remember(List.of("x", 1), loads::incrementAndGet);
		assertThat(loads).hasValue(2);
	}

	@Test
	void insideAScopeAReadWithTheSameKeyRunsOnce() {
		readOnlyTransaction();
		int[] values = ReadScope.run(() -> new int[] { ReadScope.remember(List.of("x", 1), loads::incrementAndGet),
				ReadScope.remember(List.of("x", 1), loads::incrementAndGet),
				ReadScope.remember(List.of("x", 2), loads::incrementAndGet) });
		assertThat(values).containsExactly(1, 1, 2);
		assertThat(ReadScope.active()).as("the memory ends with the scope").isFalse();
	}

	@Test
	void aScopeNeedsAReadOnlyTransaction() {
		assertThatThrownBy(() -> ReadScope.run(() -> 1)).isInstanceOf(IllegalStateException.class);
		TransactionSynchronizationManager.setActualTransactionActive(true);
		assertThatThrownBy(() -> ReadScope.run(() -> 1)).as("a writing transaction").isInstanceOf(IllegalStateException.class);
	}

	private static void readOnlyTransaction() {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
	}

}
