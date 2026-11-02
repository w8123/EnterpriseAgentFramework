package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import org.springframework.stereotype.Service;

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
    private final Clock clock;

    public EmployeePortalSessionService() {
        this(Clock.systemUTC());
    }

    EmployeePortalSessionService(Clock clock) {
        this.clock = clock;
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
        if (entry == null || entry.expired(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(put(sessions, entry.owner(), entry.chat(), SESSION_TTL));
    }

    public Optional<EmployeeOwner> resolve(String token) {
        return session(token).map(Issued::owner);
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
        if (entry.expired(clock.instant())) {
            sessions.remove(token.trim());
            return Optional.empty();
        }
        return Optional.of(new Issued(token.trim(), entry.expiresAt(), entry.owner(), entry.chat()));
    }

    public void end(String token) {
        if (token != null) {
            sessions.remove(token.trim());
        }
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
        store.put(value, new Entry(owner, chat, expiresAt));
        return new Issued(value, expiresAt, owner, chat);
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
                            Map<String, Object> attributes) {

        public ChatGrant {
            // Attested values may hold nulls, which List.copyOf / Map.copyOf reject.
            roles = roles == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(roles));
            attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        static ChatGrant of(PlatformEmbedTokenClaims claims) {
            return new ChatGrant(claims.getTenantId(), claims.getAppId(), claims.getProjectCode(),
                    claims.getAgentId(), claims.getExternalUserId(), claims.getGlobalUserId(),
                    claims.getUserName(), claims.getRoles(), claims.getAttributes());
        }
    }
}
