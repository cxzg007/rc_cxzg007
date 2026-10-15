package com.rightcapital.notifications;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://127.0.0.1:15432/notification_test}",
    "spring.datasource.username=${TEST_DB_USER:postgres}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "notify.worker-enabled=false", "notify.clients.business=key-a", "notify.request-timeout=200ms",
    "notify.lease-duration=1s", "notify.max-attempts=3", "notify.max-age=1h", "notify.backoff-base=20ms", "notify.backoff-cap=100ms", "notify.api-timeout=1500ms"})
@Import(DeliveryIT.TimeConfiguration.class)
class DeliveryIT {
    static final HttpServer supplier;
    static final ExecutorService serverThreads = Executors.newCachedThreadPool();
    static final AtomicInteger status = new AtomicInteger(204);
    static final AtomicInteger delayMillis = new AtomicInteger();
    static final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
    record Received(String method, String path, String body, String authorization) {}
    static {
        try {
            supplier = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            supplier.setExecutor(serverThreads);
            supplier.createContext("/", exchange -> {
                int response = status.get(); int delay = delayMillis.get();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body, exchange.getRequestHeaders().getFirst("Authorization")));
                try {
                    if (delay > 0) Thread.sleep(delay);
                    if (response == 302) exchange.getResponseHeaders().set("Location", "/redirected");
                    exchange.sendResponseHeaders(response, -1);
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                catch (IOException ignored) { /* caller may have timed out after this side effect */ }
                finally { exchange.close(); }
            });
            supplier.start();
        } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("notify.allowed-origins", () -> "http://127.0.0.1:" + supplier.getAddress().getPort());
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private Instant time = Instant.parse("2026-10-14T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
        void advance(long seconds) { time = time.plusSeconds(seconds); }
        void reset() { time = Instant.parse("2026-10-14T00:00:00Z"); }
    }
    @Autowired NotificationService service;
    @Autowired NotificationRepository repository;
    @Autowired DeliveryWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JsonMapper json;
    @Autowired NotificationProperties config;
    @Autowired DeliveryClient client;
    @Autowired RetryPolicy retry;

    @BeforeEach void reset() { jdbc.execute("TRUNCATE attempts, notifications CASCADE"); status.set(204); delayMillis.set(0); received.clear(); clock.reset(); }
    @AfterAll static void stop() { supplier.stop(0); serverThreads.shutdownNow(); }
    NotificationRepository.Job submit() {
        return service.submit("business", new NotificationRequest(UUID.randomUUID().toString(),
            "http://127.0.0.1:" + supplier.getAddress().getPort() + "/hook?q=1", "PATCH",
            Map.of("Content-Type", "application/json", "Authorization", "Bearer test-supplier"), "{\"order\":\"订单\"}")).job();
    }
    NotificationRepository.Job get(UUID id) { return repository.get("business", id); }

