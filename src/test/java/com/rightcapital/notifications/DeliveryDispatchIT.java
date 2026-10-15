package com.rightcapital.notifications;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://127.0.0.1:15432/notification_test}",
    "spring.datasource.username=${TEST_DB_USER:postgres}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "notify.worker-enabled=false", "notify.clients.business=key-a", "notify.concurrency=2",
    "notify.request-timeout=5s", "notify.lease-duration=10s", "notify.api-timeout=1500ms"})
class DeliveryDispatchIT {
    static final HttpServer supplier;
    static final ExecutorService httpThreads = Executors.newCachedThreadPool();
    static volatile CountDownLatch entered;
    static volatile CountDownLatch release;
    static final AtomicInteger active = new AtomicInteger();
    static final AtomicInteger maxActive = new AtomicInteger();
    static {
        try {
            supplier = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            supplier.setExecutor(httpThreads);
            supplier.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                int current = active.incrementAndGet(); maxActive.accumulateAndGet(current, Math::max); entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); exchange.sendResponseHeaders(204, -1); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                finally { active.decrementAndGet(); exchange.close(); }
            });
            supplier.start();
        } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("notify.allowed-origins", () -> "http://127.0.0.1:" + supplier.getAddress().getPort());
    }
    @Autowired NotificationRepository repository;
    @Autowired NotificationService service;
    @Autowired DeliveryClient client;
    @Autowired RetryPolicy retry;
    @Autowired NotificationProperties properties;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void reset() { jdbc.execute("TRUNCATE attempts,notifications CASCADE"); active.set(0); maxActive.set(0); entered = new CountDownLatch(2); release = new CountDownLatch(1); }
    @AfterAll static void stop() { supplier.stop(0); httpThreads.shutdownNow(); }
    DeliveryWorker dispatcher() {
        var p = properties;
        var enabled = new NotificationProperties(p.clients(), p.allowedOrigins(), true, p.concurrency(), p.maxAttempts(), p.maxAge(),
            p.requestTimeout(), p.leaseDuration(), p.backoffBase(), p.backoffCap(), p.apiTimeout());
        return new DeliveryWorker(repository, client, retry, enabled, Clock.systemUTC());
    }
    void submit(int count) {
        for (int i=0; i<count; i++) service.submit("business", new NotificationRequest(UUID.randomUUID().toString(),
            "http://127.0.0.1:" + supplier.getAddress().getPort() + "/", "POST", Map.of(), ""));
    }
    void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition did not become true before deadline");
    }
    int succeeded() { return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE status='succeeded'", Integer.class); }

    @Test void dispatchClaimsOnlyAsManyTasksAsAvailableWorkerSlots() throws Exception {
        submit(4); var worker = dispatcher();
        try {
            worker.dispatch(); assertTrue(entered.await(3, TimeUnit.SECONDS)); worker.dispatch();
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE status='processing'", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE status='pending'", Integer.class));
            release.countDown();
            await(() -> { worker.dispatch(); return succeeded() == 4; });
            assertEquals(2, maxActive.get()); assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM attempts", Integer.class));
        } finally { release.countDown(); worker.close(); }
    }
    @Test void failedClaimTransactionReleasesSlotsForLaterDelivery() throws Exception {
        submit(2); release.countDown(); var worker = dispatcher();
        jdbc.execute("CREATE SEQUENCE injected_claim_failures");
        jdbc.execute("CREATE FUNCTION reject_claim() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM nextval('injected_claim_failures'); RAISE EXCEPTION 'injected claim failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_claim BEFORE INSERT ON attempts FOR EACH ROW EXECUTE FUNCTION reject_claim()");
        try {
            try {
                worker.dispatch();
                await(() -> jdbc.queryForObject("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM injected_claim_failures", Long.class) >= 2);
                assertEquals(0, succeeded());
            } finally {
                jdbc.execute("DROP TRIGGER fail_claim ON attempts"); jdbc.execute("DROP FUNCTION reject_claim()"); jdbc.execute("DROP SEQUENCE injected_claim_failures");
            }
            await(() -> { worker.dispatch(); return succeeded() == 2; });
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM attempts", Integer.class));
        } finally { worker.close(); }
    }
    @Test void blockedClaimTransactionsReleaseAllWorkerSlots() throws Exception {
        submit(2); release.countDown(); var worker = dispatcher();
        try (var lock = jdbc.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) { statement.execute("LOCK TABLE attempts IN ACCESS EXCLUSIVE MODE"); }
            try {
                worker.dispatch();
                await(() -> blockedClaims() == 2);
                await(() -> blockedClaims() == 0);
            } finally { lock.rollback(); }
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM attempts", Integer.class));
            await(() -> { worker.dispatch(); return succeeded() == 2; });
        } finally { worker.close(); }
    }
    int blockedClaims() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'INSERT INTO attempts%'", Integer.class);
    }
}
