package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Password-free sign-in for the employee personal page. The chat panel, already
 * holding a verified Embed Token, asks for a short single-use ticket; the page
 * opened in a new browser tab trades it for a session that carries the same
 * synced employee. Both live only in memory: a Control restart simply means the
 * employee opens the page from the chat panel again.
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
        return put(tickets, owner, TICKET_TTL);
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
        return Optional.of(put(sessions, entry.owner(), SESSION_TTL));
    }

    public Optional<EmployeeOwner> resolve(String token) {
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
        return Optional.of(entry.owner());
    }

    public void end(String token) {
        if (token != null) {
            sessions.remove(token.trim());
        }
    }

    private Issued put(Map<String, Entry> store, EmployeeOwner owner, Duration ttl) {
        Instant now = clock.instant();
        if (store.size() >= MAX_ENTRIES) {
            store.values().removeIf(entry -> entry.expired(now));
            if (store.size() >= MAX_ENTRIES) {
                throw new IllegalStateException("too many open employee portal sessions");
            }
        }
        String value = randomToken();
        Instant expiresAt = now.plus(ttl);
        store.put(value, new Entry(owner, expiresAt));
        return new Issued(value, expiresAt, owner);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record Entry(EmployeeOwner owner, Instant expiresAt) {
        boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    public record Issued(String value, Instant expiresAt, EmployeeOwner owner) {
    }
}
