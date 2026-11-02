package com.enterprise.ai.control.mcp.application.port;

import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Model service console endpoints that the console proxy forwards without a project check; callers first read the
 * instance through the scoped catalog and require model:manage on its project. Owner data is unwrapped; owner
 * messages never cross the boundary.
 */
public interface McpPlatformModelWritePort {
    enum Operation { UPDATE, ARCHIVE, TEST }
    /** Owner data on success, a bare 4xx on rejection, a bare 503 when the result is not confirmed. */
    ResponseEntity<?> change(Operation operation, String modelId, Map<String, Object> body);
    ResponseEntity<?> templates();
    ResponseEntity<?> template(String templateId);
    /** Draft connection test or model discovery with credentials the caller supplies; nothing is saved. */
    enum DraftOperation { TEST, DISCOVER }
    ResponseEntity<?> draft(DraftOperation operation, Map<String, Object> body);

    /** Platform-level provider accounts and the model catalog; callers require global model:manage for writes. */
    enum AccountOperation { LIST, GET, CREATE, UPDATE, DELETE, DISCOVER_DRAFT, DISCOVER_SAVED, CREATE_INSTANCES }
    ResponseEntity<?> account(AccountOperation operation, String accountId, Map<String, String> query, Object body);
    enum CatalogOperation { STATUS, SETTINGS, SYNC }
    ResponseEntity<?> catalog(CatalogOperation operation, Object body);
}
