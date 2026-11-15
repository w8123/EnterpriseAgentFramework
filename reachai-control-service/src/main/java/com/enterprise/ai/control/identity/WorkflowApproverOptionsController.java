package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** People and roles a Workflow designer can name as 人工确认 approvers. Only display fields leave. */
@RestController
@RequiredArgsConstructor
public class WorkflowApproverOptionsController {

    static final List<Map<String, String>> ROLE_OPTIONS = List.of(
            Map.of("code", "MANAGER", "label", "管理人员"),
            Map.of("code", "DEVELOPER", "label", "开发人员"),
            Map.of("code", "PLATFORM_ADMIN", "label", "平台管理员"));

    private final PlatformRequestAuthorization authorization;
    private final PlatformUserMapper userMapper;

    @GetMapping("/api/me/workflow-approvals/approver-options")
    public ResponseEntity<Map<String, Object>> approverOptions(HttpServletRequest request) {
        PlatformAuthenticatedSession session = authorization.requireAuthenticated(request);
        boolean designer = session.permissionGrants().stream().anyMatch(grant ->
                PlatformPermissions.ALL.equals(grant.permissionCode())
                        || PlatformPermissions.WORKFLOW_WRITE.equals(grant.permissionCode()));
        if (!designer) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要工作流编辑权限");
        List<Map<String, Object>> users = new ArrayList<>();
        for (PlatformUserEntity user : userMapper.selectList(new LambdaQueryWrapper<PlatformUserEntity>()
                .orderByAsc(PlatformUserEntity::getId))) {
            if (user.getStatus() != null && !"ACTIVE".equalsIgnoreCase(user.getStatus())) continue;
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("username", user.getUsername());
            option.put("displayName", StringUtils.hasText(user.getDisplayName()) ? user.getDisplayName() : user.getUsername());
            users.add(option);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("users", users);
        body.put("roles", ROLE_OPTIONS);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
