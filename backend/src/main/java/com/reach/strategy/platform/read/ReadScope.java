package com.reach.strategy.platform.read;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Remembers read results within one transaction, so state shared by several reads (the world
 * snapshot, a membership, a module's values) is loaded once.
 * <ul>
 * <li>{@link #run} opens a scope in a <em>read-only</em> transaction (the computer's observation):
 * nothing can be written, so nothing remembered can go stale.</li>
 * <li>{@link #runTurn} opens a scope in a <em>writing</em> transaction: every game command, every end
 * of turn and a whole computer turn (ENGINE_DESIGN S6, decision D2). Every writer keeps the memory
 * exact: a store replaces the
 * value it remembered with the value it wrote ({@link #replace}) or forgets it ({@link #forget}); the
 * world forgets its snapshot on every write; a membership (turn, pause, state version) is forgotten
 * whenever the game row changes and at every command boundary.</li>
 * </ul>
 * Outside a scope nothing is remembered. Remembered values must be immutable. The key names the read and its arguments, its first
 * element being the read's name, e.g. {@code List.of("world.snapshot", gameId)}.
 */
public final class ReadScope {

	private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

	private record Scope(Map<List<Object>, Object> memory, boolean turn) {
	}

	private ReadScope() {
	}

	/** Runs {@code work} with a fresh memory in a read-only transaction; nested calls share the outer scope. */
	public static <T> T run(Supplier<T> work) {
		if (SCOPE.get() != null) {
			return work.get();
		}
		if (!TransactionSynchronizationManager.isActualTransactionActive()
				|| !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			throw new IllegalStateException("A read scope needs a read-only transaction");
		}
		return open(work, false);
	}

	/** Runs a command, an end of turn or a computer turn with a fresh memory in a writing transaction. */
	public static <T> T runTurn(Supplier<T> work) {
		if (SCOPE.get() != null) {
			throw new IllegalStateException("A turn scope cannot be nested in another scope");
		}
		if (!TransactionSynchronizationManager.isActualTransactionActive()
				|| TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			throw new IllegalStateException("A turn scope needs a writing transaction");
		}
		return open(work, true);
	}

	private static <T> T open(Supplier<T> work, boolean turn) {
		SCOPE.set(new Scope(new HashMap<>(), turn));
		try {
			return work.get();
		}
		finally {
			SCOPE.remove();
		}
	}

	/** Whether a scope is open on this thread (a caller may then prefer one shared read over many small ones). */
	public static boolean active() {
		return SCOPE.get() != null;
	}

	/**
	 * The remembered value for {@code key} inside a scope, loading it on first use; outside a scope
	 * {@code load} simply runs.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T remember(List<Object> key, Supplier<T> load) {
		Scope scope = SCOPE.get();
		if (scope == null) {
			return load.get();
		}
		if (scope.memory().containsKey(key)) {
			return (T) scope.memory().get(key);
		}
		T value = load.get();
		scope.memory().put(key, value);
		return value;
	}

	/** The remembered value for {@code key}, or null when nothing is remembered (or no scope is open). */
	@SuppressWarnings("unchecked")
	public static <T> T peek(List<Object> key) {
		Scope scope = SCOPE.get();
		return scope == null ? null : (T) scope.memory().get(key);
	}

	/**
	 * After a write: if {@code key} still remembers exactly {@code written} (the value the write started
	 * from), it now remembers {@code value}; otherwise the key is forgotten, so the next read loads the
	 * written state.
	 */
	public static void replace(List<Object> key, Object written, Object value) {
		Scope scope = SCOPE.get();
		if (scope == null) {
			return;
		}
		if (written != null && scope.memory().get(key) == written) {
			scope.memory().put(key, value);
		}
		else {
			scope.memory().remove(key);
		}
	}

	/** Forgets every remembered read with this name (any arguments). */
	public static void forget(String name) {
		Scope scope = SCOPE.get();
		if (scope != null) {
			scope.memory().keySet().removeIf(key -> !key.isEmpty() && name.equals(key.getFirst()));
		}
	}

	/** Forgets everything (after a rolled-back action, say). */
	public static void forgetAll() {
		Scope scope = SCOPE.get();
		if (scope != null) {
			scope.memory().clear();
		}
	}

}
