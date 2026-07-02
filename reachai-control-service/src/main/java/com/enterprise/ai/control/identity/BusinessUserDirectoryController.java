package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class BusinessUserDirectoryController {

    private final BusinessUserMapper businessUserMapper;
    private final ExternalUserBindingMapper externalUserBindingMapper;
    private final ExternalUserRoleBindingMapper externalUserRoleBindingMapper;

    @GetMapping("/api/platform/business-users")
    public ResponseEntity<PageResult<BusinessUserView>> listBusinessUsers(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        int page = Math.max(current, 1);
        int pageSize = Math.min(Math.max(size, 1), 200);
        LambdaQueryWrapper<BusinessUserEntity> base = new LambdaQueryWrapper<BusinessUserEntity>()
                .eq(BusinessUserEntity::getTenantId, StringUtils.hasText(tenantId) ? tenantId.trim() : "default")
                .eq(StringUtils.hasText(status), BusinessUserEntity::getStatus, trim(status))
                .and(StringUtils.hasText(keyword), wrapper -> wrapper
                        .like(BusinessUserEntity::getGlobalUserId, trim(keyword))
                        .or()
                        .like(BusinessUserEntity::getDisplayName, trim(keyword))
                        .or()
                        .like(BusinessUserEntity::getEmail, trim(keyword))
                        .or()
                        .like(BusinessUserEntity::getMobile, trim(keyword)));
        Long total = businessUserMapper.selectCount(base);
        List<BusinessUserView> records = businessUserMapper.selectList(base
                        .orderByDesc(BusinessUserEntity::getUpdatedAt)
                        .orderByDesc(BusinessUserEntity::getId)
                        .last("limit " + ((page - 1) * pageSize) + ", " + pageSize))
                .stream()
                .map(this::toBusinessUserView)
                .toList();
        return ResponseEntity.ok(new PageResult<>(records, total == null ? 0 : total, pageSize, page));
    }

    @PutMapping("/api/platform/business-users/{id}")
    public ResponseEntity<BusinessUserView> updateBusinessUser(@PathVariable Long id,
                                                               @RequestBody BusinessUserUpdateCommand request) {
        BusinessUserEntity entity = businessUserMapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (StringUtils.hasText(request.globalUserId())) {
            entity.setGlobalUserId(request.globalUserId().trim());
        }
        if (StringUtils.hasText(request.displayName())) {
            entity.setDisplayName(request.displayName().trim());
        }
        if (request.email() != null) {
            entity.setEmail(trim(request.email()));
        }
        if (request.mobile() != null) {
            entity.setMobile(trim(request.mobile()));
        }
        if (StringUtils.hasText(request.status())) {
            entity.setStatus(request.status().trim());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        businessUserMapper.updateById(entity);
        return ResponseEntity.ok(toBusinessUserView(entity));
    }

    @GetMapping("/api/platform/business-users/{id}/identities")
    public ResponseEntity<List<ExternalIdentityView>> listBusinessUserIdentities(@PathVariable Long id) {
        return ResponseEntity.ok(externalUserBindingMapper.selectList(new LambdaQueryWrapper<ExternalUserBindingEntity>()
                        .eq(ExternalUserBindingEntity::getBusinessUserId, id)
                        .orderByDesc(ExternalUserBindingEntity::getId))
                .stream()
                .map(this::toExternalIdentityView)
                .toList());
    }

    @PostMapping("/api/platform/business-users/{id}/identities")
    public ResponseEntity<ExternalIdentityView> saveBusinessUserIdentity(@PathVariable Long id,
                                                                         @RequestBody ExternalIdentityCommand request) {
        BusinessUserEntity businessUser = businessUserMapper.selectById(id);
        if (businessUser == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        ExternalUserBindingEntity entity = resolveIdentity(id, request);
        entity.setTenantId(StringUtils.hasText(entity.getTenantId()) ? entity.getTenantId() : businessUser.getTenantId());
        entity.setBusinessUserId(id);
        entity.setAppId(requireText(request.appId(), "appId"));
        entity.setExternalUserId(requireText(request.externalUserId(), "externalUserId"));
        entity.setExternalUserName(StringUtils.hasText(request.externalUserName())
                ? request.externalUserName().trim()
                : request.externalUserId().trim());
        entity.setDeptId(trim(request.deptId()));
        entity.setDeptName(trim(request.deptName()));
        entity.setStatus(StringUtils.hasText(request.status()) ? request.status().trim() : "ACTIVE");
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getId() == null) {
            entity.setCreatedAt(LocalDateTime.now());
            externalUserBindingMapper.insert(entity);
        } else {
            externalUserBindingMapper.updateById(entity);
        }
        replaceRoleBindings(id, entity, request.roles());
        return ResponseEntity.ok(toExternalIdentityView(entity));
    }

    private ExternalUserBindingEntity resolveIdentity(Long businessUserId, ExternalIdentityCommand request) {
        if (request.id() != null) {
            ExternalUserBindingEntity byId = externalUserBindingMapper.selectById(request.id());
            if (byId != null) {
                return byId;
            }
        }
        ExternalUserBindingEntity existing = externalUserBindingMapper.selectOne(new LambdaQueryWrapper<ExternalUserBindingEntity>()
                .eq(ExternalUserBindingEntity::getBusinessUserId, businessUserId)
                .eq(ExternalUserBindingEntity::getAppId, request.appId())
                .eq(ExternalUserBindingEntity::getExternalUserId, request.externalUserId())
                .last("limit 1"));
        return existing == null ? new ExternalUserBindingEntity() : existing;
    }

    private void replaceRoleBindings(Long businessUserId, ExternalUserBindingEntity identity, List<String> roleCodes) {
        externalUserRoleBindingMapper.delete(new LambdaQueryWrapper<ExternalUserRoleBindingEntity>()
                .eq(ExternalUserRoleBindingEntity::getBusinessUserId, businessUserId)
                .eq(ExternalUserRoleBindingEntity::getAppId, identity.getAppId())
                .eq(ExternalUserRoleBindingEntity::getExternalUserId, identity.getExternalUserId()));
        for (String roleCode : roleCodes == null ? List.<String>of() : roleCodes) {
            if (!StringUtils.hasText(roleCode)) {
                continue;
            }
            ExternalUserRoleBindingEntity role = new ExternalUserRoleBindingEntity();
            role.setTenantId(identity.getTenantId());
            role.setBusinessUserId(businessUserId);
            role.setAppId(identity.getAppId());
            role.setExternalUserId(identity.getExternalUserId());
            role.setRoleCode(roleCode.trim());
            role.setRoleName(roleCode.trim());
            role.setSource("MANUAL");
            role.setStatus("ACTIVE");
            role.setCreatedAt(LocalDateTime.now());
            role.setUpdatedAt(LocalDateTime.now());
            externalUserRoleBindingMapper.insert(role);
        }
    }

    private BusinessUserView toBusinessUserView(BusinessUserEntity entity) {
        List<ExternalUserBindingEntity> bindings = externalUserBindingMapper.selectList(
                new LambdaQueryWrapper<ExternalUserBindingEntity>()
                        .eq(ExternalUserBindingEntity::getBusinessUserId, entity.getId()));
        List<ExternalUserRoleBindingEntity> roles = externalUserRoleBindingMapper.selectList(
                new LambdaQueryWrapper<ExternalUserRoleBindingEntity>()
                        .eq(ExternalUserRoleBindingEntity::getBusinessUserId, entity.getId()));
        return new BusinessUserView(
                entity.getId(),
                entity.getTenantId(),
                entity.getGlobalUserId(),
                entity.getDisplayName(),
                entity.getEmail(),
                entity.getMobile(),
                entity.getStatus(),
                entity.getSource(),
                instantText(entity.getLastSeenAt()),
                instantText(entity.getCreatedAt()),
                instantText(entity.getUpdatedAt()),
                bindings.size(),
                bindings.stream()
                        .map(binding -> binding.getAppId() + ":" + binding.getExternalUserId())
                        .distinct()
                        .toList(),
                roles.stream()
                        .map(ExternalUserRoleBindingEntity::getRoleCode)
                        .filter(StringUtils::hasText)
                        .distinct()
                        .toList());
    }

    private ExternalIdentityView toExternalIdentityView(ExternalUserBindingEntity entity) {
        List<ExternalRoleView> roles = externalUserRoleBindingMapper.selectList(
                        new LambdaQueryWrapper<ExternalUserRoleBindingEntity>()
                                .eq(ExternalUserRoleBindingEntity::getBusinessUserId, entity.getBusinessUserId())
                                .eq(ExternalUserRoleBindingEntity::getAppId, entity.getAppId())
                                .eq(ExternalUserRoleBindingEntity::getExternalUserId, entity.getExternalUserId()))
                .stream()
                .map(role -> new ExternalRoleView(
                        role.getId(),
                        role.getRoleCode(),
                        role.getRoleName(),
                        role.getSource(),
                        role.getStatus()))
                .toList();
        return new ExternalIdentityView(
                entity.getId(),
                entity.getTenantId(),
                entity.getBusinessUserId(),
                entity.getAppId(),
                entity.getExternalUserId(),
                entity.getExternalUserName(),
                entity.getDeptId(),
                entity.getDeptName(),
                entity.getStatus(),
                instantText(entity.getLastSeenAt()),
                roles);
    }

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : value;
    }

    private String instantText(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    public record PageResult<T>(List<T> records, long total, int size, int current) {
    }

    public record BusinessUserView(
            Long id,
            String tenantId,
            String globalUserId,
            String displayName,
            String email,
            String mobile,
            String status,
            String source,
            String lastSeenAt,
            String createdAt,
            String updatedAt,
            int bindingCount,
            List<String> externalIdentities,
            List<String> roleCodes
    ) {
    }

    public record BusinessUserUpdateCommand(
            String globalUserId,
            String displayName,
            String email,
            String mobile,
            String status
    ) {
    }

    public record ExternalIdentityView(
            Long id,
            String tenantId,
            Long businessUserId,
            String appId,
            String externalUserId,
            String externalUserName,
            String deptId,
            String deptName,
            String status,
            String lastSeenAt,
            List<ExternalRoleView> roles
    ) {
    }

    public record ExternalRoleView(
            Long id,
            String roleCode,
            String roleName,
            String source,
            String status
    ) {
    }

    public record ExternalIdentityCommand(
            Long id,
            String appId,
            String externalUserId,
            String externalUserName,
            String deptId,
            String deptName,
            String status,
            List<String> roles
    ) {
    }
}
