package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Page workbench and registered SDK instances of one project, through the controllers the console uses. */
public interface McpPlatformProjectPagesPort {
    enum Read { ACCESS_CENTER, PAGES, PAGE, PAGE_READINESS, PAGE_MAP, FINDINGS, PUBLISHED, INSTANCES }
    enum Write { CREATE_PAGE, FINDING_STATUS, INSTANCE_STATUS, PURGE_OFFLINE_INSTANCES }
    ResponseEntity<?> read(PlatformAuthenticatedSession actor, String projectCode, Read read, Map<String, Object> query);
    ResponseEntity<?> write(PlatformAuthenticatedSession actor, String projectCode, Write write, Long targetId, Map<String, Object> body);
}
