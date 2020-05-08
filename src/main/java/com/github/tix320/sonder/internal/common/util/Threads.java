package com.github.tix320.sonder.internal.common.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class Threads {

	public static void runAsync(Runnable runnable, ExecutorService executorService) {
		CompletableFuture.runAsync(runnable, executorService).exceptionally(throwable -> {
			throwable.getCause().printStackTrace();
			return null;
		});
	}

	public static void runAsync(Runnable runnable) {
		CompletableFuture.runAsync(runnable).exceptionally(throwable -> {
			throwable.getCause().printStackTrace();
			return null;
		});
	}
}
