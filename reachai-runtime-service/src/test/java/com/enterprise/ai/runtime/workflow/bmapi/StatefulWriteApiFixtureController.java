package com.enterprise.ai.runtime.workflow.bmapi;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Actual isolated HTTP business state. No mock response stands in for a POST. */
@RestController
class StatefulWriteApiFixtureController {
    static final String PROJECT_SECRET = "synthetic-3b4-project-credential";
    static final String SENSITIVE_INPUT = "synthetic-3b4-sensitive-input";
    final AtomicInteger postHits = new AtomicInteger();
    final AtomicInteger authenticatedHits = new AtomicInteger();
    final AtomicInteger redirectedHits = new AtomicInteger();
    final AtomicReference<String> responseMode = new AtomicReference<>("SUCCESS");
    final AtomicReference<Map<String, Object>> lastBody = new AtomicReference<>();
    final AtomicReference<String> lastRawBody = new AtomicReference<>();
    final AtomicReference<String> lastContentType = new AtomicReference<>();
    final AtomicReference<Runnable> afterAppend = new AtomicReference<>(() -> { });
    private final CopyOnWriteArrayList<String> notes = new CopyOnWriteArrayList<>();
    int noteCount() { return notes.size(); }

    @PostMapping(value = "/orders/{orderId}/touch", produces = "application/json")
    ResponseEntity<Object> touch(@PathVariable String orderId, @RequestBody(required = false) String rawBody,
                                 @RequestHeader(name = "Content-Type", required = false) String contentType,
                                 @RequestHeader(name = "X-API-Key", required = false) String key) throws Exception {
        postHits.incrementAndGet();
        if (!PROJECT_SECRET.equals(key)) return ResponseEntity.status(401).body(Map.of("error", "credential required"));
        authenticatedHits.incrementAndGet();
        lastRawBody.set(rawBody);
        lastContentType.set(contentType);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = rawBody == null ? null : new com.fasterxml.jackson.databind.ObjectMapper().readValue(rawBody, Map.class);
        lastBody.set(body);
        notes.add(orderId + ":TOUCHED");
        return ResponseEntity.ok(Map.of("state", "TOUCHED", "noteCount", noteCount(), "bodyPresent", body != null));
    }

    @PostMapping(value = "/orders/{orderId}/notes", consumes = "application/json", produces = "application/json")
    ResponseEntity<Object> append(@PathVariable String orderId, @RequestBody Map<String, Object> body,
                                  @RequestHeader(name = "X-API-Key", required = false) String key) {
        postHits.incrementAndGet();
        if (!PROJECT_SECRET.equals(key)) return ResponseEntity.status(401).body(Map.of("error", "credential required"));
        authenticatedHits.incrementAndGet();
        lastBody.set(new LinkedHashMap<>(body));
        if (!(body.get("note") instanceof String note) || !(body.get("apiKey") instanceof String)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid actual input"));
        }
        if ("BUSINESS_FAILED".equals(responseMode.get())) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "order does not accept a note"));
        }
        notes.add(orderId + ":" + note);
        afterAppend.getAndSet(() -> { }).run();
        String mode = responseMode.get();
        if ("TIMEOUT".equals(mode)) {
            try { Thread.sleep(31_000L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if ("307".equals(mode)) return ResponseEntity.status(307).location(URI.create("/orders/redirected-notes"))
                .body(Map.of("noteCount", noteCount()));
        if ("503".equals(mode)) return ResponseEntity.status(503).body(Map.of("noteCount", noteCount()));
        if ("401".equals(mode)) return ResponseEntity.status(401).body(Map.of("noteCount", noteCount()));
        if ("INVALID_JSON".equals(mode)) return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{");
        var result = new LinkedHashMap<String, Object>(Map.of("orderId", orderId, "noteCount", noteCount(), "note", note,
                "apiKey", body.get("apiKey"), "echo", body.get("apiKey"), "credentialEcho", key,
                "notify", body.getOrDefault("notify", "ABSENT"), "priority", body.getOrDefault("priority", "ABSENT")));
        result.put("state", "NOTED");
        if (body.containsKey("accessCode")) result.put("receivedA", body.get("accessCode"));
        if (body.containsKey("deliveryMark")) result.put("receivedB", body.get("deliveryMark"));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/orders/redirected-notes")
    Map<String, Object> redirected(@RequestBody Map<String, Object> body) {
        redirectedHits.incrementAndGet(); notes.add("redirected");
        return Map.of("noteCount", noteCount());
    }
}
