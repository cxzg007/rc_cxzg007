package com.rightcapital.notifications;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import java.time.Clock;
import java.util.concurrent.*;

@Component
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private final NotificationRepository repository;
    private final DeliveryClient client;
    private final RetryPolicy retry;
    private final NotificationProperties properties;
    private final Clock clock;
    private final ExecutorService executor;
    private final Semaphore slots;
    private volatile boolean stopping;

    public DeliveryWorker(NotificationRepository repository, DeliveryClient client, RetryPolicy retry, NotificationProperties properties, Clock clock) {
        this.repository = repository; this.client = client; this.retry = retry; this.properties = properties; this.clock = clock;
        slots = new Semaphore(properties.concurrency());
        executor = Executors.newFixedThreadPool(properties.concurrency(), runnable -> {
            var thread = new Thread(runnable, "notification-worker"); thread.setDaemon(false); return thread;
        });
    }
    @Scheduled(fixedDelayString="${notify.poll-millis:1000}")
    public void dispatch() {
        if (!properties.workerEnabled() || stopping) return;
        for (int i = 0; i < properties.concurrency() && slots.tryAcquire(); i++) {
            try { executor.execute(() -> { try { runOne(); } finally { slots.release(); } }); }
            catch (RejectedExecutionException ex) { slots.release(); return; }
        }
    }
    public void runOne() {
        try {
            var optional = repository.claim(clock.instant());
            if (optional.isEmpty()) return;
            var claim = optional.get();
            var result = client.send(claim);
            var now = clock.instant();
            var decision = retry.decide(claim.attempt(), claim.job().createdAt(), now, result);
            boolean applied = repository.complete(claim, result, decision, now);
            log.info("notification={} attempt={} result={} applied={}", claim.job().id(), claim.attempt(),
                decision.success() ? "succeeded" : decision.retryAt() != null ? "retry" : "failed", applied);
        } catch (RuntimeException ex) {
            // A failed state write leaves the lease recoverable. Do not log payload-bearing exception details.
            log.warn("Delivery cycle interrupted by {}", ex.getClass().getSimpleName());
        }
    }
    @PreDestroy public void close() {
        stopping = true; executor.shutdown();
        try {
            long waitMillis = Math.min(15000, properties.requestTimeout().toMillis() + 2000);
            if (!executor.awaitTermination(waitMillis, TimeUnit.MILLISECONDS)) executor.shutdownNow();
        } catch (InterruptedException ex) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
