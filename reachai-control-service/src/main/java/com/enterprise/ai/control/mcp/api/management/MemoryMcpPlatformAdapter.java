package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformMemoryPort;
import com.enterprise.ai.control.memory.PersonalMemoryController;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver;
import com.enterprise.ai.control.memory.PersonalMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Runs platform AI memory calls through the 「我的记忆」 controller. A memory the AI saves is recorded as saved by an AI tool,
 * not as the owner's manual entry, exactly as the employee AI tools record theirs.
 */
@Component
@RequiredArgsConstructor
class MemoryMcpPlatformAdapter implements McpPlatformMemoryPort {
    private final PersonalMemoryController memories;
    private final PersonalMemoryService memoryService;
    private final PersonalMemoryIdentityResolver identities;
    private final PlatformDelegatedRequestScope scope;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<?> call(PlatformAuthenticatedSession actor, Operation operation, Long id, Map<String, Object> args) {
        return scope.run(actor, request -> switch (operation) {
            case TREE -> ResponseEntity.ok(memories.tree(request));
            case LIST -> ResponseEntity.ok(memories.list(request, (String) args.get("scope"), (Long) args.get("groupId"), null,
                    (String) args.get("keyword"), (Integer) args.get("limit"), (Integer) args.get("offset")));
            case GET -> ResponseEntity.ok(memories.get(request, id));
            case REMEMBER -> ResponseEntity.ok(memoryService.remember(identities.resolve(request, null), new PersonalMemoryService.RememberCommand(
                    (String) args.get("type"), null, (String) args.get("title"), (String) args.get("content"), tags(args.get("tags")), null,
                    (String) args.get("clientRequestId"), PersonalMemoryService.SOURCE_AI_TOOL, null, null, null,
                    (String) args.get("projectCode"), (Long) args.get("groupId"))));
            case UPDATE -> ResponseEntity.ok(memories.update(request, id, json.convertValue(args, PersonalMemoryService.UpdateCommand.class)));
            case FORGET -> ResponseEntity.ok(memories.forget(request, id));
            case CREATE_GROUP -> ResponseEntity.ok(memories.createGroup(request, json.convertValue(args, PersonalMemoryGroupService.CreateGroupCommand.class)));
            case UPDATE_GROUP -> ResponseEntity.ok(memories.updateGroup(request, id, json.convertValue(args, PersonalMemoryGroupService.UpdateGroupCommand.class)));
            case DELETE_GROUP -> memories.deleteGroup(request, id);
            case ADD_PROJECT -> ResponseEntity.ok(memories.addProject(request, new PersonalMemoryGroupService.AddProjectCommand((String) args.get("projectCode"))));
            case EXPORT -> ResponseEntity.ok(memories.export(request).getBody());
        });
    }

    private static List<String> tags(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : null;
    }
}
