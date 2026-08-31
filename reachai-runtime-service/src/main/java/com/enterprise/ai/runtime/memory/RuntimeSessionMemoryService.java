package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.execution.RuntimeSessionClearPort;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class RuntimeSessionMemoryService implements RuntimeSessionClearPort {

    public static final String STATE_NAME = "agent_state";
    private static final String DEFAULT_TENANT = "default";

    private final AgentStateStore stateStore;
    private final RuntimeConversationSessionMapper sessionMapper;
    private final RuntimeConversationEventMapper eventMapper;
    private final RuntimeSessionMemoryProperties properties;
    private final RuntimeToolResultArtifactService toolResultArtifactService;
    private final RuntimeSessionRetentionStore retentionStore;
    private final RuntimeSessionStateEraser stateEraser;

    @Autowired
    public RuntimeSessionMemoryService(
            AgentStateStore stateStore,
            RuntimeConversationSessionMapper sessionMapper,
            RuntimeConversationEventMapper eventMapper,
            RuntimeSessionMemoryProperties properties,
            ObjectProvider<RuntimeToolResultArtifactService> toolResultArtifactServiceProvider,
            ObjectProvider<RuntimeSessionRetentionStore> retentionStoreProvider,
            ObjectProvider<RuntimeSessionStateEraser> stateEraserProvider) {
        this(stateStore, sessionMapper, eventMapper, properties,
                toolResultArtifactServiceProvider.getIfAvailable(),
                retentionStoreProvider.getIfAvailable(),
                stateEraserProvider.getIfAvailable());
    }

    /** Compatibility constructor for isolated tests and transient Runtime use. */
    public RuntimeSessionMemoryService(
            AgentStateStore stateStore,
            RuntimeConversationSessionMapper sessionMapper,
            RuntimeConversationEventMapper eventMapper,
            RuntimeSessionMemoryProperties properties) {
        this(stateStore, sessionMapper, eventMapper, properties,
                (RuntimeToolResultArtifactService) null,
                (RuntimeSessionRetentionStore) null,
                (RuntimeSessionStateEraser) null);
    }

    RuntimeSessionMemoryService(
            AgentStateStore stateStore,
            RuntimeConversationSessionMapper sessionMapper,
            RuntimeConversationEventMapper eventMapper,
            RuntimeSessionMemoryProperties properties,
            RuntimeToolResultArtifactService toolResultArtifactService,
            RuntimeSessionRetentionStore retentionStore,
            RuntimeSessionStateEraser stateEraser) {
        this.stateStore = stateStore;
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
        this.properties = properties;
        this.toolResultArtifactService = toolResultArtifactService;
        this.retentionStore = retentionStore;
        this.stateEraser = stateEraser;
    }

    public static RuntimeSessionMemoryService transientOnly() {
        return new RuntimeSessionMemoryService(
                new InMemoryAgentStateStore(), null, null,
                new RuntimeSessionMemoryProperties(
                        false, "in-memory", "", "", "", false, 40, 65_535, 600));
    }

    public RuntimeSessionMemoryKey resolve(RuntimeAgentView agent,
                                           String requestedSessionId,
                                           WorkflowExecutionIdentity identity) {
        return resolve(agent, requestedSessionId, identity, true);
    }

    /** Eval executions keep an isolated in-process turn and never read or write durable chat memory. */
    public RuntimeSessionMemoryKey resolveTransient(RuntimeAgentView agent,
                                                    String requestedSessionId,
                                                    WorkflowExecutionIdentity identity) {
        return resolve(agent, requestedSessionId, identity, false);
    }

    private RuntimeSessionMemoryKey resolve(RuntimeAgentView agent,
                                            String requestedSessionId,
                                            WorkflowExecutionIdentity identity,
                                            boolean allowPersistence) {
        String publicSessionId = StringUtils.hasText(requestedSessionId)
                ? requestedSessionId.trim()
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        if (publicSessionId.length() > 128 || publicSessionId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("sessionId must be at most 128 characters without control characters");
        }
        String agentId = agent == null ? "unknown-agent" : normalized(agent.id(), "unknown-agent");
        boolean trusted = allowPersistence
                && properties.enabled()
                && identity != null
                && identity.canResolveUserAcl();
        if (!trusted) {
            return new RuntimeSessionMemoryKey(false, DEFAULT_TENANT, null, agentId, publicSessionId,
                    null, null, identity == null ? "UNTRUSTED" : identity.source().name());
        }
        String tenantId = normalized(identity.tenantId(), DEFAULT_TENANT);
        String trustedUserId = identity.userId().trim();
        if (tenantId.length() > 96 || trustedUserId.length() > 128) {
            throw new IllegalArgumentException("trusted tenantId or userId exceeds the persistence contract");
        }
        String userKey = "u:" + sha256(tenantId + "\n" + trustedUserId);
        String sessionKey = "a:" + sha256(agentId) + ":s:" + sha256(publicSessionId);
        return new RuntimeSessionMemoryKey(true, tenantId, trustedUserId, agentId, publicSessionId,
                userKey, sessionKey, identity.source().name());
    }

    public AgentStateStore stateStore() {
        return stateStore;
    }

    /**
     * AgentScope persists its own state before the Runtime turn ledger is finalized. Guard that
     * write with the exact database lease owner so a timed-out/zombie execution cannot recreate
     * state after another worker has claimed CLEARING/PURGING or a newer turn has taken the lease.
     */
    public AgentStateStore stateStoreForTurn(RuntimeSessionMemoryKey key, String turnLeaseOwner) {
        if (key == null || !key.persistent()) {
            return null;
        }
        if (!StringUtils.hasText(turnLeaseOwner)) {
            throw new RuntimeSessionBusyException(
                    "persistent AgentScope state requires an active turn lease");
        }
        return new TurnFencedAgentStateStore(stateStore, key, turnLeaseOwner.trim());
    }

    @Transactional
    public void prepare(RuntimeSessionMemoryKey key,
                        RuntimeAgentView agent,
                        Long agentConfigVersionId) {
        if (key != null && key.persistent()) {
            requireSession(key, agent, agentConfigVersionId);
        }
    }

    @Transactional
    public String acquireTurn(RuntimeSessionMemoryKey key,
                              RuntimeAgentView agent,
                              Long agentConfigVersionId) {
        if (key == null || !key.persistent()) {
            return null;
        }
        RuntimeConversationSessionEntity session = requireSession(key, agent, agentConfigVersionId);
        String owner = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        int updated = sessionMapper.update(null, new LambdaUpdateWrapper<RuntimeConversationSessionEntity>()
                .eq(RuntimeConversationSessionEntity::getId, session.getId())
                .eq(RuntimeConversationSessionEntity::getStatus, RuntimeSessionRetentionStore.ACTIVE)
                .and(wrapper -> wrapper.isNull(RuntimeConversationSessionEntity::getTurnLeaseExpiresAt)
                        .or().lt(RuntimeConversationSessionEntity::getTurnLeaseExpiresAt, now))
                .set(RuntimeConversationSessionEntity::getTurnLeaseOwner, owner)
                .set(RuntimeConversationSessionEntity::getTurnLeaseExpiresAt,
                        now.plusSeconds(properties.turnLeaseSeconds()))
                .set(RuntimeConversationSessionEntity::getUpdatedAt, now));
        if (updated != 1) {
            throw new RuntimeSessionBusyException("another turn is already running for this session");
        }
        // Bound the persisted AgentScope state before the model can load it. Merely
        // windowing helper reads is insufficient because ReActAgent reads the state
        // store directly at turn start.
        try {
            trimPersistentState(key, owner);
            return owner;
        } catch (RuntimeException failure) {
            try {
                releaseTurn(key, owner);
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    public void releaseTurn(RuntimeSessionMemoryKey key, String owner) {
        if (key == null || !key.persistent() || !StringUtils.hasText(owner)) {
            return;
        }
        sessionMapper.update(null, new LambdaUpdateWrapper<RuntimeConversationSessionEntity>()
                .eq(RuntimeConversationSessionEntity::getTenantId, key.tenantId())
                .eq(RuntimeConversationSessionEntity::getSessionId, key.publicSessionId())
                .eq(RuntimeConversationSessionEntity::getTurnLeaseOwner, owner)
                .eq(RuntimeConversationSessionEntity::getStatus, RuntimeSessionRetentionStore.ACTIVE)
                .set(RuntimeConversationSessionEntity::getTurnLeaseOwner, null)
                .set(RuntimeConversationSessionEntity::getTurnLeaseExpiresAt, null)
                .set(RuntimeConversationSessionEntity::getUpdatedAt, LocalDateTime.now()));
    }

    /** Renews and proves ownership before any turn-scoped external state read/write side effect. */
    public void renewTurnLease(RuntimeSessionMemoryKey key, String owner) {
        if (key == null || !key.persistent() || !StringUtils.hasText(owner)) {
            throw new RuntimeSessionBusyException(
                    "persistent turn operation requires an active turn lease");
        }
        LocalDateTime now = LocalDateTime.now();
        int renewed = sessionMapper.update(null,
                new LambdaUpdateWrapper<RuntimeConversationSessionEntity>()
                        .eq(RuntimeConversationSessionEntity::getTenantId, key.tenantId())
                        .eq(RuntimeConversationSessionEntity::getSessionId, key.publicSessionId())
                        .eq(RuntimeConversationSessionEntity::getStatus,
                                RuntimeSessionRetentionStore.ACTIVE)
                        .eq(RuntimeConversationSessionEntity::getTurnLeaseOwner, owner)
                        .set(RuntimeConversationSessionEntity::getTurnLeaseExpiresAt,
                                now.plusSeconds(properties.turnLeaseSeconds()))
                        .set(RuntimeConversationSessionEntity::getUpdatedAt, now));
        if (renewed != 1) {
            throw new RuntimeSessionBusyException(
                    "turn lease was lost before AgentScope state could be persisted");
        }
    }

    private final class TurnFencedAgentStateStore implements AgentStateStore {

        private final AgentStateStore delegate;
        private final RuntimeSessionMemoryKey key;
        private final String owner;

        private TurnFencedAgentStateStore(
                AgentStateStore delegate, RuntimeSessionMemoryKey key, String owner) {
            this.delegate = delegate;
            this.key = key;
            this.owner = owner;
        }

        @Override
        public void save(String userId,
                         String sessionId,
                         String stateName,
                         io.agentscope.core.state.State state) {
            requireExactStateSlot(userId, sessionId);
            renewTurnLease(key, owner);
            delegate.save(userId, sessionId, stateName, state);
        }

        @Override
        public void save(String userId,
                         String sessionId,
                         String stateName,
                         List<? extends io.agentscope.core.state.State> states) {
            requireExactStateSlot(userId, sessionId);
            renewTurnLease(key, owner);
            delegate.save(userId, sessionId, stateName, states);
        }

        @Override
        public <T extends io.agentscope.core.state.State> Optional<T> get(
                String userId, String sessionId, String stateName, Class<T> stateType) {
            requireExactStateSlot(userId, sessionId);
            return delegate.get(userId, sessionId, stateName, stateType);
        }

        @Override
        public <T extends io.agentscope.core.state.State> List<T> getList(
                String userId, String sessionId, String stateName, Class<T> stateType) {
            requireExactStateSlot(userId, sessionId);
            return delegate.getList(userId, sessionId, stateName, stateType);
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            requireExactStateSlot(userId, sessionId);
            return delegate.exists(userId, sessionId);
        }

        @Override
        public void delete(String userId, String sessionId) {
            requireExactStateSlot(userId, sessionId);
            renewTurnLease(key, owner);
            delegate.delete(userId, sessionId);
        }

        @Override
        public void delete(String userId, String sessionId, String stateName) {
            requireExactStateSlot(userId, sessionId);
            renewTurnLease(key, owner);
            delegate.delete(userId, sessionId, stateName);
        }

        @Override
        public Set<String> listSessionIds(String userId) {
            if (!key.stateUserKey().equals(userId)) {
                throw new RuntimeSessionOwnershipException(
                        "AgentScope state access escaped the leased user slot");
            }
            return delegate.listSessionIds(userId);
        }

        @Override
        public void close() {
            // The delegate is application-scoped and must not be closed by one ReActAgent turn.
        }

        private void requireExactStateSlot(String userId, String sessionId) {
            if (!key.stateUserKey().equals(userId)
                    || !key.stateSessionKey().equals(sessionId)) {
                throw new RuntimeSessionOwnershipException(
                        "AgentScope state access escaped the leased session slot");
            }
        }
    }

    public List<Msg> history(RuntimeSessionMemoryKey key) {
        if (key == null || !key.persistent()) {
            return List.of();
        }
        return stateStore.get(key.stateUserKey(), key.stateSessionKey(), STATE_NAME, AgentState.class)
                .map(AgentState::getContext)
                .map(this::window)
                .orElseGet(List::of);
    }

    @Transactional
    public void recordTurn(RuntimeSessionMemoryKey key,
                           RuntimeAgentView agent,
                           Long agentConfigVersionId,
                           String traceId,
                           String turnId,
                           String userMessage,
                           String assistantReply,
                           String outcomeCode,
                           boolean success,
                           boolean waiting,
                           boolean agentStateContainsTurn) {
        recordTurn(key, agent, agentConfigVersionId, traceId, turnId,
                userMessage, assistantReply, outcomeCode, success, waiting,
                agentStateContainsTurn, null);
    }

    /**
     * Production fencing overload. The turn owner acquired before execution must
     * still own the ACTIVE row before any event or AgentState write is committed.
     */
    @Transactional
    public void recordTurn(RuntimeSessionMemoryKey key,
                           RuntimeAgentView agent,
                           Long agentConfigVersionId,
                           String traceId,
                           String turnId,
                           String userMessage,
                           String assistantReply,
                           String outcomeCode,
                           boolean success,
                           boolean waiting,
                           boolean agentStateContainsTurn,
                           String turnLeaseOwner) {
        if (key == null || !key.persistent()) {
            return;
        }
        RuntimeConversationSessionEntity session = requireSession(key, agent, agentConfigVersionId);
        if (StringUtils.hasText(turnLeaseOwner)) {
            LocalDateTime fenceTime = LocalDateTime.now();
            int fenced = sessionMapper.update(null,
                    new LambdaUpdateWrapper<RuntimeConversationSessionEntity>()
                            .eq(RuntimeConversationSessionEntity::getId, session.getId())
                            .eq(RuntimeConversationSessionEntity::getStatus,
                                    RuntimeSessionRetentionStore.ACTIVE)
                            .eq(RuntimeConversationSessionEntity::getTurnLeaseOwner,
                                    turnLeaseOwner.trim())
                            .set(RuntimeConversationSessionEntity::getTurnLeaseExpiresAt,
                                    fenceTime.plusSeconds(properties.turnLeaseSeconds()))
                            .set(RuntimeConversationSessionEntity::getUpdatedAt, fenceTime));
            if (fenced != 1) {
                throw new RuntimeSessionBusyException(
                        "turn lease was lost before session memory could be persisted");
            }
        }
        String durableTurnId = StringUtils.hasText(turnId)
                ? turnId.trim()
                : sha256(text(traceId) + "\n" + text(userMessage) + "\n" + text(assistantReply));
        if (eventExists(session.getId(), durableTurnId)) {
            return;
        }
        int next = session.getEventCount() == null ? 1 : session.getEventCount() + 1;
        String eventType = waiting ? "WAITING" : (success ? "MESSAGE" : "FAILED");
        insertEvent(session.getId(), next++, traceId, durableTurnId, eventType, "user", userMessage,
                Map.of("outcomeCode", text(outcomeCode), "waiting", waiting));
        insertEvent(session.getId(), next++, traceId, durableTurnId, eventType, "assistant",
                assistantReply, Map.of("outcomeCode", text(outcomeCode), "waiting", waiting));
        if (success || waiting) {
            syncVisibleTurnToState(key, userMessage, assistantReply, agentStateContainsTurn);
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<RuntimeConversationSessionEntity> sessionUpdate =
                new LambdaUpdateWrapper<RuntimeConversationSessionEntity>()
                .eq(RuntimeConversationSessionEntity::getId, session.getId())
                .eq(RuntimeConversationSessionEntity::getStatus,
                        RuntimeSessionRetentionStore.ACTIVE)
                .set(RuntimeConversationSessionEntity::getEventCount, next - 1)
                .set(RuntimeConversationSessionEntity::getLastTurnAt, now)
                .set(RuntimeConversationSessionEntity::getUpdatedAt, now);
        if (StringUtils.hasText(turnLeaseOwner)) {
            sessionUpdate.eq(RuntimeConversationSessionEntity::getTurnLeaseOwner,
                    turnLeaseOwner.trim());
        }
        int updated = sessionMapper.update(null, sessionUpdate);
        if (StringUtils.hasText(turnLeaseOwner) && updated != 1) {
            throw new RuntimeSessionBusyException(
                    "turn lease was lost while session memory was being persisted");
        }
        session.setEventCount(next - 1);
    }

    @Transactional
    public void clear(String publicSessionId, WorkflowExecutionIdentity identity) {
        if (!StringUtils.hasText(publicSessionId) || identity == null || !identity.canResolveUserAcl()) {
            return;
        }
        String tenantId = normalized(identity.tenantId(), DEFAULT_TENANT);
        RuntimeConversationSessionEntity session = sessionMapper.selectOne(
                new LambdaQueryWrapper<RuntimeConversationSessionEntity>()
                        .eq(RuntimeConversationSessionEntity::getTenantId, tenantId)
                        .eq(RuntimeConversationSessionEntity::getSessionId, publicSessionId.trim())
                        .eq(RuntimeConversationSessionEntity::getUserId, identity.userId().trim())
                        .last("LIMIT 1"));
        if (session == null) {
            return;
        }
        if (retentionStore != null && stateEraser != null) {
            String owner = UUID.randomUUID().toString();
            String actorHash = RuntimeSessionRetentionSupport.actorHash(identity.userId().trim());
            LocalDateTime now = LocalDateTime.now();
            retentionStore.claimClear(session, owner, actorHash, now);
            try {
                stateEraser.clearTransient(session);
                retentionStore.completeClear(
                        session, owner, "RUNTIME_USER", actorHash, LocalDateTime.now());
            } catch (RuntimeException failure) {
                try {
                    retentionStore.recordFailure(
                            session, "CLEAR_FAILED", "RUNTIME_USER", actorHash,
                            null, failure, LocalDateTime.now());
                } catch (RuntimeException auditFailure) {
                    failure.addSuppressed(auditFailure);
                }
                throw failure;
            }
            return;
        }
        // Compatibility path for isolated unit tests that do not construct the
        // production retention collaborators.
        if (Boolean.TRUE.equals(session.getLegalHold())) {
            throw RuntimeSessionRetentionException.legalHold();
        }
        String status = normalizedStatus(session.getStatus());
        if (RuntimeSessionRetentionStore.CLEARING.equals(status)
                || RuntimeSessionRetentionStore.PURGING.equals(status)) {
            throw RuntimeSessionRetentionException.lifecycleBusy();
        }
        stateStore.delete(session.getStateUserKey(), session.getStateSessionKey());
        if (toolResultArtifactService != null) {
            toolResultArtifactService.scrubSession(
                    session.getStateUserKey(), session.getStateSessionKey(), session.getAgentId());
        }
        LocalDateTime now = LocalDateTime.now();
        sessionMapper.update(null, new LambdaUpdateWrapper<RuntimeConversationSessionEntity>()
                .eq(RuntimeConversationSessionEntity::getId, session.getId())
                .set(RuntimeConversationSessionEntity::getStatus, RuntimeSessionRetentionStore.CLEARED)
                .set(RuntimeConversationSessionEntity::getClearedAt, now)
                .set(RuntimeConversationSessionEntity::getUpdatedAt, now));
    }

    private RuntimeConversationSessionEntity requireSession(RuntimeSessionMemoryKey key,
                                                             RuntimeAgentView agent,
                                                             Long agentConfigVersionId) {
        RuntimeConversationSessionEntity existing = sessionMapper.selectOne(
                new LambdaQueryWrapper<RuntimeConversationSessionEntity>()
                        .eq(RuntimeConversationSessionEntity::getTenantId, key.tenantId())
                        .eq(RuntimeConversationSessionEntity::getSessionId, key.publicSessionId())
                        .last("LIMIT 1"));
        if (existing != null) {
            assertOwner(existing, key);
            String status = normalizedStatus(existing.getStatus());
            if (RuntimeSessionRetentionStore.CLEARING.equals(status)
                    || RuntimeSessionRetentionStore.PURGING.equals(status)
                    || RuntimeSessionRetentionStore.EXPIRED.equals(status)) {
                throw new RuntimeSessionBusyException(
                        "session lifecycle maintenance is in progress");
            }
            if (RuntimeSessionRetentionStore.CLEARED.equals(status)) {
                LocalDateTime now = LocalDateTime.now();
                int reactivated = sessionMapper.update(null,
                        new LambdaUpdateWrapper<RuntimeConversationSessionEntity>()
                        .eq(RuntimeConversationSessionEntity::getId, existing.getId())
                        .eq(RuntimeConversationSessionEntity::getStatus,
                                RuntimeSessionRetentionStore.CLEARED)
                        .isNull(RuntimeConversationSessionEntity::getLifecycleOwner)
                        .set(RuntimeConversationSessionEntity::getStatus,
                                RuntimeSessionRetentionStore.ACTIVE)
                        .set(RuntimeConversationSessionEntity::getClearedAt, null)
                        .set(RuntimeConversationSessionEntity::getUpdatedAt, now));
                if (reactivated != 1) {
                    throw new RuntimeSessionBusyException(
                            "session lifecycle maintenance is in progress");
                }
                existing.setStatus(RuntimeSessionRetentionStore.ACTIVE);
                existing.setClearedAt(null);
            }
            return existing;
        }
        RuntimeConversationSessionEntity created = new RuntimeConversationSessionEntity();
        created.setTenantId(key.tenantId());
        created.setSessionId(key.publicSessionId());
        created.setUserId(key.trustedUserId());
        created.setAgentId(key.agentId());
        created.setAgentConfigVersionId(agentConfigVersionId);
        created.setProjectCode(agent == null ? null : agent.projectCode());
        created.setIdentitySource(key.identitySource());
        created.setStateUserKey(key.stateUserKey());
        created.setStateSessionKey(key.stateSessionKey());
        created.setStatus(RuntimeSessionRetentionStore.ACTIVE);
        created.setLegalHold(false);
        created.setEventCount(0);
        created.setCreatedAt(LocalDateTime.now());
        created.setUpdatedAt(created.getCreatedAt());
        try {
            sessionMapper.insert(created);
            return created;
        } catch (DuplicateKeyException concurrentInsert) {
            RuntimeConversationSessionEntity raced = sessionMapper.selectOne(
                    new LambdaQueryWrapper<RuntimeConversationSessionEntity>()
                            .eq(RuntimeConversationSessionEntity::getTenantId, key.tenantId())
                            .eq(RuntimeConversationSessionEntity::getSessionId, key.publicSessionId())
                            .last("LIMIT 1"));
            if (raced == null) {
                throw concurrentInsert;
            }
            assertOwner(raced, key);
            String status = normalizedStatus(raced.getStatus());
            if (RuntimeSessionRetentionStore.CLEARING.equals(status)
                    || RuntimeSessionRetentionStore.PURGING.equals(status)
                    || RuntimeSessionRetentionStore.EXPIRED.equals(status)) {
                throw new RuntimeSessionBusyException(
                        "session lifecycle maintenance is in progress");
            }
            return raced;
        }
    }


    private void assertOwner(RuntimeConversationSessionEntity session, RuntimeSessionMemoryKey key) {
        if (!key.trustedUserId().equals(session.getUserId()) || !key.agentId().equals(session.getAgentId())) {
            throw new RuntimeSessionOwnershipException(
                    "sessionId already belongs to another trusted user or Agent");
        }
    }

    private boolean eventExists(Long sessionId, String turnId) {
        return StringUtils.hasText(turnId) && eventMapper.selectCount(
                new LambdaQueryWrapper<RuntimeConversationEventEntity>()
                        .eq(RuntimeConversationEventEntity::getConversationSessionId, sessionId)
                        .eq(RuntimeConversationEventEntity::getTurnId, turnId)) > 0;
    }

    private void insertEvent(Long sessionId,
                             int sequence,
                             String traceId,
                             String turnId,
                             String eventType,
                             String role,
                             String content,
                             Map<String, Object> payload) {
        String normalizedContent = truncate(content);
        RuntimeConversationEventEntity event = new RuntimeConversationEventEntity();
        event.setConversationSessionId(sessionId);
        event.setSequenceNo(sequence);
        event.setTraceId(text(traceId));
        event.setTurnId(turnId);
        event.setEventType(eventType);
        event.setRole(role);
        event.setContent(normalizedContent);
        event.setContentSha256(sha256(normalizedContent));
        event.setPayloadJson(toFlatJson(payload));
        event.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private void syncVisibleTurnToState(RuntimeSessionMemoryKey key,
                                        String userMessage,
                                        String assistantReply,
                                        boolean agentStateContainsTurn) {
        AgentState state = stateStore.get(
                        key.stateUserKey(), key.stateSessionKey(), STATE_NAME, AgentState.class)
                .orElseGet(() -> AgentState.builder()
                        .sessionId(key.stateSessionKey())
                        .userId(key.stateUserKey())
                        .build());
        List<Msg> context = state.contextMutable();
        if (agentStateContainsTurn && !lastUserMatches(context, userMessage)) {
            replaceLastUser(context, userMessage);
        } else if (!agentStateContainsTurn) {
            context.add(message("user", MsgRole.USER, userMessage));
        }
        if (!tailMatches(context, MsgRole.ASSISTANT, assistantReply)) {
            context.add(message("assistant", MsgRole.ASSISTANT, assistantReply));
        }
        trimMutableContext(context);
        stateStore.save(key.stateUserKey(), key.stateSessionKey(), STATE_NAME, state);
    }

    private void trimPersistentState(RuntimeSessionMemoryKey key, String owner) {
        stateStore.get(key.stateUserKey(), key.stateSessionKey(), STATE_NAME, AgentState.class)
                .ifPresent(state -> {
                    if (trimMutableContext(state.contextMutable())) {
                        stateStoreForTurn(key, owner).save(
                                key.stateUserKey(), key.stateSessionKey(), STATE_NAME, state);
                    }
                });
    }

    private boolean trimMutableContext(List<Msg> context) {
        if (context == null) {
            return false;
        }
        int overflow = context.size() - properties.maxContextMessages();
        if (overflow <= 0) {
            return false;
        }
        context.subList(0, overflow).clear();
        return true;
    }

    private static Msg message(String name, MsgRole role, String content) {
        return Msg.builder().name(name).role(role).textContent(text(content)).build();
    }

    private static boolean lastUserMatches(List<Msg> context, String content) {
        if (context == null || context.isEmpty() || !StringUtils.hasText(content)) {
            return false;
        }
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg message = context.get(i);
            if (message != null && MsgRole.USER == message.getRole()) {
                return content.trim().equals(text(message.getTextContent()).trim());
            }
        }
        return false;
    }

    private static void replaceLastUser(List<Msg> context, String content) {
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg current = context.get(i);
            if (current != null && MsgRole.USER == current.getRole()) {
                context.set(i, message("user", MsgRole.USER, content));
                return;
            }
        }
        context.add(message("user", MsgRole.USER, content));
    }

    private static boolean tailMatches(List<Msg> context, MsgRole role, String content) {
        if (context == null || context.isEmpty()) {
            return false;
        }
        Msg tail = context.get(context.size() - 1);
        return tail != null
                && role == tail.getRole()
                && text(content).equals(text(tail.getTextContent()));
    }

    private List<Msg> window(List<Msg> context) {
        if (context == null || context.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, context.size() - properties.maxContextMessages());
        return List.copyOf(context.subList(start, context.size()));
    }

    private String truncate(String value) {
        String safe = value == null ? "" : value;
        return safe.length() <= properties.maxStoredContentChars()
                ? safe
                : safe.substring(0, properties.maxStoredContentChars());
    }

    private static String normalized(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String normalizedStatus(String value) {
        return StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT)
                : RuntimeSessionRetentionStore.ACTIVE;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String toFlatJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "{}";
        }
        String outcome = escape(text(payload.get("outcomeCode")));
        boolean waiting = Boolean.TRUE.equals(payload.get("waiting"));
        return "{\"outcomeCode\":\"" + outcome + "\",\"waiting\":" + waiting + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
