package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ContextRuntimeUserMappingController {

    private final ContextRuntimeUserMappingMapper mapper;
    private final PlatformAuthorizationService authorizationService;
    private final PlatformAuthAuditService auditService;

    @GetMapping("/api/context/runtime-user-mappings")
    public ResponseEntity<List<MappingView>> list(
            HttpServletRequest request,
            @RequestParam String tenantId,
            @RequestParam(required = false) Long platformUserId,
            @RequestParam(required = false) String runtimeUserId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireMappingAdministrator(request);
        String tenant = normalizeTenant(tenantId);
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        List<MappingView> views = mapper.selectList(Wrappers.<ContextRuntimeUserMappingEntity>lambdaQuery()
                        .eq(ContextRuntimeUserMappingEntity::getTenantId, tenant)
                        .eq(platformUserId != null, ContextRuntimeUserMappingEntity::getPlatformUserId, platformUserId)
                        .eq(StringUtils.hasText(runtimeUserId),
                                ContextRuntimeUserMappingEntity::getRuntimeUserId, trim(runtimeUserId))
                        .eq(projectId != null, ContextRuntimeUserMappingEntity::getProjectId, projectId)
                        .eq(StringUtils.hasText(projectCode),
                                ContextRuntimeUserMappingEntity::getProjectCode, trim(projectCode))
                        .eq(StringUtils.hasText(status),
                                ContextRuntimeUserMappingEntity::getStatus, trim(status).toUpperCase())
                        .orderByDesc(ContextRuntimeUserMappingEntity::getUpdatedAt)
                        .orderByDesc(ContextRuntimeUserMappingEntity::getId)
                        .last("LIMIT " + boundedLimit))
                .stream()
                .map(this::view)
                .toList();
        return ResponseEntity.ok(views);
    }

    @PostMapping("/api/context/runtime-user-mappings")
    @Transactional
    public ResponseEntity<MappingView> create(HttpServletRequest request, @RequestBody CreateCommand command) {
        PlatformAuthenticatedSession actor = requireMappingAdministrator(request);
        if (command == null) {
            throw new IllegalArgumentException("Context runtime user mapping command is required");
        }
        String tenantId = normalizeTenant(command.tenantId());
        Long platformUserId = required(command.platformUserId(), "platformUserId");
        String runtimeUserId = bounded(resolveRuntimeUserId(command), 128, "runtimeUserId");
        ContextRuntimeUserMappingEntity active = mapper.selectOne(
                Wrappers.<ContextRuntimeUserMappingEntity>lambdaQuery()
                        .eq(ContextRuntimeUserMappingEntity::getTenantId, tenantId)
                        .eq(ContextRuntimeUserMappingEntity::getPlatformUserId, platformUserId)
                        .eq(ContextRuntimeUserMappingEntity::getActiveMarker, 1)
                        .last("LIMIT 1 FOR UPDATE"));
        if (active != null) {
            if (runtimeUserId.equals(active.getRuntimeUserId())) {
                return ResponseEntity.ok(view(active));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "an active runtime user mapping already exists for this tenant and platform user");
        }
        ContextRuntimeUserMappingEntity entity = new ContextRuntimeUserMappingEntity();
        entity.setTenantId(tenantId);
        entity.setPlatformUserId(platformUserId);
        entity.setRuntimeUserId(runtimeUserId);
        entity.setGlobalUserId(bounded(command.globalUserId(), 128, "globalUserId"));
        entity.setExternalUserId(bounded(command.externalUserId(), 128, "externalUserId"));
        entity.setProjectId(command.projectId());
        entity.setProjectCode(bounded(command.projectCode(), 96, "projectCode"));
        entity.setStatus("ACTIVE");
        entity.setActiveMarker(1);
        entity.setCreatedBy(String.valueOf(actor.user().getId()));
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException race) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "an active runtime user mapping was created concurrently", race);
        }
        auditService.record(actor, "RUNTIME_USER_MAPPING_CREATED", "RUNTIME_USER_MAPPING",
                String.valueOf(entity.getId()), Map.of(
                        "tenantId", entity.getTenantId(),
                        "platformUserId", entity.getPlatformUserId(),
                        "runtimeUserId", entity.getRuntimeUserId()));
        return ResponseEntity.ok(view(entity));
    }

    @DeleteMapping("/api/context/runtime-user-mappings/{id}")
    @Transactional
    public ResponseEntity<MappingView> delete(HttpServletRequest request, @PathVariable Long id) {
        PlatformAuthenticatedSession actor = requireMappingAdministrator(request);
        ContextRuntimeUserMappingEntity entity = mapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setStatus("DELETED");
        entity.setActiveMarker(null);
        entity.setUpdatedAt(now);
        entity.setDeletedAt(now);
        mapper.updateById(entity);
        auditService.record(actor, "RUNTIME_USER_MAPPING_DELETED", "RUNTIME_USER_MAPPING",
                String.valueOf(entity.getId()), Map.of(
                        "tenantId", entity.getTenantId(),
                        "platformUserId", entity.getPlatformUserId(),
                        "runtimeUserId", entity.getRuntimeUserId()));
        return ResponseEntity.ok(view(entity));
    }

    private PlatformAuthenticatedSession requireMappingAdministrator(HttpServletRequest request) {
        Object candidate = request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "live ReachAI platform login is required");
        }
        authorizationService.requireGlobalPermission(session, "context:runtime-user:mapping:manage");
        return session;
    }

    private MappingView view(ContextRuntimeUserMappingEntity entity) {
        return new MappingView(
                entity.getId(),
                entity.getTenantId(),
                entity.getPlatformUserId(),
                entity.getRuntimeUserId(),
                entity.getGlobalUserId(),
                entity.getExternalUserId(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getStatus(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt());
    }

    private String resolveRuntimeUserId(CreateCommand command) {
        String runtimeUserId = trim(command.runtimeUserId());
        if (StringUtils.hasText(runtimeUserId)) {
            return runtimeUserId;
        }
        String globalUserId = trim(command.globalUserId());
        if (StringUtils.hasText(globalUserId)) {
            return globalUserId;
        }
        String externalUserId = trim(command.externalUserId());
        if (StringUtils.hasText(externalUserId)) {
            return externalUserId;
        }
        throw new IllegalArgumentException("Context runtimeUserId, globalUserId or externalUserId is required");
    }

    private String required(String value, String field) {
        String text = trim(value);
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Context " + field + " is required");
        }
        return text;
    }

    private Long required(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("Context " + field + " is required");
        }
        return value;
    }

    private String normalizeTenant(String value) {
        String tenant = required(value, "tenantId").toLowerCase(Locale.ROOT);
        if (tenant.length() > 96 || !tenant.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("Context tenantId is invalid");
        }
        return tenant;
    }

    private String bounded(String value, int max, String field) {
        String text = trim(value);
        if (text != null && text.length() > max) {
            throw new IllegalArgumentException("Context " + field + " exceeds " + max + " characters");
        }
        return text;
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record CreateCommand(String tenantId,
                                Long platformUserId,
                                String runtimeUserId,
                                String globalUserId,
                                String externalUserId,
                                Long projectId,
                                String projectCode) {
    }

    public record MappingView(Long id,
                              String tenantId,
                              Long platformUserId,
                              String runtimeUserId,
                              String globalUserId,
                              String externalUserId,
                              Long projectId,
                              String projectCode,
                              String status,
                              String createdBy,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt,
                              LocalDateTime deletedAt) {
    }
}
