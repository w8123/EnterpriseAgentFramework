package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Password-free sign-in for the employee personal page. The chat panel, already
 * holding a verified Embed Token, asks for a short single-use ticket; the page
 * opened in a new browser tab trades it for a session that carries the same
 * synced employee. Both live only in memory: a Control restart simply means the
 * employee opens the page from the chat panel again.
 *
 * <p>A ticket issued from the chat panel also remembers which Agent the employee was
 * chatting with and exactly what the business system attested about them, so the
 * page's 我的助手 can talk to the same Agent as the same user without the business
 * system being open.
 */
@Service
public class EmployeePortalSessionService {

    static final Duration TICKET_TTL = Duration.ofSeconds(60);
    static final Duration SESSION_TTL = Duration.ofHours(2);
    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, Entry> tickets = new ConcurrentHashMap<>();
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final ReentrantReadWriteLock sessionCommitGate = new ReentrantReadWriteLock();
    private final Clock clock;
    private final PlatformEmbedBusinessSessionRevocation businessSessions;

    public EmployeePortalSessionService() {
        this(Clock.systemUTC(), null);
    }

    EmployeePortalSessionService(Clock clock) {
        this(clock, null);
    }

    @Autowired
    public EmployeePortalSessionService(PlatformEmbedBusinessSessionRevocation businessSessions) {
        this(Clock.systemUTC(), businessSessions);
    }

    EmployeePortalSessionService(Clock clock, PlatformEmbedBusinessSessionRevocation businessSessions) {
        this.clock = clock;
        this.businessSessions = businessSessions;
    }

    public Issued issueTicket(EmployeeOwner owner) {
        return issueTicket(owner, null);
    }

    public Issued issueTicket(EmployeeOwner owner, ChatGrant chat) {
        return put(tickets, owner, chat, TICKET_TTL);
    }

    /** Redeems a ticket exactly once; an unknown, used or expired ticket yields empty. */
    public Optional<Issued> redeem(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        Entry entry = tickets.remove(ticket.trim());
        if (entry == null || entry.expired(clock.instant()) || !active(entry.chat())) {
            return Optional.empty();
        }
        try {
            return Optional.of(put(sessions, entry.owner(), entry.chat(), SESSION_TTL));
        } catch (PlatformEmbedTokenException expiredDuringRedeem) {
            return Optional.empty();
        }
    }

    public Optional<EmployeeOwner> resolve(String token) {
        return session(token).map(Issued::owner);
    }

    /** Used only for pending confirmations: never persist a live portal bearer token. */
    public Optional<Issued> sessionByDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) return Optional.empty();
        for (String token : sessions.keySet()) {
            if (java.security.MessageDigest.isEqual(
                    AiToolConnectionService.sha256Hex(token).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    digest.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                return session(token);
            }
        }
        return Optional.empty();
    }

    /** The live session behind a portal token, with its chat grant and expiry. */
    public Optional<Issued> session(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Entry entry = sessions.get(token.trim());
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expired(clock.instant()) || !active(entry.chat())) {
            sessions.remove(token.trim());
            return Optional.empty();
        }
        return Optional.of(new Issued(token.trim(), entry.expiresAt(), entry.owner(), entry.chat()));
    }

    public void end(String token) {
        if (token != null) {
            var lock = sessionCommitGate.writeLock();
            lock.lock();
            try { sessions.remove(token.trim()); }
            finally { lock.unlock(); }
        }
    }

    /** Recheck live sessions at commit; a completed local logout cannot race past that check. */
    public void verifyAtCommit(Runnable verify) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("session confirmation requires an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            private boolean locked;
            @Override public void beforeCommit(boolean readOnly) {
                sessionCommitGate.readLock().lock();
                locked = true;
                try { verify.run(); }
                catch (RuntimeException | Error failure) {
                    locked = false;
                    sessionCommitGate.readLock().unlock();
                    throw failure;
                }
            }
            @Override public void afterCompletion(int status) {
                if (locked) {
                    locked = false;
                    sessionCommitGate.readLock().unlock();
                }
            }
        });
    }

    private Issued put(Map<String, Entry> store, EmployeeOwner owner, ChatGrant chat, Duration ttl) {
        Instant now = clock.instant();
        if (store.size() >= MAX_ENTRIES) {
            store.values().removeIf(entry -> entry.expired(now));
            if (store.size() >= MAX_ENTRIES) {
                throw new IllegalStateException("too many open employee portal sessions");
            }
        }
        String value = randomToken();
        Instant expiresAt = now.plus(ttl);
        if (chat != null && chat.sourceExpiresAt() != null && chat.sourceExpiresAt().isBefore(expiresAt)) {
            expiresAt = chat.sourceExpiresAt();
        }
        if (!now.isBefore(expiresAt)) throw new PlatformEmbedTokenException("embed token is expired");
        store.put(value, new Entry(owner, chat, expiresAt));
        return new Issued(value, expiresAt, owner, chat);
    }

    private boolean active(ChatGrant chat) {
        if (chat == null || chat.businessSessionId() == null) return true;
        if (businessSessions == null) return false;
        try {
            businessSessions.requireActive(chat.projectCode(), chat.businessSessionId());
            return true;
        } catch (PlatformEmbedTokenException revoked) {
            return false;
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record Entry(EmployeeOwner owner, ChatGrant chat, Instant expiresAt) {
        boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    public record Issued(String value, Instant expiresAt, EmployeeOwner owner, ChatGrant chat) {
    }

    /**
     * What the chat panel's verified Embed Token said: the Agent and the business user
     * exactly as the business system attested them. Null when the ticket did not come
     * from a chat panel.
     */
    public record ChatGrant(String tenantId,
                            String appId,
                            String projectCode,
                            String agentId,
                            String externalUserId,
                            String globalUserId,
                            String userName,
                            List<String> roles,
                            Map<String, Object> attributes,
                            String businessSessionId,
                            Instant sourceExpiresAt) {

        public ChatGrant(String tenantId, String appId, String projectCode, String agentId,
                         String externalUserId, String globalUserId, String userName,
                         List<String> roles, Map<String, Object> attributes) {
            this(tenantId, appId, projectCode, agentId, externalUserId, globalUserId, userName,
                    roles, attributes, null, null);
        }

        public ChatGrant {
            // Attested values may hold nulls, which List.copyOf / Map.copyOf reject.
            roles = roles == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(roles));
            attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        static ChatGrant of(PlatformEmbedTokenClaims claims) {
            return new ChatGrant(claims.getTenantId(), claims.getAppId(), claims.getProjectCode(),
                    claims.getAgentId(), claims.getExternalUserId(), claims.getGlobalUserId(),
                    claims.getUserName(), claims.getRoles(), claims.getAttributes(), claims.getBusinessSessionId(),
                    Instant.ofEpochSecond(claims.getExpiresAt()));
        }
    }
}
