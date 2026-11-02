package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Owner-controlled AI credentials, deliberately separate from console and employee sessions. */
@Service
@RequiredArgsConstructor
public class PlatformAiConnectionService {
    static final String TOKEN_PREFIX = "rpi_";
    private static final int MAX_TTL_DAYS = 30;
    private static final Set<String> TOOL_KINDS = Set.of("CODEX", "CLAUDE_CODE", "OTHER");
    private static final TypeReference<List<String>> ROLES = new TypeReference<>() { };
    private static final TypeReference<List<PlatformPermissionGrant>> GRANTS = new TypeReference<>() { };
    private final PlatformAiConnectionMapper connectionMapper;
    private final PlatformUserMapper userMapper;
    private final PlatformAuthorizationService authorizationService;
    private final PlatformSessionTokenCodec tokenCodec;
    private final PlatformAuthAuditService auditService;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public CreatedConnection create(PlatformAuthenticatedSession actor, CreateConnection command) {
        Long owner = requireOwner(actor);
        if (command == null) throw badRequest("connection is required");
        String label = command.label() == null ? "" : command.label().trim();
        if (label.isEmpty() || label.length() > 128) throw badRequest("label must contain 1-128 characters");
        String kind = command.toolKind() == null ? "OTHER"
                : command.toolKind().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!TOOL_KINDS.contains(kind)) throw badRequest("unsupported AI tool kind");
        int days = command.ttlDays() == null ? MAX_TTL_DAYS : command.ttlDays();
        if (days < 1 || days > MAX_TTL_DAYS) throw badRequest("ttlDays must be between 1 and 30");

        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = TOKEN_PREFIX + HexFormat.of().formatHex(secret);
        LocalDateTime now = LocalDateTime.now();
        PlatformAiConnectionEntity entity = new PlatformAiConnectionEntity();
        entity.setUserId(owner);
        entity.setLabel(label);
        entity.setToolKind(kind);
        entity.setTokenHash(tokenCodec.digest(token));
        entity.setTokenHint(token.substring(0, 12));
        entity.setRolesJson(writeJson(actor.roles()));
        entity.setGrantsJson(writeJson(actor.permissionGrants()));
        entity.setStatus("ACTIVE");
        entity.setExpiresAt(now.plusDays(days));
        entity.setCreatedAt(now);
        connectionMapper.insert(entity);
        auditService.record(actor, "PLATFORM_AI_CONNECTION_CREATED", "PLATFORM_AI_CONNECTION",
                String.valueOf(entity.getId()), Map.of("toolKind", kind, "expiresAt", entity.getExpiresAt().toString()));
        return new CreatedConnection(view(entity, now), token);
    }

    public List<ConnectionView> list(PlatformAuthenticatedSession actor) {
        Long owner = requireOwner(actor);
        LocalDateTime now = LocalDateTime.now();
        return connectionMapper.selectList(new LambdaQueryWrapper<PlatformAiConnectionEntity>()
                        .eq(PlatformAiConnectionEntity::getUserId, owner)
                        .orderByDesc(PlatformAiConnectionEntity::getId))
                .stream().map(entity -> view(entity, now)).toList();
    }

    @Transactional
    public ConnectionView revoke(PlatformAuthenticatedSession actor, long id) {
        Long owner = requireOwner(actor);
        PlatformAiConnectionEntity entity = connectionMapper.selectById(id);
        if (entity == null || !Objects.equals(owner, entity.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI connection not found");
        }
        LocalDateTime now = LocalDateTime.now();
        int changed = connectionMapper.update(null, new LambdaUpdateWrapper<PlatformAiConnectionEntity>()
                .eq(PlatformAiConnectionEntity::getId, id)
                .eq(PlatformAiConnectionEntity::getUserId, owner)
                .eq(PlatformAiConnectionEntity::getStatus, "ACTIVE")
                .set(PlatformAiConnectionEntity::getStatus, "REVOKED")
                .set(PlatformAiConnectionEntity::getRevokedAt, now));
        if (changed > 0) {
            entity.setStatus("REVOKED");
            entity.setRevokedAt(now);
            auditService.record(actor, "PLATFORM_AI_CONNECTION_REVOKED", "PLATFORM_AI_CONNECTION",
                    String.valueOf(id), Map.of());
        } else {
            PlatformAiConnectionEntity current = connectionMapper.selectById(id);
            if (current != null) entity = current;
        }
        return view(entity, now);
    }

    /** Rechecked on each protocol request; malformed snapshots fail closed. */
    public Optional<ConnectionIdentity> authenticate(String token) {
        if (!StringUtils.hasText(token) || !token.startsWith(TOKEN_PREFIX) || token.length() != 68) {
            return Optional.empty();
        }
        PlatformAiConnectionEntity entity = connectionMapper.selectOne(new LambdaQueryWrapper<PlatformAiConnectionEntity>()
                .eq(PlatformAiConnectionEntity::getTokenHash, tokenCodec.digest(token))
                .last("limit 1"));
        LocalDateTime now = LocalDateTime.now();
        if (!usable(entity, now)) return Optional.empty();
        PlatformUserEntity user = userMapper.selectById(entity.getUserId());
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) return Optional.empty();
        List<String> originalRoles;
        List<PlatformPermissionGrant> originalGrants;
        try {
            originalRoles = objectMapper.readValue(entity.getRolesJson(), ROLES);
            originalGrants = objectMapper.readValue(entity.getGrantsJson(), GRANTS);
            if (originalRoles == null || originalGrants == null) return Optional.empty();
        } catch (IllegalArgumentException | JsonProcessingException invalid) {
            return Optional.empty();
        }
        PlatformLoginSessionEntity identityContext = new PlatformLoginSessionEntity();
        identityContext.setSessionId("pai_" + entity.getId());
        identityContext.setUserId(entity.getUserId());
        identityContext.setExpiresAt(entity.getExpiresAt());
        PlatformAuthenticatedSession current = authorizationService.authenticatedSession(user, identityContext);
        List<PlatformPermissionGrant> grants = PlatformAiDelegationGrants.intersect(
                originalGrants, current.permissionGrants());
        List<String> roles = PlatformAiDelegationGrants.roles(originalRoles, current.roles(), grants);
        PlatformAuthenticatedSession delegated = new PlatformAuthenticatedSession(
                current.user(), identityContext.getSessionId(), entity.getExpiresAt(), roles,
                grants.stream().map(PlatformPermissionGrant::permissionCode).distinct().toList(), grants);
        int touched = connectionMapper.update(null, new LambdaUpdateWrapper<PlatformAiConnectionEntity>()
                .eq(PlatformAiConnectionEntity::getId, entity.getId())
                .eq(PlatformAiConnectionEntity::getStatus, "ACTIVE")
                .isNull(PlatformAiConnectionEntity::getRevokedAt)
                .gt(PlatformAiConnectionEntity::getExpiresAt, now)
                .set(PlatformAiConnectionEntity::getLastUsedAt, now));
        return touched == 1 ? Optional.of(new ConnectionIdentity(entity.getId(), entity.getToolKind(), delegated))
                : Optional.empty();
    }

    /** Used together with platform-session revocation for an account. */
    public int revokeForUser(Long userId) {
        if (userId == null) return 0;
        return connectionMapper.update(null, new LambdaUpdateWrapper<PlatformAiConnectionEntity>()
                .eq(PlatformAiConnectionEntity::getUserId, userId)
                .eq(PlatformAiConnectionEntity::getStatus, "ACTIVE")
                .set(PlatformAiConnectionEntity::getStatus, "REVOKED")
                .set(PlatformAiConnectionEntity::getRevokedAt, LocalDateTime.now()));
    }

    private boolean usable(PlatformAiConnectionEntity entity, LocalDateTime now) {
        return entity != null && entity.getId() != null && entity.getUserId() != null
                && "ACTIVE".equals(entity.getStatus()) && entity.getRevokedAt() == null
                && entity.getExpiresAt() != null && now.isBefore(entity.getExpiresAt());
    }

    private ConnectionView view(PlatformAiConnectionEntity entity, LocalDateTime now) {
        String state = "ACTIVE".equals(entity.getStatus()) && entity.getExpiresAt() != null
                && !now.isBefore(entity.getExpiresAt()) ? "EXPIRED" : entity.getStatus();
        return new ConnectionView(entity.getId(), entity.getLabel(), entity.getToolKind(), entity.getTokenHint(),
                state, entity.getCreatedAt(), entity.getExpiresAt(), entity.getLastUsedAt(), entity.getRevokedAt());
    }

    private Long requireOwner(PlatformAuthenticatedSession actor) {
        if (actor == null || actor.user() == null || actor.user().getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform session is required");
        }
        return actor.user().getId();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("could not serialize platform delegation", failure);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record CreateConnection(String label, String toolKind, Integer ttlDays) { }
    public record ConnectionView(Long id, String label, String toolKind, String tokenHint, String status,
                                 LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime lastUsedAt,
                                 LocalDateTime revokedAt) { }
    public record CreatedConnection(ConnectionView connection, String token) { }
    public record ConnectionIdentity(Long connectionId, String toolKind, PlatformAuthenticatedSession session) { }
}
