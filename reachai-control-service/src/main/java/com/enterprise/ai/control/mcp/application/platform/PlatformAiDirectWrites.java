package com.enterprise.ai.control.mcp.application.platform;

import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Runs a direct AI write exactly once: definite refusals pass through, a lost or failed result is reported as unknown. */
final class PlatformAiDirectWrites {
    private PlatformAiDirectWrites() { }

    static ResponseEntity<?> once(Supplier<ResponseEntity<?>> call, String message) {
        ResponseEntity<?> response;
        try { response = call.get(); }
        catch (ResponseStatusException definite) {
            if (definite.getStatusCode().is4xxClientError()) throw definite;
            return unknown(message);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException lost) {
            return unknown(message);
        }
        return response == null || response.getStatusCode().is5xxServerError() ? unknown(message) : response;
    }

    static ResponseEntity<?> unknown(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("message", message);
        return ResponseEntity.accepted().body(body);
    }
}
