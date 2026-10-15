package com.rightcapital.notifications;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeliveryDeadlineTest {
    private NotificationProperties properties(String origin) {
        return new NotificationProperties(Map.of("business", "key"), List.of(origin), false, 1, 20,
            Duration.ofHours(24), Duration.ofSeconds(1), Duration.ofSeconds(3),
            Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofSeconds(5));
    }
    private NotificationRepository.Claim claim(String origin) {
        var now = Instant.now();
        return new NotificationRepository.Claim(new NotificationRepository.Job(UUID.randomUUID(), "business", "deadline", "",
            origin + "/hook", "GET", Map.of(), "", "pending", 0, now, now, now, null, null, null, null, null), UUID.randomUUID(), 1);
    }
    private void readHeaders(Socket socket) throws IOException {
        socket.setSoTimeout(2500);
        var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) { }
    }

    @Test void automaticTransportRetryCannotRestartTheOverallDeadline() throws Exception {
        try (var server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(1500);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var supplier = pool.submit(() -> {
                    try {
                        try (var first = server.accept()) { readHeaders(first); Thread.sleep(600); }
                        try (var second = server.accept()) {
                            readHeaders(second); Thread.sleep(600);
                            second.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        }
                    } catch (IOException expectedAfterCancellation) {
                        // A cancelled request may close either connection before the delayed response.
                    } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                });
                String origin = "http://127.0.0.1:" + server.getLocalPort();
                var config = properties(origin);
                var client = new DeliveryClient(HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build(), new OriginPolicy(config), config);
                long started = System.nanoTime();
                var result = client.send(claim(origin));
                assertEquals("http_timeout", result.reason(), result.toString());
                assertNull(result.statusCode());
                assertTrue(result.retryable());
                assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
                supplier.get(4, TimeUnit.SECONDS);
            } finally { server.close(); pool.shutdownNow(); }
        }
    }

    @Test void closesResponseWithoutWaitingForTheSupplierBody() throws Exception {
        try (var server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(2500);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var supplier = pool.submit(() -> {
                    try (var connection = server.accept()) {
                        readHeaders(connection);
                        connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 99999999\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        connection.getOutputStream().flush();
                        return connection.getInputStream().read();
                    }
                });
                String origin = "http://127.0.0.1:" + server.getLocalPort();
                var config = properties(origin);
                var result = new DeliveryClient(HttpClient.newHttpClient(), new OriginPolicy(config), config).send(claim(origin));
                assertEquals(200, result.statusCode());
                assertEquals(-1, supplier.get(3, TimeUnit.SECONDS));
            } finally { server.close(); pool.shutdownNow(); }
        }
    }
    @Test void timeoutCancelsTheOpenConnection() throws Exception {
        try (var server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(2500);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var peerClosed = pool.submit(() -> {
                    try (var connection = server.accept()) { readHeaders(connection); return connection.getInputStream().read(); }
                });
                String origin = "http://127.0.0.1:" + server.getLocalPort(); var config = properties(origin);
                var result = new DeliveryClient(HttpClient.newHttpClient(), new OriginPolicy(config), config).send(claim(origin));
                assertEquals("http_timeout", result.reason());
                assertEquals(-1, peerClosed.get(3, TimeUnit.SECONDS));
            } finally { server.close(); pool.shutdownNow(); }
        }
    }
    @Test void interruptionCancelsTransportAndPreservesTheInterruptFlag() throws Exception {
        try (var server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(2500);
            var pool = Executors.newSingleThreadExecutor(); var entered = new CountDownLatch(1);
            var result = new AtomicReference<DeliveryResult>(); var interrupted = new AtomicBoolean();
            String origin = "http://127.0.0.1:" + server.getLocalPort(); var config = properties(origin);
            var delivery = new DeliveryClient(HttpClient.newHttpClient(), new OriginPolicy(config), config);
            var caller = new Thread(() -> { result.set(delivery.send(claim(origin))); interrupted.set(Thread.currentThread().isInterrupted()); });
            try {
                var peerClosed = pool.submit(() -> {
                    try (var connection = server.accept()) { readHeaders(connection); entered.countDown(); return connection.getInputStream().read(); }
                });
                caller.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
                caller.interrupt(); caller.join(2000);
                assertFalse(caller.isAlive()); assertTrue(interrupted.get());
                assertEquals("interrupted", result.get().reason());
                assertEquals(-1, peerClosed.get(3, TimeUnit.SECONDS));
            } finally { caller.interrupt(); caller.join(2000); server.close(); pool.shutdownNow(); }
        }
    }
    @Test void previouslyInterruptedCallerDoesNotStartTransport() {
        String origin = "http://127.0.0.1:19091"; var config = properties(origin);
        var transport = mock(HttpClient.class);
        when(transport.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
            .thenReturn(new CompletableFuture<>());
        try {
            Thread.currentThread().interrupt();
            var result = new DeliveryClient(transport, new OriginPolicy(config), config).send(claim(origin));
            assertEquals("interrupted", result.reason()); assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(transport);
        } finally { Thread.interrupted(); }
    }
    @Test void responseWinningCancellationRaceStillClosesItsBody() {
        String origin = "http://127.0.0.1:19091"; var config = properties(origin);
        var closed = new AtomicInteger();
        InputStream body = new ByteArrayInputStream(new byte[0]) {
            @Override public void close() { closed.incrementAndGet(); }
        };
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.body()).thenReturn(body); when(response.statusCode()).thenReturn(204);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        // Force completion exactly when cancellation is requested, making cancellation lose the race.
        var future = new CompletableFuture<HttpResponse<InputStream>>() {
            @Override public boolean cancel(boolean interrupt) { complete(response); return false; }
        };
        var transport = mock(HttpClient.class);
        when(transport.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(future);
        var result = new DeliveryClient(transport, new OriginPolicy(config), config).send(claim(origin));
        assertEquals("http_timeout", result.reason()); assertEquals(1, closed.get());
    }
}
