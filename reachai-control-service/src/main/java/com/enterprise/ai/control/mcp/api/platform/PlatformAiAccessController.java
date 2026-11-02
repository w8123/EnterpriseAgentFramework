package com.enterprise.ai.control.mcp.api.platform;

import com.enterprise.ai.control.identity.PlatformAiCallRecords;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.mcp.application.platform.McpPlatformProtocolService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「AI 工具接入」 for a signed-in platform user: what their AI tools can do for them and
 * what those tools did. Always the caller's own data; connections live in /api/me/ai-connections.
 */
@RestController
@RequestMapping("/api/me/ai-access")
@RequiredArgsConstructor
public class PlatformAiAccessController {
    static final int DEFAULT_CALL_LIMIT = 200;

    private final McpPlatformProtocolService platform;
    private final PlatformAiCallRecords calls;
    private final PlatformRequestAuthorization authorization;

    @GetMapping("/capabilities")
    public ResponseEntity<List<McpPlatformProtocolService.Capability>> capabilities(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(platform.capabilities(authorization.requireAuthenticated(request)));
    }

    @GetMapping("/calls")
    public ResponseEntity<List<PlatformAiCallRecords.CallView>> calls(
            HttpServletRequest request, @RequestParam(defaultValue = "" + DEFAULT_CALL_LIMIT) int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(calls.recent(authorization.requireAuthenticated(request), limit));
    }
}
