package com.enterprise.ai.agent.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BusinessUserDirectoryService {

    private final BusinessUserMapper businessUserMapper;
    private final ExternalUserBindingMapper bindingMapper;
    private final ExternalUserRoleBindingMapper roleBindingMapper;

    public Page<BusinessUserView> pageDirectoryUsers(int current,
                                                     int size,
                                                     String tenantId,
                                                     String keyword,
                                                     String status) {
        Page<BusinessUserEntity> page = businessUserMapper.selectPage(
                new Page<>(Math.max(1, current), Math.max(1, Math.min(200, size))),
                buildUserQuery(tenantId, keyword, status));
        List<BusinessUserEntity> users = page.getRecords() == null ? List.of() : page.getRecords();
        List<Long> userIds = users.stream().map(BusinessUserEntity::getId).filter(id -> id != null).toList();
        Map<Long, List<ExternalUserBindingEntity>> bindingsByUser = userIds.isEmpty()
                ? Map.of()
                : bindingMapper.selectList(Wrappers.<ExternalUserBindingEntity>lambdaQuery()
                        .in(ExternalUserBindingEntity::getBusinessUserId, userIds))
                .stream()
                .collect(Collectors.groupingBy(ExternalUserBindingEntity::getBusinessUserId));
        Map<Long, List<ExternalUserRoleBindingEntity>> rolesByUser = userIds.isEmpty()
                ? Map.of()
                : roleBindingMapper.selectList(Wrappers.<ExternalUserRoleBindingEntity>lambdaQuery()
                        .in(ExternalUserRoleBindingEntity::getBusinessUserId, userIds))
                .stream()
                .collect(Collectors.groupingBy(ExternalUserRoleBindingEntity::getBusinessUserId));

        Page<BusinessUserView> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(users.stream()
                .map(user -> toView(user,
                        bindingsByUser.getOrDefault(user.getId(), List.of()),
                        rolesByUser.getOrDefault(user.getId(), List.of())))
                .toList());
        return result;
    }

    public List<ExternalIdentityView> listExternalIdentities(Long businessUserId) {
        if (businessUserId == null) {
            return List.of();
        }
        List<ExternalUserBindingEntity> bindings = bindingMapper.selectList(Wrappers.<ExternalUserBindingEntity>lambdaQuery()
                .eq(ExternalUserBindingEntity::getBusinessUserId, businessUserId)
                .orderByAsc(ExternalUserBindingEntity::getAppId)
                .orderByAsc(ExternalUserBindingEntity::getExternalUserId));
        List<ExternalUserRoleBindingEntity> roles = roleBindingMapper.selectList(Wrappers.<ExternalUserRoleBindingEntity>lambdaQuery()
                .eq(ExternalUserRoleBindingEntity::getBusinessUserId, businessUserId));
        Map<String, List<ExternalUserRoleBindingEntity>> rolesByIdentity = roles.stream()
                .collect(Collectors.groupingBy(role -> identityKey(role.getAppId(), role.getExternalUserId())));
        return bindings.stream()
                .map(binding -> new ExternalIdentityView(
                        binding.getId(),
                        binding.getTenantId(),
                        binding.getBusinessUserId(),
                        binding.getAppId(),
                        binding.getExternalUserId(),
                        binding.getExternalUserName(),
                        binding.getDeptId(),
                        binding.getDeptName(),
                        binding.getStatus(),
                        binding.getLastSeenAt(),
                        rolesByIdentity.getOrDefault(identityKey(binding.getAppId(), binding.getExternalUserId()), List.of())
                                .stream()
                                .map(role -> new ExternalRoleView(role.getId(), role.getRoleCode(), role.getRoleName(), role.getSource(), role.getStatus()))
                                .toList()))
                .toList();
    }

    @Transactional
    public ExternalIdentityView saveExternalIdentity(Long businessUserId, ExternalIdentityCommand command) {
        if (businessUserId == null) {
            throw new IllegalArgumentException("businessUserId is required");
        }
        if (command == null || !StringUtils.hasText(command.appId()) || !StringUtils.hasText(command.externalUserId())) {
            throw new IllegalArgumentException("appId and externalUserId are required");
        }
        BusinessUserEntity user = businessUserMapper.selectById(businessUserId);
        if (user == null) {
            throw new IllegalArgumentException("business user not found");
        }
        LocalDateTime now = LocalDateTime.now();
        ExternalUserBindingEntity binding = null;
        if (command.id() != null) {
            binding = bindingMapper.selectById(command.id());
        }
        if (binding == null) {
            binding = bindingMapper.selectOne(Wrappers.<ExternalUserBindingEntity>lambdaQuery()
                    .eq(ExternalUserBindingEntity::getTenantId, user.getTenantId())
                    .eq(ExternalUserBindingEntity::getAppId, command.appId())
                    .eq(ExternalUserBindingEntity::getExternalUserId, command.externalUserId())
                    .last("LIMIT 1"));
        }
        if (binding == null) {
            binding = new ExternalUserBindingEntity();
            binding.setTenantId(user.getTenantId());
            binding.setCreatedAt(now);
        }
        binding.setBusinessUserId(user.getId());
        binding.setAppId(command.appId());
        binding.setExternalUserId(command.externalUserId());
        binding.setExternalUserName(firstNonBlank(command.externalUserName(), command.externalUserId()));
        binding.setDeptId(command.deptId());
        binding.setDeptName(command.deptName());
        binding.setStatus(firstNonBlank(command.status(), "ACTIVE"));
        binding.setLastSeenAt(now);
        binding.setUpdatedAt(now);
        if (binding.getId() == null) {
            bindingMapper.insert(binding);
        } else {
            bindingMapper.updateById(binding);
        }
        syncRoles(user.getId(), user.getTenantId(), binding.getAppId(), binding.getExternalUserId(),
                command.roles(), "MANUAL", now);
        Long savedBindingId = binding.getId();
        return listExternalIdentities(user.getId()).stream()
                .filter(view -> view.id().equals(savedBindingId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("saved identity not found"));
    }

    @Transactional
    public BusinessUserEntity updateBusinessUser(Long businessUserId, BusinessUserUpdateCommand command) {
        if (businessUserId == null) {
            throw new IllegalArgumentException("businessUserId is required");
        }
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        BusinessUserEntity user = businessUserMapper.selectById(businessUserId);
        if (user == null) {
            throw new IllegalArgumentException("business user not found");
        }
        if (StringUtils.hasText(command.globalUserId())) {
            user.setGlobalUserId(command.globalUserId().trim());
        }
        if (command.displayName() != null) {
            user.setDisplayName(command.displayName());
        }
        if (command.email() != null) {
            user.setEmail(command.email());
        }
        if (command.mobile() != null) {
            user.setMobile(command.mobile());
        }
        if (StringUtils.hasText(command.status())) {
            user.setStatus(command.status().trim());
        }
        user.setSource(firstNonBlank(user.getSource(), "MANUAL"));
        user.setUpdatedAt(LocalDateTime.now());
        businessUserMapper.updateById(user);
        return user;
    }

    @Transactional
    public BusinessUserEntity upsertFromPrincipal(BusinessPrincipal principal) {
        return upsertPrincipal(principal, "EMBED_TOKEN", null, null);
    }

    private BusinessUserEntity upsertPrincipal(BusinessPrincipal principal, String source, String email, String mobile) {
        if (principal == null || !StringUtils.hasText(principal.getExternalUserId())) {
            throw new IllegalArgumentException("principal.externalUserId is required");
        }
        String tenantId = defaultString(principal.getTenantId(), "default");
        String appId = defaultString(principal.getAppId(), "default");
        String globalUserId = defaultString(principal.getGlobalUserId(), principal.getExternalUserId());
        LocalDateTime now = LocalDateTime.now();

        ExternalUserBindingEntity binding = bindingMapper.selectOne(Wrappers.<ExternalUserBindingEntity>lambdaQuery()
                .eq(ExternalUserBindingEntity::getTenantId, tenantId)
                .eq(ExternalUserBindingEntity::getAppId, appId)
                .eq(ExternalUserBindingEntity::getExternalUserId, principal.getExternalUserId())
                .last("LIMIT 1"));
        rejectBlockedExternalBinding(binding);

        BusinessUserEntity user = businessUserMapper.selectOne(Wrappers.<BusinessUserEntity>lambdaQuery()
                .eq(BusinessUserEntity::getTenantId, tenantId)
                .eq(BusinessUserEntity::getGlobalUserId, globalUserId)
                .last("LIMIT 1"));
        if (user == null) {
            user = new BusinessUserEntity();
            user.setTenantId(tenantId);
            user.setGlobalUserId(globalUserId);
            user.setStatus("ACTIVE");
            user.setSource("EMBED_TOKEN");
            user.setCreatedAt(now);
        }
        user.setDisplayName(firstNonBlank(principal.getUserName(), principal.getExternalUserId()));
        if (email != null) {
            user.setEmail(email);
        }
        if (mobile != null) {
            user.setMobile(mobile);
        }
        user.setSource(defaultString(source, "EMBED_TOKEN"));
        user.setLastSeenAt(now);
        user.setUpdatedAt(now);
        if (user.getId() == null) {
            businessUserMapper.insert(user);
        } else {
            businessUserMapper.updateById(user);
        }

        if (binding == null) {
            binding = new ExternalUserBindingEntity();
            binding.setTenantId(tenantId);
            binding.setAppId(appId);
            binding.setExternalUserId(principal.getExternalUserId());
            binding.setCreatedAt(now);
        }
        binding.setBusinessUserId(user.getId());
        binding.setExternalUserName(firstNonBlank(principal.getUserName(), principal.getExternalUserId()));
        binding.setDeptId(principal.getDeptId());
        binding.setDeptName(principal.getDeptName());
        binding.setStatus("ACTIVE");
        binding.setLastSeenAt(now);
        binding.setUpdatedAt(now);
        if (binding.getId() == null) {
            bindingMapper.insert(binding);
        } else {
            bindingMapper.updateById(binding);
        }

        syncRoles(user.getId(), tenantId, appId, principal.getExternalUserId(), principal.getRoles(), source, now);
        return user;
    }

    private void rejectBlockedExternalBinding(ExternalUserBindingEntity binding) {
        if (binding == null || !StringUtils.hasText(binding.getStatus())) {
            return;
        }
        String status = binding.getStatus().trim().toUpperCase();
        if ("DISABLED".equals(status) || "DELETED".equals(status)) {
            throw new IllegalArgumentException("external user is " + status + " and cannot use embedded agents");
        }
    }

    @Transactional
    public BusinessUserSyncResult upsertExternalUser(String tenantId,
                                                     String appId,
                                                     BusinessUserSyncCommand command,
                                                     String source) {
        if (command == null || !StringUtils.hasText(command.externalUserId())) {
            throw new IllegalArgumentException("externalUserId is required");
        }
        BusinessPrincipal principal = BusinessPrincipal.builder()
                .tenantId(defaultString(tenantId, "default"))
                .appId(defaultString(appId, "default"))
                .externalUserId(command.externalUserId())
                .globalUserId(defaultString(command.globalUserId(), command.externalUserId()))
                .userName(command.userName())
                .deptId(command.deptId())
                .deptName(command.deptName())
                .roles(command.roles())
                .attributes(command.attributes())
                .build();
        upsertPrincipal(principal, defaultString(source, "SDK_SYNC"), command.email(), command.mobile());
        return new BusinessUserSyncResult(command.externalUserId(), "UPSERTED", null);
    }

    @Transactional
    public BusinessUserSyncResult disableExternalUser(String tenantId, String appId, String externalUserId) {
        return markExternalUser(tenantId, appId, externalUserId, "DISABLED");
    }

    @Transactional
    public BusinessUserSyncResult deleteExternalUser(String tenantId, String appId, String externalUserId) {
        return markExternalUser(tenantId, appId, externalUserId, "DELETED");
    }

    @Transactional
    public BusinessUserBatchSyncResult syncExternalUsers(String tenantId,
                                                        String appId,
                                                        List<BusinessUserSyncCommand> users,
                                                        String source) {
        List<BusinessUserSyncResult> items = (users == null ? List.<BusinessUserSyncCommand>of() : users)
                .stream()
                .map(user -> {
                    try {
                        return upsertExternalUser(tenantId, appId, user, source);
                    } catch (Exception ex) {
                        String externalUserId = user == null ? null : user.externalUserId();
                        return new BusinessUserSyncResult(externalUserId, "FAILED", ex.getMessage());
                    }
                })
                .toList();
        return new BusinessUserBatchSyncResult(items);
    }

    private BusinessUserSyncResult markExternalUser(String tenantId,
                                                    String appId,
                                                    String externalUserId,
                                                    String status) {
        if (!StringUtils.hasText(externalUserId)) {
            throw new IllegalArgumentException("externalUserId is required");
        }
        String resolvedTenant = defaultString(tenantId, "default");
        String resolvedApp = defaultString(appId, "default");
        LocalDateTime now = LocalDateTime.now();
        ExternalUserBindingEntity binding = bindingMapper.selectOne(Wrappers.<ExternalUserBindingEntity>lambdaQuery()
                .eq(ExternalUserBindingEntity::getTenantId, resolvedTenant)
                .eq(ExternalUserBindingEntity::getAppId, resolvedApp)
                .eq(ExternalUserBindingEntity::getExternalUserId, externalUserId)
                .last("LIMIT 1"));
        if (binding == null) {
            return new BusinessUserSyncResult(externalUserId, "NOT_FOUND", "external user binding not found");
        }
        binding.setStatus(status);
        binding.setUpdatedAt(now);
        bindingMapper.updateById(binding);

        List<ExternalUserRoleBindingEntity> roles = roleBindingMapper.selectList(
                Wrappers.<ExternalUserRoleBindingEntity>lambdaQuery()
                        .eq(ExternalUserRoleBindingEntity::getTenantId, resolvedTenant)
                        .eq(ExternalUserRoleBindingEntity::getAppId, resolvedApp)
                        .eq(ExternalUserRoleBindingEntity::getExternalUserId, externalUserId));
        for (ExternalUserRoleBindingEntity role : roles) {
            role.setStatus(status);
            role.setUpdatedAt(now);
            roleBindingMapper.updateById(role);
        }
        return new BusinessUserSyncResult(externalUserId, status, null);
    }

    private void syncRoles(Long businessUserId,
                           String tenantId,
                           String appId,
                           String externalUserId,
                           List<String> roles,
                           String source,
                           LocalDateTime now) {
        Set<String> desired = new HashSet<>(roles == null ? List.of() : roles);
        List<ExternalUserRoleBindingEntity> existing = roleBindingMapper.selectList(
                Wrappers.<ExternalUserRoleBindingEntity>lambdaQuery()
                        .eq(ExternalUserRoleBindingEntity::getTenantId, tenantId)
                        .eq(ExternalUserRoleBindingEntity::getAppId, appId)
                        .eq(ExternalUserRoleBindingEntity::getExternalUserId, externalUserId));
        Set<String> seen = new HashSet<>();
        for (ExternalUserRoleBindingEntity row : existing) {
            seen.add(row.getRoleCode());
            row.setBusinessUserId(businessUserId);
            row.setStatus(desired.contains(row.getRoleCode()) ? "ACTIVE" : "DISABLED");
            row.setUpdatedAt(now);
            roleBindingMapper.updateById(row);
        }
        for (String role : desired) {
            if (!StringUtils.hasText(role) || seen.contains(role)) {
                continue;
            }
            ExternalUserRoleBindingEntity row = new ExternalUserRoleBindingEntity();
            row.setTenantId(tenantId);
            row.setBusinessUserId(businessUserId);
            row.setAppId(appId);
            row.setExternalUserId(externalUserId);
            row.setRoleCode(role);
            row.setRoleName(role);
            row.setSource(defaultString(source, "EMBED_TOKEN"));
            row.setStatus("ACTIVE");
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            roleBindingMapper.insert(row);
        }
    }

    private static String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String firstNonBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private LambdaQueryWrapper<BusinessUserEntity> buildUserQuery(String tenantId, String keyword, String status) {
        LambdaQueryWrapper<BusinessUserEntity> query = Wrappers.<BusinessUserEntity>lambdaQuery();
        if (StringUtils.hasText(tenantId)) {
            query.eq(BusinessUserEntity::getTenantId, tenantId);
        }
        if (StringUtils.hasText(status)) {
            query.eq(BusinessUserEntity::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            query.and(w -> w.like(BusinessUserEntity::getGlobalUserId, kw)
                    .or()
                    .like(BusinessUserEntity::getDisplayName, kw)
                    .or()
                    .like(BusinessUserEntity::getEmail, kw)
                    .or()
                    .like(BusinessUserEntity::getMobile, kw));
        }
        query.orderByDesc(BusinessUserEntity::getLastSeenAt)
                .orderByDesc(BusinessUserEntity::getUpdatedAt)
                .orderByDesc(BusinessUserEntity::getId);
        return query;
    }

    private BusinessUserView toView(BusinessUserEntity user,
                                    List<ExternalUserBindingEntity> bindings,
                                    List<ExternalUserRoleBindingEntity> roles) {
        List<String> externalIdentities = bindings.stream()
                .map(binding -> identityKey(binding.getAppId(), binding.getExternalUserId()))
                .distinct()
                .sorted()
                .toList();
        List<String> roleCodes = roles.stream()
                .filter(role -> "ACTIVE".equalsIgnoreCase(role.getStatus()))
                .map(role -> identityKey(role.getAppId(), role.getRoleCode()))
                .distinct()
                .sorted()
                .toList();
        return new BusinessUserView(
                user.getId(),
                user.getTenantId(),
                user.getGlobalUserId(),
                user.getDisplayName(),
                user.getEmail(),
                user.getMobile(),
                user.getStatus(),
                user.getSource(),
                user.getLastSeenAt(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                bindings.size(),
                externalIdentities,
                roleCodes);
    }

    private static String identityKey(String appId, String value) {
        return defaultString(appId, "default") + ":" + defaultString(value, "");
    }

    public record BusinessUserView(Long id,
                                   String tenantId,
                                   String globalUserId,
                                   String displayName,
                                   String email,
                                   String mobile,
                                   String status,
                                   String source,
                                   LocalDateTime lastSeenAt,
                                   LocalDateTime createdAt,
                                   LocalDateTime updatedAt,
                                   int bindingCount,
                                   List<String> externalIdentities,
                                   List<String> roleCodes) {
        public BusinessUserView {
            externalIdentities = externalIdentities == null ? List.of() : externalIdentities;
            roleCodes = roleCodes == null ? List.of() : roleCodes;
        }
    }

    public record ExternalIdentityView(Long id,
                                       String tenantId,
                                       Long businessUserId,
                                       String appId,
                                       String externalUserId,
                                       String externalUserName,
                                       String deptId,
                                       String deptName,
                                       String status,
                                       LocalDateTime lastSeenAt,
                                       List<ExternalRoleView> roles) {
        public ExternalIdentityView {
            roles = roles == null ? List.of() : roles;
        }
    }

    public record ExternalRoleView(Long id, String roleCode, String roleName, String source, String status) {
    }

    public record ExternalIdentityCommand(Long id,
                                          String appId,
                                          String externalUserId,
                                          String externalUserName,
                                          String deptId,
                                          String deptName,
                                          String status,
                                          List<String> roles) {
        public ExternalIdentityCommand {
            roles = roles == null ? List.of() : roles;
        }
    }

    public record BusinessUserUpdateCommand(String globalUserId,
                                            String displayName,
                                            String email,
                                            String mobile,
                                            String status) {
    }
}
