package com.rightcapital.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/notifications")
public class NotificationController {
    public record ReplayRequest(@JsonProperty("request_id") String requestId) {}
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service = service; }
    @PostMapping public ResponseEntity<?> submit(@RequestAttribute("clientId") String client, @RequestAttribute("deadline") Deadline deadline, @RequestBody NotificationRequest request) {
        return response(service.submit(client, request, deadline));
    }
    @GetMapping("/{id}") public Map<String,Object> get(@RequestAttribute("clientId") String client, @RequestAttribute("deadline") Deadline deadline, @PathVariable UUID id) { return service.get(client, id, deadline); }
    @GetMapping public Map<String,Object> list(@RequestAttribute("clientId") String client, @RequestAttribute("deadline") Deadline deadline,
        @RequestParam(defaultValue="failed") String status, @RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="0") int offset) {
        if (!status.equals("failed")) throw new ApiException(400, "only_failed_filter_supported");
        return Map.of("items", service.failed(client, limit, offset, deadline), "limit", limit, "offset", offset);
    }
    @PostMapping("/{id}/replay") public ResponseEntity<?> replay(@RequestAttribute("clientId") String client, @RequestAttribute("deadline") Deadline deadline, @PathVariable UUID id, @RequestBody ReplayRequest request) {
        return response(service.replay(client, id, request.requestId(), deadline));
    }
    private ResponseEntity<?> response(NotificationRepository.Submission result) {
        return ResponseEntity.status(result.created() ? 202 : 200).location(URI.create("/notifications/" + result.job().id())).body(service.view(result.job()));
    }
}
