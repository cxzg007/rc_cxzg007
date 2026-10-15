package com.rightcapital.notifications;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://127.0.0.1:15432/notification_test}",
    "spring.datasource.username=${TEST_DB_USER:postgres}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "notify.worker-enabled=false", "notify.clients.business=", "notify.clients.client-a=key-a",
    "notify.clients.client-b=key-b", "notify.allowed-origins=http://127.0.0.1:19091"})
class NotificationApiIT {
    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void clean() { jdbc.execute("TRUNCATE attempts, notifications CASCADE"); }

    HttpResponse<String> call(String method, String path, Object body, String key) throws Exception {
        return callRaw(method, path, body == null ? null : json.writeValueAsString(body), key);
    }
    HttpResponse<String> callRaw(String method, String path, String body, String key) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + env.getProperty("local.server.port") + path))
            .header("X-API-Key", key).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    Map<String,Object> request(String key, String body) {
        return Map.of("request_id", key, "url", "http://127.0.0.1:19091/hook", "method", "POST",
            "headers", Map.of("Content-Type", "application/json", "Authorization", "Bearer vendor-secret"), "body", body);
    }
    JsonNode tree(HttpResponse<String> r) { return json.readTree(r.body()); }

    @Test void acceptanceIsIdempotentAndConflictingPayloadIsRejected() throws Exception {
        var first = call("POST", "/notifications", request("event-1", "secret-body"), "key-a");
        assertEquals(202, first.statusCode(), first.body());
        String id = tree(first).get("id").asText();
        var repeat = call("POST", "/notifications", request("event-1", "secret-body"), "key-a");
        assertEquals(200, repeat.statusCode()); assertEquals(id, tree(repeat).get("id").asText());
        assertEquals(409, call("POST", "/notifications", request("event-1", "changed"), "key-a").statusCode());
        var get = call("GET", "/notifications/" + id, null, "key-a");
        assertEquals(200, get.statusCode()); assertEquals("pending", tree(get).get("status").asText());
        assertFalse(get.body().contains("vendor-secret")); assertFalse(get.body().contains("secret-body"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class));
    }
    @Test void isolatesClientsAndAuthenticatesAllApiRequests() throws Exception {
        assertEquals(401, call("POST", "/notifications", request("a", ""), "invalid").statusCode());
        var accepted = call("POST", "/notifications", request("a", ""), "key-a");
        assertEquals(202, accepted.statusCode());
        String id = tree(accepted).get("id").asText();
        assertEquals(404, call("GET", "/notifications/" + id, null, "key-b").statusCode());
        assertEquals(404, call("POST", "/notifications/" + id + "/replay", Map.of("request_id", "b"), "key-b").statusCode());
        assertEquals(202, call("POST", "/notifications", request("a", ""), "key-b").statusCode());
    }
    @Test void validatesTargetHeadersAndRequestSize() throws Exception {
        var req = new HashMap<>(request("a", ""));
        req.put("url", "http://127.0.0.1:19092/hook");
        assertEquals(400, call("POST", "/notifications", req, "key-a").statusCode());
        req.put("url", "http://evil@127.0.0.1:19091/hook");
        assertEquals(400, call("POST", "/notifications", req, "key-a").statusCode());
        req.put("url", "http://127.0.0.1:19091/hook"); req.put("headers", Map.of("Host", "other"));
        assertEquals(400, call("POST", "/notifications", req, "key-a").statusCode());
        assertEquals(413, call("POST", "/notifications", request("big", "x".repeat(2 * 1024 * 1024)), "key-a").statusCode());
    }
    @Test void concurrentSubmissionsCreateExactlyOneTask() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var jobs = new ArrayList<Callable<HttpResponse<String>>>();
            for (int i = 0; i < 8; i++) jobs.add(() -> call("POST", "/notifications", request("race", "same"), "key-a"));
            var ids = new HashSet<String>(); int created = 0;
            for (var result : pool.invokeAll(jobs)) {
                var r = result.get(); assertTrue(r.statusCode() == 200 || r.statusCode() == 202, r.body());
                if (r.statusCode() == 202) created++;
                ids.add(tree(r).get("id").asText());
            }
            assertEquals(1, created); assertEquals(1, ids.size());
        } finally { pool.shutdownNow(); }
    }
    @Test void replayOnlyFailedTaskAndKeepHistory() throws Exception {
        var first = call("POST", "/notifications", request("original", "body"), "key-a");
        assertEquals(202, first.statusCode()); String id = tree(first).get("id").asText();
        assertEquals(409, call("POST", "/notifications/" + id + "/replay", Map.of("request_id", "replay"), "key-a").statusCode());
        jdbc.update("UPDATE notifications SET status='failed', last_error='http_400' WHERE id=?::uuid", id);
        var replay = call("POST", "/notifications/" + id + "/replay", Map.of("request_id", "replay"), "key-a");
        assertEquals(202, replay.statusCode(), replay.body());
        assertEquals(id, tree(replay).get("replay_of").asText());
        var repeated = call("POST", "/notifications/" + id + "/replay", Map.of("request_id", "replay"), "key-a");
        assertEquals(200, repeated.statusCode());
        assertEquals(tree(replay).get("id"), tree(repeated).get("id"));
        var failed = call("GET", "/notifications?status=failed", null, "key-a");
        assertEquals(200, failed.statusCode()); assertEquals(1, tree(failed).get("items").size());
    }
    @Test void databaseRejectingInsertNeverReturnsAccepted() throws Exception {
        jdbc.execute("ALTER TABLE notifications ADD CONSTRAINT reject_test CHECK (client_id <> 'client-a') NOT VALID");
        try {
            assertEquals(503, call("POST", "/notifications", request("db-failure", ""), "key-a").statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class));
        } finally { jdbc.execute("ALTER TABLE notifications DROP CONSTRAINT reject_test"); }
    }
    @Test void rejectsTextThatCannotBeRepresentedAsUtf8WithoutReplacement() throws Exception {
        String invalid = json.writeValueAsString(request("invalid-unicode", "PLACEHOLDER")).replace("PLACEHOLDER", "\\" + "ud800");
        assertEquals(400, callRaw("POST", "/notifications", invalid, "key-a").statusCode());
    }
    @Test void acceptsMaximumBodyEvenWhenJsonEscapingExpandsIt() throws Exception {
        var large = request("escaped-max", String.valueOf((char) 1).repeat(1024 * 1024));
        assertEquals(202, call("POST", "/notifications", large, "key-a").statusCode());
    }
    @Test void concurrentReplayIsIdempotentAndCannotReuseKeyForAnotherSource() throws Exception {
        String first = tree(call("POST", "/notifications", request("source-1", "same"), "key-a")).get("id").asText();
        String second = tree(call("POST", "/notifications", request("source-2", "same"), "key-a")).get("id").asText();
        jdbc.update("UPDATE notifications SET status='failed'");
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<HttpResponse<String>>>();
            for (int i=0; i<4; i++) tasks.add(() -> call("POST", "/notifications/" + first + "/replay", Map.of("request_id", "shared-replay"), "key-a"));
            int created = 0; var ids = new HashSet<String>();
            for (var f : pool.invokeAll(tasks)) {
                var result = f.get(); assertTrue(result.statusCode() == 200 || result.statusCode() == 202);
                if (result.statusCode() == 202) created++;
                ids.add(tree(result).get("id").asText());
            }
            assertEquals(1, created); assertEquals(1, ids.size());
            assertEquals(409, call("POST", "/notifications/" + second + "/replay", Map.of("request_id", "shared-replay"), "key-a").statusCode());
        } finally { pool.shutdownNow(); }
    }
}
