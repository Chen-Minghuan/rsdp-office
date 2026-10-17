package com.rsdp.officeproduct.importer.service;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Separate bounded pools: a waiting job must never occupy its model workers. */
@Component
public class VisionTaskExecutors implements DisposableBean {
    private final ThreadPoolTaskExecutor jobs = pool("vision-job-", 2, 8);
    private final ThreadPoolTaskExecutor models = pool("vision-model-", 4, 8);

    private static ThreadPoolTaskExecutor pool(String prefix, int threads, int queue) {
        var pool = new ThreadPoolTaskExecutor();
        pool.setThreadNamePrefix(prefix);
        pool.setCorePoolSize(threads);
        pool.setMaxPoolSize(threads);
        pool.setQueueCapacity(queue);
        pool.setDaemon(true);
        pool.initialize();
        return pool;
    }
    public void executeJob(Runnable job) { jobs.execute(job); }
    public <T> CompletableFuture<T> model(Supplier<T> request) { return CompletableFuture.supplyAsync(request, models); }
    @Override public void destroy() { jobs.shutdown(); models.shutdown(); }
}
