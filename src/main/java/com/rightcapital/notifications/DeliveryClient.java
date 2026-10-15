package com.rightcapital.notifications;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import java.security.cert.CertificateException;
import java.security.cert.CertPathValidatorException;
import java.util.Locale;

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
        try {
            var job = claim.job();
            var builder = HttpRequest.newBuilder(origins.validate(job.url())).timeout(properties.requestTimeout())
                .method(job.method(), HttpRequest.BodyPublishers.ofString(job.body(), StandardCharsets.UTF_8));
            job.headers().forEach(builder::header);
            // We need the response status, not an unbounded supplier response body.
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                int status = response.statusCode();
                return new DeliveryResult(status, response.headers().firstValue("Retry-After").orElse(null), "http_" + status, RETRYABLE.contains(status));
            }
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
    private boolean isTlsValidationFailure(SSLException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CertificateException || cause instanceof CertPathValidatorException
                || cause instanceof SSLPeerUnverifiedException || cause instanceof SSLProtocolException) return true;
        }
        String detail = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        // JSSE exposes remote TLS alert names in its exception, without a certificate cause.
        return Set.of("bad_certificate", "unsupported_certificate", "certificate_revoked", "certificate_expired",
            "certificate_unknown", "unknown_ca", "certificate_required", "protocol_version", "handshake_failure",
            "insufficient_security", "no_application_protocol", "unrecognized_name", "no appropriate protocol")
            .stream().anyMatch(detail::contains);
    }
}
