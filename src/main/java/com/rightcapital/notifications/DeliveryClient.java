package com.rightcapital.notifications;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import java.security.cert.CertificateException;
import java.security.cert.CertPathValidatorException;
import java.util.Locale;
import java.util.concurrent.*;

@Component
public class DeliveryClient {
    private static final Set<Integer> RETRYABLE = Set.of(408, 429, 500, 502, 503, 504);
    private final HttpClient client;
    private final OriginPolicy origins;
    private final NotificationProperties properties;
    public DeliveryClient(HttpClient client, OriginPolicy origins, NotificationProperties properties) {
        this.client = client; this.origins = origins; this.properties = properties;
    }
    public DeliveryResult send(NotificationRepository.Claim claim) {
        long started = System.nanoTime();
        try {
            var job = claim.job();
            var builder = HttpRequest.newBuilder(origins.validate(job.url())).timeout(properties.requestTimeout())
                .method(job.method(), HttpRequest.BodyPublishers.ofString(job.body(), StandardCharsets.UTF_8));
            job.headers().forEach(builder::header);
            return sendWithinDeadline(builder.build(), started);
        } catch (HttpTimeoutException ex) {
            return new DeliveryResult(null, null, "http_timeout", true);
        } catch (SSLException ex) {
            boolean permanent = isTlsValidationFailure(ex);
            return new DeliveryResult(null, null, permanent ? "tls_validation_error" : "tls_transport_error", !permanent);
        } catch (IOException ex) {
            return new DeliveryResult(null, null, "io_error", true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new DeliveryResult(null, null, "interrupted", true);
        } catch (ApiException | IllegalArgumentException ex) {
            return new DeliveryResult(null, null, "invalid_outbound_request", false);
        }
    }
    private DeliveryResult sendWithinDeadline(HttpRequest request, long started) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        long budget = properties.requestTimeout().toNanos();
        if (System.nanoTime() - started >= budget) throw new HttpTimeoutException("Delivery deadline exceeded");
        var transport = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        // Keep this stage alive when cancelling transport: a response that wins the cancellation race
        // must still close its body. Only status and headers are needed, never the supplier body.
        var result = transport.thenApply(response -> {
            try (var body = response.body()) {
                int status = response.statusCode();
                return new DeliveryResult(status, response.headers().firstValue("Retry-After").orElse(null),
                    "http_" + status, RETRYABLE.contains(status));
            } catch (IOException ex) { throw new UncheckedIOException(ex); }
        });
        try {
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            return result.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            transport.cancel(true);
            throw new HttpTimeoutException("Delivery deadline exceeded");
        } catch (InterruptedException ex) {
            transport.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            while (cause instanceof CompletionException || cause instanceof UncheckedIOException) cause = cause.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Delivery failed", cause);
        }
    }
    private boolean isTlsValidationFailure(SSLException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CertificateException || cause instanceof CertPathValidatorException
                || cause instanceof SSLPeerUnverifiedException || cause instanceof SSLProtocolException) return true;
        }
        String detail = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        // JSSE exposes remote TLS alert names in its exception, without a certificate cause.
        return Set.of("bad_certificate", "unsupported_certificate", "certificate_revoked", "certificate_expired",
            "certificate_unknown", "unknown_ca", "certificate_required", "protocol_version", "handshake_failure",
            "insufficient_security", "no_application_protocol", "unrecognized_name", "no appropriate protocol",
            "illegal_parameter", "access_denied", "decode_error", "decrypt_error", "missing_extension", "unsupported_extension")
            .stream().anyMatch(detail::contains);
    }
}
