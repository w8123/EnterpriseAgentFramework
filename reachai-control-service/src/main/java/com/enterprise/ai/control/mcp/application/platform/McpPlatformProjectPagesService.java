package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectPagesPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectPagesPort.Read;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectPagesPort.Write;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Registering a page by hand only adds catalog entries, triaging an analysis finding is a reversible review mark, and
 * instance status or cleanup of offline instances is reversible operations work (a live instance re-registers on its
 * next heartbeat), so these run directly. A lost result is reported as unknown, never resent.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformProjectPagesService {
    private final McpPlatformProjectPagesPort pages;

    public ResponseEntity<?> read(ConnectionIdentity actor, String projectCode, Read read, Map<String, Object> query) {
        return pages.read(actor.session(), projectCode, read, query);
    }

    public ResponseEntity<?> write(ConnectionIdentity actor, String projectCode, Write write, Long targetId, Map<String, Object> body) {
        return PlatformAiDirectWrites.once(() -> pages.write(actor.session(), projectCode, write, targetId, body), switch (write) {
            case CREATE_PAGE -> "页面登记结果未确认，请先用 reachai_page_workbench_read 列出页面再决定；不要直接重试。";
            case FINDING_STATUS -> "标记结果未确认，请重新读取分析发现。";
            case INSTANCE_STATUS, PURGE_OFFLINE_INSTANCES -> "实例操作结果未确认，请重新列出实例。";
        });
    }
}
