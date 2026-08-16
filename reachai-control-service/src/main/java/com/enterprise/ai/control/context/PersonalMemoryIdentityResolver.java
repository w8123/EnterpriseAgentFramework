package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/** Resolves a personal-memory owner exclusively from a server-attested platform session. */
@Component
public class PersonalMemoryIdentityResolver {

    public static final String TENANT_HEADER = "X-ReachAI-Tenant-Id";

    private final ContextRuntimeUserMappingMapper mappingMapper;
    private final String defaultTenant;

    public PersonalMemoryIdentityResolver(
            ContextRuntimeUserMappingMapper mappingMapper,
            @Value("${reachai.context.personal-memory.default-tenant:default}") String defaultTenant) {
        this.mappingMapper = mappingMapper;
        this.defaultTenant = normalizeTenant(defaultTenant, "default");
    }

    public PersonalMemoryPrincipal resolve(HttpServletRequest request, String requestedTenant) {
        Object candidate = request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session) || session.user() == null
                || session.user().getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticated platform session is required");
        }
        return resolveAttestedPlatformUser(session.user(), session.sessionId(), requestedTenant);
    }

    /**
     * Resolves the canonical Runtime owner for a platform user already authenticated by a
     * server-side boundary. Public request values must never be passed as {@code user}.
     */
    public PersonalMemoryPrincipal resolveAttestedPlatformUser(
            PlatformUserEntity user,
            String platformSessionId,
            String requestedTenant) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticated platform user is required");
        }
        String tenantId = normalizeTenant(requestedTenant, defaultTenant);
        List<String> mappedRuntimeUsers = mappingMapper.selectList(
                        Wrappers.<ContextRuntimeUserMappingEntity>lambdaQuery()
                                .eq(ContextRuntimeUserMappingEntity::getTenantId, tenantId)
                                .eq(ContextRuntimeUserMappingEntity::getPlatformUserId, user.getId())
                                .eq(ContextRuntimeUserMappingEntity::getStatus, "ACTIVE")
                                .eq(ContextRuntimeUserMappingEntity::getActiveMarker, 1)
                                .orderByDesc(ContextRuntimeUserMappingEntity::getUpdatedAt))
                .stream()
                .map(ContextRuntimeUserMappingEntity::getRuntimeUserId)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        String runtimeUserId;
        if (mappedRuntimeUsers.size() == 1) {
            runtimeUserId = mappedRuntimeUsers.get(0);
        } else if (mappedRuntimeUsers.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "multiple runtime user mappings exist for this tenant");
        } else if (defaultTenant.equals(tenantId)) {
            // Matches the trusted identity currently used by the platform Agent gateway.
            runtimeUserId = String.valueOf(user.getId());
        } else {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "an active runtime user mapping is required for the requested tenant");
        }
        return new PersonalMemoryPrincipal(tenantId, runtimeUserId, user.getId(),
                user.getUsername(), platformSessionId);
    }

    private static String normalizeTenant(String value, String fallback) {
        String tenant = StringUtils.hasText(value) ? value.trim() : fallback;
        if (!StringUtils.hasText(tenant) || tenant.length() > 96
                || !tenant.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid tenant id");
        }
        return tenant.toLowerCase(Locale.ROOT);
    }

    public record PersonalMemoryPrincipal(
            String tenantId,
            String runtimeUserId,
            Long platformUserId,
            String username,
            String platformSessionId) {
    }
}
