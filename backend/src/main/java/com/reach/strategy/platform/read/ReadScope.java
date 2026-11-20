package com.reach.strategy.platform.read;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Remembers read results for the duration of one read-only transaction, so several module views
 * built for the same caller (the computer opponent's observation) load shared state such as the
 * world snapshot or a membership only once. Inside one REPEATABLE READ transaction the database would
 * return the same rows for the repeated read anyway: results are identical, only queries disappear.
 * <p>
 * Outside {@link #run} nothing is remembered, so endpoints and commands behave exactly as without it.
 * A scope may only be opened inside a read-only transaction (checked), so nothing written during the
 * scope can ever be answered from a stale memory. Remembered values must be immutable.
 */
public final class ReadScope {

	private static final ThreadLocal<Map<List<Object>, Object>> MEMORY = new ThreadLocal<>();

	private ReadScope() {
	}

	/** Runs {@code work} with a fresh memory; nested calls share the outer scope. */
	public static <T> T run(Supplier<T> work) {
		if (MEMORY.get() != null) {
			return work.get();
		}
		if (!TransactionSynchronizationManager.isActualTransactionActive()
				|| !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			throw new IllegalStateException("A read scope needs a read-only transaction");
		}
		MEMORY.set(new HashMap<>());
		try {
			return work.get();
		}
		finally {
			MEMORY.remove();
		}
	}

	/** Whether a scope is open on this thread (a caller may then prefer one shared read over many small ones). */
	public static boolean active() {
		return MEMORY.get() != null;
	}

	/**
	 * The remembered value for {@code key} inside a scope, loading it on first use; outside a scope
	 * {@code load} simply runs. The key names the read and its arguments, e.g.
	 * {@code List.of("world.snapshot", gameId)}.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T remember(List<Object> key, Supplier<T> load) {
		Map<List<Object>, Object> memory = MEMORY.get();
		if (memory == null) {
			return load.get();
		}
		if (memory.containsKey(key)) {
			return (T) memory.get(key);
		}
		T value = load.get();
		memory.put(key, value);
		return value;
	}

}
