package com.rightcapital.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class NotificationRepository {
    public record Job(UUID id, String clientId, String requestId, String fingerprint, String url, String method,
        Map<String,String> headers, String body, String status, int attempts, Instant createdAt, Instant updatedAt,
        Instant nextAttemptAt, UUID leaseToken, Instant leaseUntil, Integer lastStatusCode, String lastError, UUID replayOf) {}
    public record Submission(Job job, boolean created) {}
    public record Claim(Job job, UUID leaseToken, int attempt) {}

    private final DatabaseOperations database;
    private final JsonMapper json;
    private final NotificationProperties properties;
    public NotificationRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, JsonMapper json, NotificationProperties properties) {
        this.database = new DatabaseOperations(jdbc, manager, properties); this.json = json; this.properties = properties;
    }
    public Deadline newDeadline() {
        return Deadline.after(properties.apiTimeout()).reserving(DatabaseOperations.RESPONSE_RESERVE);
    }
    public Submission insert(String client, NotificationRequest request, String fingerprint, UUID replayOf, Instant now, Deadline deadline) {
        return database.execute(deadline, jdbc -> {
            var id = UUID.randomUUID();
            int added = jdbc.update("""
                INSERT INTO notifications(id,client_id,request_id,fingerprint,url,method,headers,body,status,created_at,updated_at,next_attempt_at,replay_of)
                VALUES(?,?,?,?,?,?,?::jsonb,?,'pending',?,?,?,?) ON CONFLICT(client_id,request_id) DO NOTHING
                """, id, client, request.requestId(), fingerprint, request.url(), request.method(), json.writeValueAsString(request.headers()),
                request.body(), stamp(now), stamp(now), stamp(now), replayOf);
            var stored = jdbc.query("SELECT * FROM notifications WHERE client_id=? AND request_id=?", this::map, client, request.requestId()).get(0);
            if (!stored.fingerprint().equals(fingerprint) || !Objects.equals(stored.replayOf(), replayOf))
                throw new ApiException(409, "request_id_conflict");
            return new Submission(stored, added == 1);
        });
    }
    public Job get(String client, UUID id) { return get(client, id, newDeadline()); }
    public Job get(String client, UUID id, Deadline deadline) {
        return database.execute(deadline, jdbc -> jdbc.query("SELECT * FROM notifications WHERE id=? AND client_id=?", this::map, id, client)
            .stream().findFirst().orElseThrow(() -> new ApiException(404, "notification_not_found")));
    }
    public List<Job> failed(String client, int limit, int offset, Deadline deadline) {
        return database.execute(deadline, jdbc -> jdbc.query("SELECT * FROM notifications WHERE client_id=? AND status='failed' ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            this::map, client, limit, offset));
    }
    public List<Map<String,Object>> attempts(UUID id) { return attempts(id, newDeadline()); }
    public List<Map<String,Object>> attempts(UUID id, Deadline deadline) {
        return database.execute(deadline, jdbc -> jdbc.query("SELECT number,started_at,finished_at,outcome,status_code,error FROM attempts WHERE notification_id=? ORDER BY number", (rs, row) -> {
            var view = new LinkedHashMap<String,Object>();
            view.put("number", rs.getInt("number")); view.put("started_at", instant(rs, "started_at"));
            view.put("finished_at", instant(rs, "finished_at")); view.put("outcome", rs.getString("outcome"));
            view.put("status_code", rs.getObject("status_code")); view.put("error", rs.getString("error")); return view;
        }, id));
    }

    public Optional<Claim> claim(Instant now) {
        return database.execute(newDeadline(), jdbc -> {
            var candidates = jdbc.query("""
                SELECT * FROM notifications
                WHERE (status='pending' AND next_attempt_at<=?) OR (status='processing' AND lease_until<=?)
                ORDER BY COALESCE(next_attempt_at,lease_until),id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, this::map, stamp(now), stamp(now));
            if (candidates.isEmpty()) return Optional.empty();
            var job = candidates.get(0);
            if (job.status().equals("processing")) {
                jdbc.update("""
                    UPDATE attempts SET finished_at=?,outcome='unknown',error='lease_expired'
                    WHERE notification_id=? AND lease_token=? AND outcome='processing'
                    """, stamp(now), job.id(), job.leaseToken());
            }
            String budget = job.attempts() >= properties.maxAttempts() ? "attempt_budget_exhausted"
                : (!now.isBefore(job.createdAt().plus(properties.maxAge())) ? "age_budget_exhausted" : null);
            if (budget != null) {
                jdbc.update("""
                    UPDATE notifications SET status='failed',last_error=?,updated_at=?,next_attempt_at=NULL,lease_token=NULL,lease_until=NULL WHERE id=?
                    """, budget, stamp(now), job.id());
                return Optional.empty();
            }
            var token = UUID.randomUUID(); int attempt = job.attempts() + 1;
            jdbc.update("""
                UPDATE notifications SET status='processing',attempts=?,lease_token=?,lease_until=?,next_attempt_at=NULL,updated_at=? WHERE id=?
                """, attempt, token, stamp(now.plus(properties.leaseDuration())), stamp(now), job.id());
            jdbc.update("INSERT INTO attempts(notification_id,number,lease_token,started_at,outcome) VALUES(?,?,?,?,'processing')",
                job.id(), attempt, token, stamp(now));
            return Optional.of(new Claim(job, token, attempt));
        });
    }
    public boolean complete(Claim claim, DeliveryResult result, RetryPolicy.Decision decision, Instant now) {
        return Boolean.TRUE.equals(database.execute(newDeadline(), jdbc -> {
            String status = decision.success() ? "succeeded" : decision.retryAt() == null ? "failed" : "pending";
            // Updating by token fences database writes only; it cannot retract a sent HTTP request.
            int changed = jdbc.update("""
                UPDATE notifications SET status=?,updated_at=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,last_status_code=?,last_error=?
                WHERE id=? AND status='processing' AND lease_token=?
                """, status, stamp(now), stamp(decision.retryAt()), result.statusCode(), decision.reason(), claim.job().id(), claim.leaseToken());
            if (changed == 0) return false;
            String outcome = decision.success() ? "succeeded" : decision.retryAt() == null ? "failed" : "retry";
            jdbc.update("""
                UPDATE attempts SET finished_at=?,outcome=?,status_code=?,error=? WHERE notification_id=? AND lease_token=?
                """, stamp(now), outcome, result.statusCode(), decision.success() ? null : result.reason(), claim.job().id(), claim.leaseToken());
            return true;
        }));
    }

    @SuppressWarnings("unchecked")
    private Job map(ResultSet rs, int row) throws SQLException {
        return new Job(rs.getObject("id", UUID.class), rs.getString("client_id"), rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getString("url"), rs.getString("method"), json.readValue(rs.getString("headers"), Map.class), rs.getString("body"),
            rs.getString("status"), rs.getInt("attempts"), instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "next_attempt_at"),
            rs.getObject("lease_token", UUID.class), instant(rs, "lease_until"), rs.getObject("last_status_code", Integer.class),
            rs.getString("last_error"), rs.getObject("replay_of", UUID.class));
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var timestamp = rs.getTimestamp(name); return timestamp == null ? null : timestamp.toInstant();
    }
    private static Timestamp stamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
}
