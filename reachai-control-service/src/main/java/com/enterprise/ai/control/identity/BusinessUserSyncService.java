package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps the ReachAI business-user directory in step with what a business system
 * has already verified about its logged-in user. A user seen for the first time
 * is created; a disabled user is refused instead of being re-activated.
 *
 * <p>Callers must pass identity that a business system attested (for example a
 * verified Embed Token), never fields taken from a request body.
 */
@Service
@RequiredArgsConstructor
public class BusinessUserSyncService {

    static final String SOURCE = "BUSINESS_SYSTEM_SYNC";
    static final String STATUS_ACTIVE = "ACTIVE";

    private final BusinessUserMapper businessUserMapper;
    private final ExternalUserBindingMapper externalUserBindingMapper;
    private final ExternalUserRoleBindingMapper externalUserRoleBindingMapper;

    @Transactional
    public SyncedBusinessUser sync(AttestedBusinessUser attested) {
        String tenantId = tenant(attested.tenantId());
        String appId = requireText(attested.appId(), "appId");
        String externalUserId = requireText(attested.externalUserId(), "externalUserId");
        String userName = StringUtils.hasText(attested.userName()) ? attested.userName().trim() : externalUserId;
        LocalDateTime now = LocalDateTime.now();

        ExternalUserBindingEntity binding = externalUserBindingMapper.selectOne(
                new LambdaQueryWrapper<ExternalUserBindingEntity>()
                        .eq(ExternalUserBindingEntity::getTenantId, tenantId)
                        .eq(ExternalUserBindingEntity::getAppId, appId)
                        .eq(ExternalUserBindingEntity::getExternalUserId, externalUserId)
                        .last("limit 1"));
        BusinessUserEntity user = binding == null
                ? findOrCreateUser(tenantId, globalUserId(attested, appId, externalUserId), userName, now)
                : businessUserMapper.selectById(binding.getBusinessUserId());
        if (user == null) {
            user = findOrCreateUser(tenantId, globalUserId(attested, appId, externalUserId), userName, now);
        }
        if (!STATUS_ACTIVE.equalsIgnoreCase(user.getStatus())) {
            throw new BusinessUserDisabledException(user.getId());
        }
        user.setLastSeenAt(now);
        user.setUpdatedAt(now);
        businessUserMapper.updateById(user);

        if (binding == null) {
            binding = new ExternalUserBindingEntity();
            binding.setTenantId(tenantId);
            binding.setAppId(appId);
            binding.setExternalUserId(externalUserId);
            binding.setStatus(STATUS_ACTIVE);
            binding.setCreatedAt(now);
        }
        if (!STATUS_ACTIVE.equalsIgnoreCase(binding.getStatus())) {
            throw new BusinessUserDisabledException(user.getId());
        }
        binding.setBusinessUserId(user.getId());
        binding.setExternalUserName(userName);
        binding.setLastSeenAt(now);
        binding.setUpdatedAt(now);
        if (binding.getId() == null) {
            externalUserBindingMapper.insert(binding);
        } else {
            externalUserBindingMapper.updateById(binding);
        }
        List<String> roles = replaceSyncedRoles(user.getId(), binding, attested.roles(), now);
        return new SyncedBusinessUser(user.getId(), tenantId, user.getGlobalUserId(),
                user.getDisplayName(), appId, externalUserId, userName, roles);
    }

    /** Business systems (app ids) where this user has an active account binding. */
    public Set<String> activeAppIds(String tenantId, Long businessUserId) {
        Set<String> appIds = new LinkedHashSet<>();
        externalUserBindingMapper.selectList(new LambdaQueryWrapper<ExternalUserBindingEntity>()
                        .eq(ExternalUserBindingEntity::getTenantId, tenant(tenantId))
                        .eq(ExternalUserBindingEntity::getBusinessUserId, businessUserId)
                        .eq(ExternalUserBindingEntity::getStatus, STATUS_ACTIVE))
                .forEach(binding -> appIds.add(binding.getAppId()));
        return appIds;
    }

    /**
     * The employee's active account in one business system, with the roles last synced or
     * granted there. Empty when the employee or that account is no longer active.
     */
    public Optional<BusinessSystemAccount> activeAccount(String tenantId, Long businessUserId, String appId) {
        BusinessUserEntity user = businessUserId == null ? null : businessUserMapper.selectById(businessUserId);
        if (user == null || !STATUS_ACTIVE.equalsIgnoreCase(user.getStatus())) {
            return Optional.empty();
        }
        String tenant = tenant(tenantId);
        ExternalUserBindingEntity binding = externalUserBindingMapper.selectOne(
                new LambdaQueryWrapper<ExternalUserBindingEntity>()
                        .eq(ExternalUserBindingEntity::getTenantId, tenant)
                        .eq(ExternalUserBindingEntity::getBusinessUserId, businessUserId)
                        .eq(ExternalUserBindingEntity::getAppId, appId)
                        .eq(ExternalUserBindingEntity::getStatus, STATUS_ACTIVE)
                        .orderByDesc(ExternalUserBindingEntity::getLastSeenAt)
                        .last("limit 1"));
        if (binding == null) {
            return Optional.empty();
        }
        Set<String> roles = new LinkedHashSet<>();
        externalUserRoleBindingMapper.selectList(new LambdaQueryWrapper<ExternalUserRoleBindingEntity>()
                        .eq(ExternalUserRoleBindingEntity::getTenantId, tenant)
                        .eq(ExternalUserRoleBindingEntity::getAppId, appId)
                        .eq(ExternalUserRoleBindingEntity::getExternalUserId, binding.getExternalUserId())
                        .eq(ExternalUserRoleBindingEntity::getStatus, STATUS_ACTIVE))
                .forEach(role -> roles.add(role.getRoleCode()));
        return Optional.of(new BusinessSystemAccount(appId, binding.getExternalUserId(),
                binding.getExternalUserName(), List.copyOf(roles)));
    }

