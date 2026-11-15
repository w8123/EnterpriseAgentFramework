package com.enterprise.ai.runtime.execution.http;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Intranet host allowlist; Control checks that the console user is a platform administrator before proxying. */
@RestController
@RequiredArgsConstructor
public class RuntimeHttpEgressHostController {

    private final RuntimeHttpEgressHostService service;
    private final WorkflowHttpEgressPolicy policy;

    @GetMapping("/api/platform/http-egress-hosts")
    public Map<String, Object> list() {
        return Map.of("hosts", service.list(), "allowPrivateNetworks", policy.allowsPrivateNetworks(),
                "environmentHosts", List.copyOf(policy.environmentAllowlist()));
    }

    /** For other platform services: every host an administrator allowed (console list and environment). */
    @GetMapping("/internal/runtime/http-egress-hosts")
    public List<String> allowedHosts() {
        java.util.Set<String> hosts = new java.util.TreeSet<>(policy.environmentAllowlist());
        hosts.addAll(service.hosts());
        return List.copyOf(hosts);
    }

    @PostMapping("/api/platform/http-egress-hosts")
    public RuntimeHttpEgressHostEntity add(@RequestBody Map<String, Object> body) {
        return service.add(text(body.get("host")), text(body.get("note")), text(body.get("createdBy")));
    }

    @DeleteMapping("/api/platform/http-egress-hosts/{id}")
    public Map<String, Object> remove(@PathVariable Long id) {
        service.remove(id);
        return Map.of("removed", true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest().body(Map.of("code", "HTTP_EGRESS_HOST_INVALID",
                "message", String.valueOf(invalid.getMessage())));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
