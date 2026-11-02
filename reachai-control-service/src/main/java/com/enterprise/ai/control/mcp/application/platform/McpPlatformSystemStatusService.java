package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSystemStatusPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/** Read-only installation status. Changing login methods stays with administrators on the console. */
@Service
@RequiredArgsConstructor
public class McpPlatformSystemStatusService {
    private final McpPlatformSystemStatusPort status;

    public ResponseEntity<?> read(ConnectionIdentity actor, McpPlatformSystemStatusPort.Read read) {
        return status.read(actor.session(), read);
    }
}
