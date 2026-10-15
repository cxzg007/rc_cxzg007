package com.rightcapital.notifications;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryTlsTest {
    @TempDir static Path directory;
    private static SSLContext trustedContext;

    @BeforeAll static void certificate() throws Exception {
        Path store = directory.resolve("supplier.p12");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "supplier", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1", "-storetype", "PKCS12", "-keystore", store.toString(),
            "-storepass", "test-password", "-noprompt").redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "test-password".toCharArray()); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, "test-password".toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
        trustedContext = SSLContext.getInstance("TLS"); trustedContext.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    }
    private NotificationProperties properties(String origin) {
        return new NotificationProperties(Map.of("business", "key"), List.of(origin), false, 1, 20, Duration.ofHours(24),
            Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofHours(1));
    }
    private NotificationRepository.Claim claim(String origin) {
        var now = Instant.now();
        var job = new NotificationRepository.Job(UUID.randomUUID(), "business", "tls-event", "", origin + "/", "GET", Map.of(), "", "pending", 0,
            now, now, now, null, null, null, null, null);
        return new NotificationRepository.Claim(job, UUID.randomUUID(), 1);
    }
    @Test void transientTlsAlertCanBeRetriedAndThenSucceedWithCertificateValidation() throws Exception {
        try (var server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            var pool = Executors.newSingleThreadExecutor();
            try {
                var task = pool.submit(() -> {
                    try {
                        try (var first = server.accept()) {
                            first.setSoTimeout(3000);
                            byte[] header = first.getInputStream().readNBytes(5);
                            int length = (Byte.toUnsignedInt(header[3]) << 8) | Byte.toUnsignedInt(header[4]);
                            first.getInputStream().readNBytes(length);
                            first.getOutputStream().write(new byte[]{21, 3, 3, 0, 2, 2, 80}); // TLS fatal internal_error
                            first.getOutputStream().flush();
                        }
                        try (var raw = server.accept(); var tls = (SSLSocket) trustedContext.getSocketFactory().createSocket(raw, "localhost", raw.getPort(), true)) {
                            tls.setUseClientMode(false); tls.setSoTimeout(3000); tls.startHandshake();
                            var reader = new BufferedReader(new InputStreamReader(tls.getInputStream(), StandardCharsets.US_ASCII));
                            String line; while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                            tls.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                            tls.getOutputStream().flush();
                        }
                    } catch (IOException ex) { throw new UncheckedIOException(ex); }
                });
                String origin = "https://127.0.0.1:" + server.getLocalPort();
                var config = properties(origin);
                var delivery = new DeliveryClient(HttpClient.newBuilder().sslContext(trustedContext).build(), new OriginPolicy(config), config);
                var first = delivery.send(claim(origin));
                assertTrue(first.retryable(), first.toString()); assertNull(first.statusCode());
                assertEquals(204, delivery.send(claim(origin)).statusCode());
                task.get(5, TimeUnit.SECONDS);
            } finally { server.close(); pool.shutdownNow(); }
        }
    }
    @Test void untrustedCertificateIsPermanentAndNeverBypassed() throws Exception {
        try (var server = (SSLServerSocket) trustedContext.getServerSocketFactory().createServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            var pool = Executors.newSingleThreadExecutor();
            try {
                var task = pool.submit(() -> {
                    try (var connection = (SSLSocket) server.accept()) { connection.setSoTimeout(3000); connection.startHandshake(); }
                    catch (IOException expected) { /* untrusted server certificate ends the handshake */ }
                });
                String origin = "https://127.0.0.1:" + server.getLocalPort();
                var config = properties(origin);
                var result = new DeliveryClient(HttpClient.newHttpClient(), new OriginPolicy(config), config).send(claim(origin));
                assertFalse(result.retryable()); assertNull(result.statusCode()); assertTrue(result.reason().startsWith("tls_"));
                task.get(5, TimeUnit.SECONDS);
            } finally { server.close(); pool.shutdownNow(); }
        }
    }
}
