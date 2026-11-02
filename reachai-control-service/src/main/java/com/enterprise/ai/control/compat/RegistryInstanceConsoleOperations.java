package com.enterprise.ai.control.compat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;

/** In-process access to the console's registered-instance list and instance operations for trusted platform adapters. */
@Component
@RequiredArgsConstructor
public class RegistryInstanceConsoleOperations {
    private final RegistryCompatibilityController registry;
    private final CapabilityCompatibilityProxyController proxy;
    private final ObjectMapper json;

    public ResponseEntity<?> list(String projectCode) {
        return registry.listInstances(projectCode);
    }

    /** {@code operation} is {@code status} or {@code purge-offline}; the bridge only carries those two console paths. */
    public ResponseEntity<?> change(HttpServletRequest request, String projectCode, String operation, Map<String, Object> body) {
        String uri = "/api/registry/projects/" + projectCode + "/instances/" + operation;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        byte[] payload;
        try { payload = json.writeValueAsBytes(body == null ? Map.of() : body); }
        catch (Exception invalid) { throw new IllegalArgumentException("body is invalid"); }
        ResponseEntity<byte[]> response = proxy.proxyRegistry(new RequestEntity<>(payload, headers, HttpMethod.POST, URI.create(uri)),
                new HttpServletRequestWrapper(request) {
                    @Override public String getRequestURI() { return uri; }
                    @Override public String getMethod() { return "POST"; }
                });
        if (response.getBody() == null || response.getBody().length == 0) return ResponseEntity.status(response.getStatusCode()).build();
        try { return ResponseEntity.status(response.getStatusCode()).body(json.readTree(response.getBody())); }
        catch (Exception invalid) {
            return response.getStatusCode().is2xxSuccessful() ? ResponseEntity.status(502).build() : ResponseEntity.status(response.getStatusCode()).build();
        }
    }
}
