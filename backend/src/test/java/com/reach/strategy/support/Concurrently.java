package com.reach.strategy.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs a task on several threads released at the same instant, to provoke races. */
public final class Concurrently {

	private Concurrently() {
	}

	@FunctionalInterface
	public interface IndexedTask<T> {

		T run(int index) throws Exception;

	}

	public static <T> List<T> run(int threads, IndexedTask<T> task) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int index = i;
				futures.add(executor.submit(() -> {
					start.await();
					return task.run(index);
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		}
		finally {
			executor.shutdownNow();
		}
	}

}
