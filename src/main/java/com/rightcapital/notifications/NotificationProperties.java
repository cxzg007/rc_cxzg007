package com.rightcapital.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.*;

@ConfigurationProperties("notify")
public record NotificationProperties(Map<String, String> clients, List<String> allowedOrigins,
    boolean workerEnabled, int concurrency, int maxAttempts, Duration maxAge, Duration requestTimeout,
    Duration leaseDuration, Duration backoffBase, Duration backoffCap, Duration apiTimeout) {
    public NotificationProperties {
        var keys = new LinkedHashMap<String, String>();
        if (clients != null) clients.forEach((id, key) -> { if (key != null && !key.isBlank()) keys.put(id, key); });
        if (keys.isEmpty()) throw new IllegalArgumentException("Configure at least one notify.clients API key");
        if (new HashSet<>(keys.values()).size() != keys.size()) throw new IllegalArgumentException("Each client needs a distinct API key");
        clients = Map.copyOf(keys);
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream().filter(s -> !s.isBlank()).toList();
        if (allowedOrigins.isEmpty()) throw new IllegalArgumentException("Configure notify.allowed-origins");
        if (concurrency < 1 || concurrency > 64 || maxAttempts < 1 || maxAttempts > 1000)
            throw new IllegalArgumentException("Invalid concurrency or attempt budget");
        for (var d : List.of(maxAge, requestTimeout, leaseDuration, backoffBase, backoffCap, apiTimeout)) {
            if (d.isZero() || d.isNegative() || d.compareTo(Duration.ofDays(365)) > 0)
                throw new IllegalArgumentException("Durations must be positive and at most 365 days");
        }
        if (leaseDuration.compareTo(requestTimeout) <= 0 || backoffCap.compareTo(backoffBase) < 0)
            throw new IllegalArgumentException("Lease must exceed request timeout, cap must be at least base backoff");
    }
}
