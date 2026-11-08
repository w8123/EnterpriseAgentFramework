package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Console sign-in for a platform account an administrator bound to an external login (source provider = the
 * business-system login entry, external subject = the account id there). The caller has already verified the
 * external identity; this only finds the bound account and opens a console session for it.
 */
@Service
@RequiredArgsConstructor
public class PlatformExternalLoginService {

    private final PlatformUserMapper userMapper;
    private final PlatformLoginSessionMapper sessionMapper;
    private final PlatformSessionTokenCodec tokenCodec;
    private final PlatformSessionCookieService cookieService;
    private final PlatformAuthorizationService authorizationService;
    private final PlatformAuthProperties authProperties;
    private final PlatformAuthAuditService audit;

    /**
     * @return the session cookies to set, or empty when no platform account is bound to this external account
     * @throws BoundAccountDisabledException when the bound account exists but is disabled
     */
    public Optional<ConsoleLogin> signInBoundAccount(String providerCode, String externalSubject,
                                                     HttpServletRequest request, Map<String, ?> auditDetails) {
        PlatformUserEntity user = userMapper.selectOne(new LambdaQueryWrapper<PlatformUserEntity>()
                .eq(PlatformUserEntity::getSourceProvider, providerCode)
                .eq(PlatformUserEntity::getExternalSubject, externalSubject)
                .last("limit 1"));
        // Exact match only: a case-insensitive collation must not map account "A1" onto "a1".
        if (user == null || !providerCode.equals(user.getSourceProvider())
                || !externalSubject.equals(user.getExternalSubject())) {
            return Optional.empty();
        }
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) throw new BoundAccountDisabledException();
        LocalDateTime now = LocalDateTime.now();
        user.setLastLoginAt(now);
        user.setUpdatedAt(now);
        userMapper.updateById(user);
        String rawToken = tokenCodec.issueRawToken();
        PlatformLoginSessionEntity session = new PlatformLoginSessionEntity();
        session.setSessionId("pls_" + UUID.randomUUID());
        session.setUserId(user.getId());
        session.setProvider(providerCode);
        session.setAccessTokenId(tokenCodec.digest(rawToken));
        session.setExpiresAt(now.plus(authProperties.getSessionTtl()));
        session.setCreatedAt(now);
        sessionMapper.insert(session);
        PlatformAuthenticatedSession authenticated = authorizationService.authenticatedSession(user, session);
        audit.record(authenticated, "PLATFORM_SSO_LOGIN_SUCCEEDED", "PLATFORM_USER", String.valueOf(user.getId()),
                auditDetails);
        return Optional.of(new ConsoleLogin(user.getId(), List.of(cookieService.issueCookie(request, rawToken),
                cookieService.expireLegacyCookie(request))));
    }

    public record ConsoleLogin(Long userId, List<String> cookies) {
    }

    public static class BoundAccountDisabledException extends RuntimeException {
        public BoundAccountDisabledException() {
            super("bound platform account is disabled");
        }
    }
}
