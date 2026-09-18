package io.nostro.api.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** Runs the same work on N threads released together, and returns every result or the first failure. */
final class Concurrently {

    private Concurrently() {
    }

    static <T> List<T> run(int writers, Supplier<T> work) throws Exception {
        var start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            for (int i = 0; i < writers; i++) {
                Callable<T> task = () -> {
                    start.await();
                    return work.get();
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
