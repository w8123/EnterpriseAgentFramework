package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** The connection owner's own personal memories, resolved to the same owner the 「我的记忆」 page uses. */
public interface McpPlatformMemoryPort {
    enum Operation { TREE, LIST, GET, REMEMBER, UPDATE, FORGET, CREATE_GROUP, UPDATE_GROUP, DELETE_GROUP, ADD_PROJECT, EXPORT }
    ResponseEntity<?> call(PlatformAuthenticatedSession actor, Operation operation, Long id, Map<String, Object> arguments);
}
