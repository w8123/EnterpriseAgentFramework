package com.enterprise.ai.control.platform;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService.AddProjectCommand;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService.CreateGroupCommand;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService.GroupView;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService.LayerView;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService.TreeView;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService.UpdateGroupCommand;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService;
import com.enterprise.ai.control.memory.PersonalMemoryService.EraseAllCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.EraseAllView;
import com.enterprise.ai.control.memory.PersonalMemoryService.ForgetView;
import com.enterprise.ai.control.memory.PersonalMemoryService.MemoryPage;
import com.enterprise.ai.control.memory.PersonalMemoryService.MemoryView;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberResult;
import com.enterprise.ai.control.memory.PersonalMemoryService.UpdateCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Function;

/**
 * 员工个人页 · 我的记忆：新增、编辑、删除记忆，按「个人记忆 / 项目记忆」分层分组管理。
 *
 * <p>The owner is the employee the portal session was opened for, the same tenant and external user that embedded
 * chat recalls and writes memories for, so the portal and the chat panel show one list. Nothing in the request can
 * name another owner.
 */
@RestController
@RequestMapping("/api/employee-portal/memories")
@RequiredArgsConstructor
public class EmployeePortalMemoryController {

    private final EmployeePortalSessionService sessions;
    private final PersonalMemoryService memoryService;
    private final PersonalMemoryGroupService groupService;

    @GetMapping("/tree")
    public ResponseEntity<ApiResult<TreeView>> tree(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return handle(authorization, groupService::tree);
    }

    @GetMapping
    public ResponseEntity<ApiResult<MemoryPage>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset) {
        return handle(authorization, owner -> memoryService.list(owner, scope, groupId, agentId, keyword, limit, offset));
    }

    @PostMapping
    public ResponseEntity<ApiResult<RememberResult>> remember(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody RememberCommand command) {
        RememberCommand manual = command == null ? null : new RememberCommand(command.type(), command.semanticKey(),
                command.title(), command.content(), command.tags(), command.expiresAt(), command.clientRequestId(),
                PersonalMemoryService.SOURCE_MANUAL, null, null, null, command.projectCode(), command.groupId());
        return handle(authorization, owner -> memoryService.remember(owner, manual));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResult<MemoryView>> update(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable Long id,
            @RequestBody UpdateCommand command) {
        return handle(authorization, owner -> memoryService.update(owner, id, command));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResult<ForgetView>> forget(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable Long id) {
        return handle(authorization, owner -> memoryService.forget(owner, id));
    }

    @PostMapping("/erase-all")
    public ResponseEntity<ApiResult<EraseAllView>> eraseAll(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody EraseAllCommand command) {
        return handle(authorization, owner -> memoryService.eraseAll(owner, command));
    }

    @PostMapping("/groups")
    public ResponseEntity<ApiResult<GroupView>> createGroup(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody CreateGroupCommand command) {
        return handle(authorization, owner -> groupService.createGroup(owner, command));
    }

    @PutMapping("/groups/{id}")
    public ResponseEntity<ApiResult<GroupView>> updateGroup(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable Long id,
            @RequestBody UpdateGroupCommand command) {
        return handle(authorization, owner -> groupService.updateGroup(owner, id, command));
    }

    @DeleteMapping("/groups/{id}")
    public ResponseEntity<ApiResult<Boolean>> deleteGroup(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable Long id) {
        return handle(authorization, owner -> {
            groupService.deleteGroup(owner, id);
            return Boolean.TRUE;
        });
    }

    @PostMapping("/projects")
    public ResponseEntity<ApiResult<LayerView>> addProject(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody AddProjectCommand command) {
        return handle(authorization, owner -> groupService.addProject(owner, command == null ? null : command.projectCode()));
    }

    private <T> ResponseEntity<ApiResult<T>> handle(String authorization, Function<PersonalMemoryPrincipal, T> action) {
        EmployeeOwner employee = sessions.resolve(bearer(authorization)).orElse(null);
        if (employee == null || !StringUtils.hasText(employee.externalUserId())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(401,
                    "登录已过期，请回到业务系统的 AI 对话面板重新打开"));
        }
        try {
            return ResponseEntity.ok(ApiResult.ok(action.apply(owner(employee))));
        } catch (ResponseStatusException ex) {
            int status = ex.getStatusCode().value();
            return ResponseEntity.status(status).body(ApiResult.fail(status, ex.getReason()));
        }
    }

    /** Matches EmbedPersonalMemoryController: the business-user tenant is already normalized to lower case. */
    static PersonalMemoryPrincipal owner(EmployeeOwner employee) {
        String tenant = StringUtils.hasText(employee.tenantId()) ? employee.tenantId().trim() : "default";
        return PersonalMemoryPrincipal.business(tenant, employee.externalUserId(), employee.projectCode());
    }

    private static String bearer(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }
}
