package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ai.control.identity.PlatformAiApprovalCipher.Encrypted;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable, one-use human approval for an exact encrypted platform AI action. */
@Service
@RequiredArgsConstructor
public class PlatformAiApprovalService {
    public static final String BUSINESS_METHOD_WRITE = "BUSINESS_METHOD_WRITE";
    public static final String HTTP_API_WRITE = "HTTP_API_WRITE";
    public static final String AGENT_PUBLISH = "AGENT_PUBLISH";
    public static final String WORKFLOW_PUBLISH = "WORKFLOW_PUBLISH";
    public static final String KNOWLEDGE_SETTINGS_WRITE = "KNOWLEDGE_SETTINGS_WRITE";
    public static final String PAGE_ASSISTANT_SYNC = "PAGE_ASSISTANT_SYNC";
    public static final String WORKFLOW_ROLLBACK = "WORKFLOW_ROLLBACK";
    public static final String KNOWLEDGE_IMPORT_COMMIT = "KNOWLEDGE_IMPORT_COMMIT";
    public static final String KNOWLEDGE_IMPORT_CANCEL = "KNOWLEDGE_IMPORT_CANCEL";
    public static final String SKILL_VERSION_GOVERNANCE = "SKILL_VERSION_GOVERNANCE";
    public static final String TOOL_ACL_CHANGE = "TOOL_ACL_CHANGE";
    public static final String BUSINESS_SYSTEM_CHANGE = "BUSINESS_SYSTEM_CHANGE";
    public static final String KNOWLEDGE_CHUNK_CHANGE = "KNOWLEDGE_CHUNK_CHANGE";
    public static final String MODEL_INSTANCE_CHANGE = "MODEL_INSTANCE_CHANGE";
    public static final String MODEL_PROVIDER_ACCOUNT_CHANGE = "MODEL_PROVIDER_ACCOUNT_CHANGE";
    public static final String MCP_PUBLICATION_CHANGE = "MCP_PUBLICATION_CHANGE";
    public static final String AUTOMATION_CHANGE = "AUTOMATION_CHANGE";
    public static final String RUN_REPLAY = "RUN_REPLAY";
    public static final String ACCOUNT_CHANGE = "ACCOUNT_CHANGE";
    public static final String BUSINESS_INDEX_CHANGE = "BUSINESS_INDEX_CHANGE";
    public static final String AGENT_LIFECYCLE_CHANGE = "AGENT_LIFECYCLE_CHANGE";
    public static final String HTTP_API_CONNECTION_CHANGE = "HTTP_API_CONNECTION_CHANGE";
    public static final String IDENTITY_MAPPING_CHANGE = "IDENTITY_MAPPING_CHANGE";
    public static final String KNOWLEDGE_DELETE = "KNOWLEDGE_DELETE";
    public static final String CAPABILITY_CHANGE_REVIEW = "CAPABILITY_CHANGE_REVIEW";
    public static final String EMBED_CHANGE = "EMBED_CHANGE";
    public static final String API_GRAPH_CHANGE = "API_GRAPH_CHANGE";
    public static final String WORKFLOW_CREDENTIAL_CHANGE = "WORKFLOW_CREDENTIAL_CHANGE";
    public static final String MEMORY_ERASURE_REQUEST = "MEMORY_ERASURE_REQUEST";
    public static final String AGENT_WORKFLOW_PUBLISH = "AGENT_WORKFLOW_PUBLISH";
    public static final String PAGE_WORKFLOW_DELIVERY = "PAGE_WORKFLOW_DELIVERY";
    public static final String REGISTRY_ENROLLMENT = "REGISTRY_ENROLLMENT";
    private static final Set<String> ACTIONS = Set.of(BUSINESS_METHOD_WRITE, HTTP_API_WRITE, AGENT_PUBLISH, WORKFLOW_PUBLISH,
            KNOWLEDGE_SETTINGS_WRITE, PAGE_ASSISTANT_SYNC, WORKFLOW_ROLLBACK, KNOWLEDGE_IMPORT_COMMIT, KNOWLEDGE_IMPORT_CANCEL, SKILL_VERSION_GOVERNANCE,
            TOOL_ACL_CHANGE, BUSINESS_SYSTEM_CHANGE, KNOWLEDGE_CHUNK_CHANGE, MODEL_INSTANCE_CHANGE, REGISTRY_ENROLLMENT,
            MODEL_PROVIDER_ACCOUNT_CHANGE, MCP_PUBLICATION_CHANGE, AUTOMATION_CHANGE, RUN_REPLAY, ACCOUNT_CHANGE, BUSINESS_INDEX_CHANGE, AGENT_LIFECYCLE_CHANGE, HTTP_API_CONNECTION_CHANGE, IDENTITY_MAPPING_CHANGE, KNOWLEDGE_DELETE, CAPABILITY_CHANGE_REVIEW, EMBED_CHANGE, API_GRAPH_CHANGE, WORKFLOW_CREDENTIAL_CHANGE, MEMORY_ERASURE_REQUEST, AGENT_WORKFLOW_PUBLISH, PAGE_WORKFLOW_DELIVERY);
    private static final Set<String> OUTCOMES = Set.of("SUCCEEDED", "FAILED", "UNKNOWN");
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final int MAX_PENDING = 20;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final PlatformAiApprovalMapper approvals;
    private final PlatformAiConnectionMapper connections;
    private final PlatformAiApprovalCipher cipher;
    private final PlatformAuthAuditService audit;
    private final ObjectMapper json;

