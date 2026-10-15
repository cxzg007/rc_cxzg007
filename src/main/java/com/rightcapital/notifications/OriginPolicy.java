package com.rightcapital.notifications;

import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Component
public final class OriginPolicy {
    private final Set<String> allowed;
    public OriginPolicy(NotificationProperties properties) {
        allowed = properties.allowedOrigins().stream().map(value -> {
            var uri = parse(value);
            if (uri.getRawQuery() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")))
                throw new IllegalArgumentException("Allowlist entries must be origins without path or query");
            return origin(uri);
        }).collect(Collectors.toUnmodifiableSet());
    }
    public URI validate(String value) {
        var uri = parse(value);
        if (!allowed.contains(origin(uri))) throw new ApiException(400, "target_not_allowed");
        return uri;
    }
    private URI parse(String value) {
        try {
            if (value == null || value.length() > 4096) throw new IllegalArgumentException();
            var uri = URI.create(value);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException ex) { throw new ApiException(400, "invalid_target_url"); }
    }
    private String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
