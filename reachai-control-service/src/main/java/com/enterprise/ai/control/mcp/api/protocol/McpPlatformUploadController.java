package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.mcp.application.platform.McpPlatformProtocolService;
import com.enterprise.ai.control.mcp.application.platform.McpPlatformUploadStore;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

/**
 * Out-of-band file upload for platform AI connections: the raw file body with the connection's own Bearer token. The
 * returned uploadId is what knowledge import and Skill package tools take, since files do not fit the MCP body cap.
 */
@RestController
@RequiredArgsConstructor
public class McpPlatformUploadController {
    private final McpProtocolRequestGuard guard;
    private final McpPlatformProtocolService platformService;
    private final McpPlatformUploadStore uploads;

    @PostMapping("/mcp/platform/uploads")
    public ResponseEntity<?> upload(HttpServletRequest request,
                                    @RequestParam("fileName") String fileName,
                                    @RequestHeader(value = "Content-Length", required = false) Long declaredBytes) {
        String token = guard.bearerToken(request);
        if (token == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            var connection = platformService.authenticate(token);
            if (declaredBytes != null && declaredBytes > McpPlatformUploadStore.MAX_BYTES) {
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("code", "PLATFORM_AI_UPLOAD_TOO_LARGE"));
            }
            return ResponseEntity.ok(uploads.put(connection, fileName, request.getInputStream()).view());
        } catch (McpDomainException unauthorized) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (ResponseStatusException refused) {
            return ResponseEntity.status(refused.getStatusCode()).build();
        } catch (IOException lost) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }
}
