package com.rightcapital.notifications;

import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;

@Service
public class NotificationService {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> RESERVED = Set.of("host", "content-length", "connection", "transfer-encoding", "upgrade", "expect",
        "te", "trailer", "keep-alive", "proxy-authorization", "proxy-authenticate", "http2-settings");
    private final NotificationRepository repository;
    private final OriginPolicy origins;
    private final JsonMapper json;
    private final Clock clock;
    public NotificationService(NotificationRepository repository, OriginPolicy origins, JsonMapper json, Clock clock) {
        this.repository = repository; this.origins = origins; this.json = json; this.clock = clock;
    }
    public NotificationRepository.Submission submit(String client, NotificationRequest request) {
        return submit(client, request, repository.newDeadline());
    }
    public NotificationRepository.Submission submit(String client, NotificationRequest request, Deadline deadline) {
        var normalized = normalize(request);
        return repository.insert(client, normalized, fingerprint(normalized), null, clock.instant(), deadline);
    }
    public Map<String,Object> get(String client, UUID id, Deadline deadline) {
        var result = view(repository.get(client, id, deadline)); result.put("history", repository.attempts(id, deadline)); return result;
    }
    public List<Map<String,Object>> failed(String client, int limit, int offset, Deadline deadline) {
        if (limit < 1 || limit > 100 || offset < 0) throw new ApiException(400, "invalid_pagination");
        return repository.failed(client, limit, offset, deadline).stream().map(this::view).toList();
    }
    public NotificationRepository.Submission replay(String client, UUID id, String requestId, Deadline deadline) {
        var original = repository.get(client, id, deadline);
        if (!original.status().equals("failed")) throw new ApiException(409, "only_failed_tasks_can_be_replayed");
        var request = normalize(new NotificationRequest(requestId, original.url(), original.method(), original.headers(), original.body()));
        return repository.insert(client, request, fingerprint(request), original.id(), clock.instant(), deadline);
    }
    public Map<String,Object> view(NotificationRepository.Job job) {
        var view = new LinkedHashMap<String,Object>();
        view.put("id", job.id()); view.put("request_id", job.requestId()); view.put("status", job.status());
        view.put("attempts", job.attempts()); view.put("created_at", job.createdAt()); view.put("updated_at", job.updatedAt());
        view.put("next_attempt_at", job.nextAttemptAt()); view.put("last_status_code", job.lastStatusCode());
        view.put("last_error", job.lastError()); view.put("replay_of", job.replayOf()); return view;
    }
    private NotificationRequest normalize(NotificationRequest r) {
        if (r == null || r.requestId() == null || !r.requestId().matches("[A-Za-z0-9._:-]{1,128}"))
            throw new ApiException(400, "invalid_request_id");
        origins.validate(r.url());
        if (r.method() == null || !METHODS.contains(r.method())) throw new ApiException(400, "unsupported_method");
        String body = r.body() == null ? "" : r.body();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(body)) throw new ApiException(400, "invalid_utf8_body");
        if (body.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) throw new ApiException(413, "body_too_large");
        if (body.indexOf('\0') >= 0) throw new ApiException(400, "body_contains_null_character");
        var headers = new TreeMap<String,String>();
        int bytes = 0;
        if (r.headers() != null) for (var entry : r.headers().entrySet()) {
            String name = entry.getKey(); String value = entry.getValue();
            if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value == null)
                throw new ApiException(400, "invalid_header");
            String lower = name.toLowerCase(Locale.ROOT);
            if (RESERVED.contains(lower) || headers.containsKey(lower)) throw new ApiException(400, "reserved_or_duplicate_header");
            if (value.chars().anyMatch(ch -> ch != '\t' && (ch < 32 || ch > 126))) throw new ApiException(400, "invalid_header_value");
            bytes += name.length() + value.length(); headers.put(lower, value);
        }
        if (headers.size() > 64 || bytes > 16384) throw new ApiException(400, "headers_too_large");
        return new NotificationRequest(r.requestId(), r.url(), r.method(), Collections.unmodifiableMap(headers), body);
    }
    private String fingerprint(NotificationRequest r) {
        try {
            byte[] bytes = json.writeValueAsBytes(List.of(r.url(), r.method(), r.headers(), r.body()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
