package com.github.tix320.sonder.internal.common.util;

import java.util.concurrent.*;

public class Threads {

	private static final ExecutorService EXECUTOR_SERVICE = new ThreadPoolExecutor(15, Integer.MAX_VALUE, 60L,
			TimeUnit.SECONDS, new SynchronousQueue<>());

	public static void runAsync(Runnable runnable) {
		CompletableFuture.runAsync(runnable, EXECUTOR_SERVICE).exceptionally(throwable -> {
			throwable.printStackTrace();
			return null;
		});
	}
}