    private BusinessUserEntity findOrCreateUser(String tenantId, String globalUserId,
                                                String displayName, LocalDateTime now) {
        BusinessUserEntity existing = businessUserMapper.selectOne(new LambdaQueryWrapper<BusinessUserEntity>()
                .eq(BusinessUserEntity::getTenantId, tenantId)
                .eq(BusinessUserEntity::getGlobalUserId, globalUserId)
                .last("limit 1"));
        if (existing != null) {
            return existing;
        }
        BusinessUserEntity created = new BusinessUserEntity();
        created.setTenantId(tenantId);
        created.setGlobalUserId(globalUserId);
        created.setDisplayName(displayName);
        created.setStatus(STATUS_ACTIVE);
        created.setSource(SOURCE);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        businessUserMapper.insert(created);
        return created;
    }

    /** Synced roles mirror the business system; manually granted roles are left alone. */
    private List<String> replaceSyncedRoles(Long businessUserId, ExternalUserBindingEntity binding,
                                            List<String> roleCodes, LocalDateTime now) {
        externalUserRoleBindingMapper.delete(new LambdaQueryWrapper<ExternalUserRoleBindingEntity>()
                .eq(ExternalUserRoleBindingEntity::getTenantId, binding.getTenantId())
                .eq(ExternalUserRoleBindingEntity::getAppId, binding.getAppId())
                .eq(ExternalUserRoleBindingEntity::getExternalUserId, binding.getExternalUserId())
                .eq(ExternalUserRoleBindingEntity::getSource, SOURCE));
        Set<String> normalized = new LinkedHashSet<>();
        for (String roleCode : roleCodes == null ? List.<String>of() : roleCodes) {
            if (StringUtils.hasText(roleCode)) {
                normalized.add(roleCode.trim());
            }
        }
        Set<String> existing = new LinkedHashSet<>();
        externalUserRoleBindingMapper.selectList(new LambdaQueryWrapper<ExternalUserRoleBindingEntity>()
                        .eq(ExternalUserRoleBindingEntity::getTenantId, binding.getTenantId())
                        .eq(ExternalUserRoleBindingEntity::getAppId, binding.getAppId())
                        .eq(ExternalUserRoleBindingEntity::getExternalUserId, binding.getExternalUserId()))
                .forEach(role -> existing.add(role.getRoleCode()));
        for (String roleCode : normalized) {
            if (existing.contains(roleCode)) {
                continue;
            }
            ExternalUserRoleBindingEntity role = new ExternalUserRoleBindingEntity();
            role.setTenantId(binding.getTenantId());
            role.setBusinessUserId(businessUserId);
            role.setAppId(binding.getAppId());
            role.setExternalUserId(binding.getExternalUserId());
            role.setRoleCode(roleCode);
            role.setRoleName(roleCode);
            role.setSource(SOURCE);
            role.setStatus(STATUS_ACTIVE);
            role.setCreatedAt(now);
            role.setUpdatedAt(now);
            externalUserRoleBindingMapper.insert(role);
        }
        return List.copyOf(normalized);
    }

    /**
     * A real cross-system id links the same person in several business systems. Embed falls back
     * to the external id when the system sends none, which is not unique across systems, so that
     * case stays scoped to the system.
     */
    private static String globalUserId(AttestedBusinessUser attested, String appId, String externalUserId) {
        String global = StringUtils.hasText(attested.globalUserId()) ? attested.globalUserId().trim() : null;
        return global == null || global.equals(externalUserId) ? appId + ":" + externalUserId : global;
    }

    private static String tenant(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim().toLowerCase(Locale.ROOT) : "default";
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    /** Identity a business system has already verified for its logged-in user. */
    public record AttestedBusinessUser(String tenantId, String appId, String externalUserId,
                                       String globalUserId, String userName, List<String> roles) {
    }

    public record SyncedBusinessUser(Long businessUserId, String tenantId, String globalUserId,
                                     String displayName, String appId, String externalUserId,
                                     String externalUserName, List<String> roles) {
    }

    public record BusinessSystemAccount(String appId, String externalUserId, String userName, List<String> roles) {
    }

    public static class BusinessUserDisabledException extends RuntimeException {
        public BusinessUserDisabledException(Long businessUserId) {
            super("business user " + businessUserId + " is disabled");
        }
    }
}
