package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Resolves platform login sessions from Authorization Bearer tokens.
 * Used by Control public Agent execute to build server-attested trusted user identity.
 */
@Service
@RequiredArgsConstructor
public class PlatformBearerAuthService {

    private final PlatformUserMapper userMapper;
    private final PlatformLoginSessionMapper sessionMapper;
    private final PlatformSessionTokenCodec sessionTokenCodec;
    private final PlatformAuthorizationService authorizationService;

    /** Resolves the authenticated server session, including database-backed grants. */
    public Optional<PlatformAuthenticatedSession> resolveBearerSession(String authorization) {
        String token = bearerToken(authorization);
        return resolveSessionToken(token);
    }

    /** Resolves a raw token supplied by a trusted server-side transport such as an HttpOnly cookie. */
    public Optional<PlatformAuthenticatedSession> resolveSessionToken(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        String tokenDigest = sessionTokenCodec.digest(token);
        PlatformLoginSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<PlatformLoginSessionEntity>()
                .eq(PlatformLoginSessionEntity::getAccessTokenId, tokenDigest)
                .isNull(PlatformLoginSessionEntity::getRevokedAt)
                .last("limit 1"));
        if (session == null || session.getExpiresAt() == null || session.getExpiresAt().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }
        PlatformUserEntity user = userMapper.selectById(session.getUserId());
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(authorizationService.authenticatedSession(user, session));
    }

    /**
     * Compatibility entrypoint for existing public Runtime and AI Coding call
     * sites. New console authorization must use {@link #resolveBearerSession}.
     */
    public Optional<PlatformUserEntity> resolveBearerUser(String authorization) {
        return resolveBearerSession(authorization).map(PlatformAuthenticatedSession::user);
    }

    private static String bearerToken(String authorization) {
        if (!StringUtils.hasText(authorization)) {
            return null;
        }
        String trimmed = authorization.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7) ? trimmed.substring(7).trim() : null;
    }
}