    @Transactional
    public ApprovalView prepare(ConnectionIdentity identity, String action, String subject, Map<String, Object> arguments) {
        Long owner = requireIdentity(identity);
        if (!ACTIONS.contains(action) || subject == null || !subject.matches("[A-Za-z0-9._:-]{1,200}")) {
            throw badRequest("approval action or subject is invalid");
        }
        byte[] payload = serialize(arguments);
        int payloadLimit = AGENT_PUBLISH.equals(action) || WORKFLOW_PUBLISH.equals(action) ? 512 * 1024 : MAX_PAYLOAD_BYTES;
        if (payload.length > payloadLimit) throw badRequest("approval payload is too large");
        String requestKey = requestKey(arguments);
        String digest = cipher.fingerprint(fingerprintContext(identity.connectionId(), owner, action, subject), payload);
        PlatformAiApprovalEntity existing = approvals.findByRequestForUpdate(identity.connectionId(), action, requestKey);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) return duplicate(existing, digest, now);
        Long pending = approvals.selectCount(new LambdaQueryWrapper<PlatformAiApprovalEntity>()
                .eq(PlatformAiApprovalEntity::getOwnerUserId, owner)
                .in(PlatformAiApprovalEntity::getStatus, "PENDING", "APPROVED")
                .gt(PlatformAiApprovalEntity::getExpiresAt, now));
        if (pending != null && pending >= MAX_PENDING) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "too many pending platform AI approvals");
        String id = UUID.randomUUID().toString();
        Encrypted encrypted = cipher.encrypt(payload, aad(id, identity.connectionId(), owner, action, subject));
        var entity = new PlatformAiApprovalEntity();
        entity.setId(id); entity.setConnectionId(identity.connectionId()); entity.setOwnerUserId(owner);
        entity.setAction(action); entity.setSubject(subject); entity.setRequestKey(requestKey); entity.setRequestDigest(digest);
        entity.setPayloadKeyId(encrypted.keyId()); entity.setPayloadNonce(encrypted.nonce());
        entity.setPayloadCiphertext(encrypted.ciphertext()); entity.setStatus("PENDING");
        entity.setCreatedAt(now); entity.setExpiresAt(now.plusMinutes(30));
        try {
            approvals.insert(entity);
        } catch (DuplicateKeyException conflict) {
            PlatformAiApprovalEntity duplicate = approvals.findByRequestForUpdate(identity.connectionId(), action, requestKey);
            if (duplicate == null) throw conflict;
            return duplicate(duplicate, digest, now);
        }
        audit.record(identity.session(), "PLATFORM_AI_APPROVAL_REQUESTED", "PLATFORM_AI_APPROVAL", id,
                Map.of("connectionId", identity.connectionId(), "action", action));
        return view(entity, now, null);
    }

    public List<ApprovalView> list(PlatformAuthenticatedSession actor) {
        Long owner = requireOwner(actor);
        LocalDateTime now = LocalDateTime.now();
        return approvals.selectList(new LambdaQueryWrapper<PlatformAiApprovalEntity>()
                        .eq(PlatformAiApprovalEntity::getOwnerUserId, owner)
                        .orderByDesc(PlatformAiApprovalEntity::getCreatedAt).last("limit 100"))
                .stream().map(entity -> view(entity, now, null)).toList();
    }

    public ApprovalView review(PlatformAuthenticatedSession actor, String id) {
        PlatformAiApprovalEntity entity = owned(requireOwner(actor), id);
        LocalDateTime now = LocalDateTime.now();
        String state = visibleStatus(entity, now);
        Object arguments = ("PENDING".equals(state) || "APPROVED".equals(state)) ? payload(entity) : null;
        return view(entity, now, arguments);
    }

    @Transactional
    public ApprovalView decide(PlatformAuthenticatedSession actor, String id, String expectedDigest, boolean approve) {
        Long owner = requireOwner(actor);
        PlatformAiApprovalEntity entity = owned(owner, id);
        LocalDateTime now = LocalDateTime.now();
        if (!constantEquals(entity.getRequestDigest(), expectedDigest)) throw conflict("approval changed; review it again");
        if (approve && !connectionActive(entity.getConnectionId(), owner, now)) throw conflict("AI connection is no longer active");
        LambdaUpdateWrapper<PlatformAiApprovalEntity> update = new LambdaUpdateWrapper<PlatformAiApprovalEntity>()
                .eq(PlatformAiApprovalEntity::getId, id).eq(PlatformAiApprovalEntity::getOwnerUserId, owner)
                .eq(PlatformAiApprovalEntity::getStatus, "PENDING").gt(PlatformAiApprovalEntity::getExpiresAt, now)
                .set(PlatformAiApprovalEntity::getStatus, approve ? "APPROVED" : "REJECTED")
                .set(PlatformAiApprovalEntity::getDecidedAt, now);
        if (!approve) clearPayload(update);
        if (approvals.update(null, update) != 1) throw conflict("approval is no longer pending");
        audit.record(actor, approve ? "PLATFORM_AI_APPROVAL_APPROVED" : "PLATFORM_AI_APPROVAL_REJECTED",
                "PLATFORM_AI_APPROVAL", id, Map.of("action", entity.getAction(), "connectionId", entity.getConnectionId()));
        return view(owned(owner, id), now, null);
    }

    /** Atomically consumes the approval before dispatch; a crashed dispatch is never replayed. */
    @Transactional
    public Claim claim(ConnectionIdentity identity, String id, String action) {
        Long owner = requireIdentity(identity);
        if (!ACTIONS.contains(action)) throw badRequest("approval action is invalid");
        PlatformAiApprovalEntity entity = owned(owner, id);
        LocalDateTime now = LocalDateTime.now();
        if (!identity.connectionId().equals(entity.getConnectionId()) || !action.equals(entity.getAction())
                || !"APPROVED".equals(visibleStatus(entity, now))
                || !connectionActive(entity.getConnectionId(), owner, now)) throw conflict("approval is not executable");
        Map<String, Object> arguments = payload(entity);
        String actualDigest = cipher.fingerprint(fingerprintContext(entity.getConnectionId(), owner, action, entity.getSubject()), serialize(arguments));
        if (!constantEquals(entity.getRequestDigest(), actualDigest)
                || !constantEquals(entity.getRequestKey(), requestKey(arguments))) throw conflict("approval payload changed");
        var update = new LambdaUpdateWrapper<PlatformAiApprovalEntity>()
                .eq(PlatformAiApprovalEntity::getId, id).eq(PlatformAiApprovalEntity::getOwnerUserId, owner)
                .eq(PlatformAiApprovalEntity::getConnectionId, identity.connectionId())
                .eq(PlatformAiApprovalEntity::getAction, action).eq(PlatformAiApprovalEntity::getStatus, "APPROVED")
                .gt(PlatformAiApprovalEntity::getExpiresAt, now)
                .set(PlatformAiApprovalEntity::getStatus, "EXECUTING").set(PlatformAiApprovalEntity::getStartedAt, now);
        clearPayload(update);
        if (approvals.update(null, update) != 1) throw conflict("approval was already consumed");
        audit.record(identity.session(), "PLATFORM_AI_APPROVAL_EXECUTING", "PLATFORM_AI_APPROVAL", id,
                Map.of("action", action, "connectionId", identity.connectionId()));
        return new Claim(id, action, entity.getSubject(), arguments);
    }

    @Transactional
    public void finish(ConnectionIdentity identity, String id, String outcome, String resultCode) {
        Long owner = requireIdentity(identity);
        if (!OUTCOMES.contains(outcome) || (resultCode != null && !resultCode.matches("[A-Z0-9_]{1,96}"))) {
            throw badRequest("approval outcome is invalid");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = approvals.update(null, new LambdaUpdateWrapper<PlatformAiApprovalEntity>()
                .eq(PlatformAiApprovalEntity::getId, id).eq(PlatformAiApprovalEntity::getOwnerUserId, owner)
                .eq(PlatformAiApprovalEntity::getConnectionId, identity.connectionId())
                .eq(PlatformAiApprovalEntity::getStatus, "EXECUTING")
                .set(PlatformAiApprovalEntity::getStatus, outcome).set(PlatformAiApprovalEntity::getResultCode, resultCode)
                .set(PlatformAiApprovalEntity::getCompletedAt, now));
        if (updated != 1) throw conflict("approval execution state changed");
        audit.record(identity.session(), "PLATFORM_AI_APPROVAL_FINISHED", "PLATFORM_AI_APPROVAL", id,
                Map.of("outcome", outcome, "connectionId", identity.connectionId()));
    }

    public ApprovalView status(ConnectionIdentity identity, String id) {
        PlatformAiApprovalEntity entity = owned(requireIdentity(identity), id);
        if (!identity.connectionId().equals(entity.getConnectionId())) throw notFound();
        return view(entity, LocalDateTime.now(), null);
    }

    /** Metadata-only recovery when creation returned no approval ID. Never includes the review contents. */
    public List<ApprovalView> findRequests(ConnectionIdentity identity, String invocationId) {
        Long owner = requireIdentity(identity);
        if (invocationId != null) requestKey(Map.of("invocationId", invocationId));
        LocalDateTime now = LocalDateTime.now();
        return approvals.selectList(new LambdaQueryWrapper<PlatformAiApprovalEntity>()
                        .eq(PlatformAiApprovalEntity::getOwnerUserId, owner)
                        .eq(PlatformAiApprovalEntity::getConnectionId, identity.connectionId())
                        .eq(invocationId != null, PlatformAiApprovalEntity::getRequestKey, invocationId)
                        .orderByDesc(PlatformAiApprovalEntity::getCreatedAt).orderByDesc(PlatformAiApprovalEntity::getId).last("limit 100"))
                .stream().map(entity -> view(entity, now, null)).toList();
    }

    @Scheduled(fixedDelayString = "${reachai.platform-ai.approval-cleanup-delay-ms:300000}")
    public void expireLapsed() {
        LocalDateTime now = LocalDateTime.now();
        var update = new LambdaUpdateWrapper<PlatformAiApprovalEntity>()
                .in(PlatformAiApprovalEntity::getStatus, "PENDING", "APPROVED")
                .le(PlatformAiApprovalEntity::getExpiresAt, now)
                .set(PlatformAiApprovalEntity::getStatus, "EXPIRED");
        clearPayload(update);
        approvals.update(null, update);
        // An interrupted dispatcher is never replayed. Preserve the invocation ID for investigation.
        approvals.update(null, new LambdaUpdateWrapper<PlatformAiApprovalEntity>()
                .eq(PlatformAiApprovalEntity::getStatus, "EXECUTING")
                .le(PlatformAiApprovalEntity::getStartedAt, now.minusMinutes(5))
                .set(PlatformAiApprovalEntity::getStatus, "UNKNOWN")
                .set(PlatformAiApprovalEntity::getResultCode, "PLATFORM_APPROVAL_EXECUTION_UNKNOWN")
                .set(PlatformAiApprovalEntity::getCompletedAt, now));
    }

    private boolean connectionActive(Long id, Long owner, LocalDateTime now) {
        PlatformAiConnectionEntity connection = id == null ? null : connections.selectById(id);
        return connection != null && owner.equals(connection.getUserId()) && "ACTIVE".equals(connection.getStatus())
                && connection.getRevokedAt() == null && connection.getExpiresAt() != null && now.isBefore(connection.getExpiresAt());
    }

    private PlatformAiApprovalEntity owned(Long owner, String id) {
        if (id == null || id.length() > 64) throw notFound();
        PlatformAiApprovalEntity entity = approvals.selectById(id);
        if (entity == null || !owner.equals(entity.getOwnerUserId())) throw notFound();
        return entity;
    }

    private Map<String, Object> payload(PlatformAiApprovalEntity entity) {
        try {
            byte[] plaintext = cipher.decrypt(new Encrypted(entity.getPayloadKeyId(), entity.getPayloadNonce(),
                    entity.getPayloadCiphertext()), aad(entity.getId(), entity.getConnectionId(), entity.getOwnerUserId(),
                    entity.getAction(), entity.getSubject()));
            return json.readValue(plaintext, MAP);
        } catch (java.io.IOException invalid) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "approval payload is invalid");
        }
    }

    private byte[] serialize(Map<String, Object> arguments) {
        if (arguments == null) throw badRequest("approval arguments are required");
        try {
            return json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).writeValueAsBytes(arguments);
        } catch (Exception invalid) {
            throw badRequest("approval arguments are invalid");
        }
    }

    private ApprovalView duplicate(PlatformAiApprovalEntity existing, String digest, LocalDateTime now) {
        if (!constantEquals(existing.getRequestDigest(), digest)) {
            throw conflict("invocationId is already bound to a different approval");
        }
        return view(existing, now, null);
    }

    private String requestKey(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("invocationId");
        if (!(value instanceof String text)) throw badRequest("invocationId is required");
        try {
            if (!UUID.fromString(text).toString().equals(text)) throw new IllegalArgumentException("noncanonical UUID");
            return text;
        } catch (IllegalArgumentException invalid) {
            throw badRequest("invocationId must be a canonical UUID");
        }
    }

    private ApprovalView view(PlatformAiApprovalEntity entity, LocalDateTime now, Object arguments) {
        return new ApprovalView(entity.getId(), entity.getConnectionId(), entity.getAction(), entity.getSubject(),
                entity.getRequestKey(), visibleStatus(entity, now), entity.getRequestDigest(), arguments, entity.getResultCode(),
                entity.getCreatedAt(), entity.getExpiresAt(), entity.getDecidedAt(), entity.getCompletedAt());
    }

    private String visibleStatus(PlatformAiApprovalEntity entity, LocalDateTime now) {
        return ("PENDING".equals(entity.getStatus()) || "APPROVED".equals(entity.getStatus()))
                && (entity.getExpiresAt() == null || !now.isBefore(entity.getExpiresAt())) ? "EXPIRED" : entity.getStatus();
    }

    private void clearPayload(LambdaUpdateWrapper<PlatformAiApprovalEntity> update) {
        update.set(PlatformAiApprovalEntity::getPayloadCiphertext, null)
                .set(PlatformAiApprovalEntity::getPayloadNonce, null).set(PlatformAiApprovalEntity::getPayloadKeyId, null);
    }

    private Long requireIdentity(ConnectionIdentity identity) {
        if (identity == null || identity.connectionId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return requireOwner(identity.session());
    }

    private Long requireOwner(PlatformAuthenticatedSession actor) {
        if (actor == null || actor.user() == null || actor.user().getId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return actor.user().getId();
    }

    private static String aad(String id, Long connectionId, Long owner, String action, String subject) {
        return "platform-ai-approval:v1:" + id + ":" + connectionId + ":" + owner + ":" + action + ":" + subject;
    }

    private static String fingerprintContext(Long connectionId, Long owner, String action, String subject) {
        return "platform-ai-approval:v1:" + connectionId + ":" + owner + ":" + action + ":" + subject;
    }

    private static boolean constantEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "approval not found"); }

    public record Claim(String id, String action, String subject, Map<String, Object> arguments) { }
    public record ApprovalView(String id, Long connectionId, String action, String subject, String invocationId, String status,
                               String requestDigest, Object arguments, String resultCode, LocalDateTime createdAt,
                               LocalDateTime expiresAt, LocalDateTime decidedAt, LocalDateTime completedAt) { }
}