    @Test void forwardsExactRequestAndPersistsHttpSuccess() throws Exception {
        var job = submit(); worker.runOne();
        assertEquals("succeeded", get(job.id()).status());
        var request = received.poll(1, TimeUnit.SECONDS); assertNotNull(request);
        assertEquals("PATCH", request.method()); assertEquals("/hook?q=1", request.path());
        assertEquals("{\"order\":\"订单\"}", request.body()); assertEquals("Bearer test-supplier", request.authorization());
        assertEquals(1, get(job.id()).attempts()); assertEquals(204, get(job.id()).lastStatusCode());
        assertEquals("succeeded", repository.attempts(job.id()).get(0).get("outcome"));
    }
    @Test void transientFailureWaitsUntilDueThenSucceeds() {
        status.set(503); var job = submit(); worker.runOne();
        assertEquals("pending", get(job.id()).status()); assertEquals(1, get(job.id()).attempts());
        worker.runOne(); assertEquals(1, received.size());
        clock.advance(1); status.set(204); worker.runOne();
        assertEquals("succeeded", get(job.id()).status()); assertEquals(2, get(job.id()).attempts());
    }
    @Test void permanentFailureDoesNotRetry() {
        status.set(400); var job = submit(); worker.runOne(); clock.advance(2); worker.runOne();
        assertEquals("failed", get(job.id()).status()); assertEquals(1, received.size()); assertEquals("http_400", get(job.id()).lastError());
    }
    @Test void redirectsAreNotFollowed() {
        status.set(302); var job = submit(); worker.runOne();
        assertEquals("failed", get(job.id()).status()); assertEquals(1, received.size());
        assertEquals("/hook?q=1", received.peek().path());
    }
    @Test void timeoutCanDuplicateAnAlreadyReceivedNotification() {
        delayMillis.set(400); var job = submit(); worker.runOne();
        assertEquals("pending", get(job.id()).status()); assertEquals("http_timeout", get(job.id()).lastError());
        delayMillis.set(0); clock.advance(2); worker.runOne();
        assertEquals("succeeded", get(job.id()).status()); assertEquals(2, received.size());
    }
    @Test void attemptAndAgeBudgetsStopFurtherDelivery() {
        status.set(500); var job = submit();
        for (int i=0;i<4;i++) { worker.runOne(); clock.advance(2); }
        assertEquals("failed", get(job.id()).status()); assertEquals(3, received.size());
        assertEquals("attempt_budget_exhausted", get(job.id()).lastError());
        var expired = submit(); clock.advance(3601); worker.runOne();
        assertEquals("failed", get(expired.id()).status()); assertEquals(0, get(expired.id()).attempts());
        assertEquals(3, received.size());
    }
    @Test void ageBudgetCrossedDuringClaimPreventsTheHttpRequest() {
        var job = submit();
        var delayedRepository = new NotificationRepository(jdbc, transactions, json, config) {
            @Override public Optional<Claim> claim(Instant now) {
                var claimed = super.claim(now);
                clock.advance(3600);
                return claimed;
            }
        };
        var delayedWorker = new DeliveryWorker(delayedRepository, client, retry, config, clock);
        try { delayedWorker.runOne(); } finally { delayedWorker.close(); }
        assertTrue(received.isEmpty(), "A claim completed at the deadline must not contact the supplier");
        assertEquals("failed", get(job.id()).status());
        assertEquals("age_budget_exhausted", get(job.id()).lastError());
        assertEquals(1, get(job.id()).attempts());
        assertEquals("failed", repository.attempts(job.id()).get(0).get("outcome"));
    }
    @Test void crashedClaimIsRecoveredAndOldTokenCannotOverwriteNewClaim() {
        var job = submit(); var old = repository.claim(clock.instant()).orElseThrow();
        assertTrue(repository.claim(clock.instant()).isEmpty()); clock.advance(2);
        var restartedRepository = new NotificationRepository(jdbc, transactions, json, config);
        var recovered = restartedRepository.claim(clock.instant()).orElseThrow();
        assertEquals(job.id(), recovered.job().id()); assertNotEquals(old.leaseToken(), recovered.leaseToken());
        var success = new DeliveryResult(204, null, "http_204", false);
        var decision = new RetryPolicy.Decision(true, null, null);
        assertFalse(repository.complete(old, success, decision, clock.instant()));
        assertEquals("processing", get(job.id()).status());
        assertTrue(restartedRepository.complete(recovered, success, decision, clock.instant()));
        assertEquals("succeeded", get(job.id()).status());
        assertEquals("unknown", repository.attempts(job.id()).get(0).get("outcome"));
    }
    @Test void concurrentWorkersCannotClaimTheSameLiveLease() throws Exception {
        submit(); var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i=0;i<8;i++) tasks.add(() -> repository.claim(clock.instant()).isPresent());
            int claims = 0; for (var f : pool.invokeAll(tasks)) if (f.get()) claims++;
            assertEquals(1, claims); assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM attempts", Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void resultAndAttemptHistoryCommitAtomically() {
        var job = submit(); var claim = repository.claim(clock.instant()).orElseThrow();
        jdbc.execute("ALTER TABLE attempts ADD CONSTRAINT reject_success CHECK (outcome <> 'succeeded') NOT VALID");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> repository.complete(claim,
                new DeliveryResult(204, null, null, false), new RetryPolicy.Decision(true, null, null), clock.instant()));
            assertEquals("processing", get(job.id()).status());
        } finally { jdbc.execute("ALTER TABLE attempts DROP CONSTRAINT reject_success"); }
        clock.advance(2); worker.runOne();
        assertEquals("succeeded", get(job.id()).status()); assertEquals(2, get(job.id()).attempts());
        assertEquals("unknown", repository.attempts(job.id()).get(0).get("outcome"));
    }
    @Test void lastClaimCrashExhaustsBudgetWithoutAnotherSend() {
        var job = submit(); NotificationRepository.Claim last = null;
        for (int i=0; i<3; i++) { last = repository.claim(clock.instant()).orElseThrow(); clock.advance(2); }
        assertTrue(repository.claim(clock.instant()).isEmpty());
        assertEquals("failed", get(job.id()).status()); assertEquals(3, get(job.id()).attempts());
        assertEquals("attempt_budget_exhausted", get(job.id()).lastError());
        assertEquals("unknown", repository.attempts(job.id()).get(2).get("outcome"));
        assertTrue(received.isEmpty());
        assertFalse(repository.complete(last, new DeliveryResult(204, null, null, false), new RetryPolicy.Decision(true, null, null), clock.instant()));
    }
    @Test void blockedResultWriteRollsBackHistoryAndRecoversByLease() throws Exception {
        var job = submit(); var claim = repository.claim(clock.instant()).orElseThrow();
        var result = client.send(claim); assertEquals(204, result.statusCode());
        var decision = new RetryPolicy.Decision(true, null, null);
        var pool = Executors.newSingleThreadExecutor();
        try (var lock = jdbc.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) { statement.execute("LOCK TABLE attempts IN ACCESS EXCLUSIVE MODE"); }
            try {
                var completion = pool.submit(() -> repository.complete(claim, result, decision, clock.instant()));
                var failure = assertThrows(ExecutionException.class, () -> completion.get(2500, TimeUnit.MILLISECONDS));
                assertInstanceOf(org.springframework.dao.DataAccessException.class, failure.getCause());
                assertEquals("processing", get(job.id()).status());
            } finally { lock.rollback(); }
        } finally { pool.shutdownNow(); }
        assertEquals("processing", repository.attempts(job.id()).get(0).get("outcome"));
        clock.advance(2); worker.runOne();
        assertEquals("succeeded", get(job.id()).status()); assertEquals(2, received.size());
        assertEquals("unknown", repository.attempts(job.id()).get(0).get("outcome"));
        assertFalse(repository.complete(claim, result, decision, clock.instant()));
    }
}
