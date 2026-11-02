package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService;
import com.enterprise.ai.control.platform.EmployeePortalSessionService;
import com.enterprise.ai.control.platform.EmployeePortalSessionService.Issued;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** One-time confirmation between two live employee portal sessions. */
@Service
public class PersonalMemoryIdentityChallengeService {
    private static final Duration TTL = Duration.ofMinutes(5);
    private final EmployeePortalSessionService sessions;
    private final PersonalMemoryIdentityLinkService links;
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public PersonalMemoryIdentityChallengeService(EmployeePortalSessionService sessions,
                                                  PersonalMemoryIdentityLinkService links, JdbcTemplate jdbc) {
        this(sessions, links, jdbc, Clock.systemUTC());
    }

    PersonalMemoryIdentityChallengeService(EmployeePortalSessionService sessions,
                                           PersonalMemoryIdentityLinkService links, JdbcTemplate jdbc, Clock clock) {
        this.sessions = sessions;
        this.links = links;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Challenge issue(String portalToken) {
        Issued source = liveSession(portalToken);
        links.requireVerifiedEmployee(source.owner());
        Instant now = clock.instant();
        Instant expires = now.plus(TTL).isBefore(source.expiresAt()) ? now.plus(TTL) : source.expiresAt();
        if (!now.isBefore(expires)) throw unauthorized();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String code = "rml_" + HexFormat.of().formatHex(bytes);
        jdbc.update("INSERT INTO control_personal_memory_identity_challenge (challenge_hash, source_session_hash, expires_at, created_at) VALUES (?, ?, ?, ?)",
                AiToolConnectionService.sha256Hex(code), AiToolConnectionService.sha256Hex(source.value()),
                utc(expires), utc(now));
        return new Challenge(code, expires);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long confirm(String portalToken, String code) {
        Issued target = liveSession(portalToken);
        if (code == null || !code.matches("rml_[0-9a-f]{64}")) throw invalidChallenge();
        List<Pending> candidates = jdbc.query("""
                SELECT id, source_session_hash, expires_at, consumed_at
                FROM control_personal_memory_identity_challenge WHERE challenge_hash=? FOR UPDATE
                """, (rs, row) -> new Pending(rs.getLong("id"), rs.getString("source_session_hash"),
                rs.getTimestamp("expires_at").toLocalDateTime().toInstant(ZoneOffset.UTC),
                rs.getTimestamp("consumed_at") != null), AiToolConnectionService.sha256Hex(code));
        Instant now = clock.instant();
        if (candidates.size() != 1 || candidates.get(0).consumed() || !now.isBefore(candidates.get(0).expiresAt())) {
            throw invalidChallenge();
        }
        Pending pending = candidates.get(0);
        Issued source = sessions.sessionByDigest(pending.sourceDigest()).orElseThrow(PersonalMemoryIdentityChallengeService::invalidChallenge);
        if (source.value().equals(target.value())) throw invalidChallenge();
        sessions.verifyAtCommit(() -> {
            if (!clock.instant().isBefore(pending.expiresAt())
                    || !source.equals(liveSession(source.value())) || !target.equals(liveSession(target.value()))) {
                throw invalidChallenge();
            }
        });
        long id = links.linkByVerifiedEmployees(source.owner(), target.owner());
        int changed = jdbc.update("UPDATE control_personal_memory_identity_challenge SET consumed_at=? WHERE id=? AND consumed_at IS NULL",
                utc(now), pending.id());
        if (changed != 1) throw invalidChallenge();
        return id;
    }

    private Issued liveSession(String token) {
        Issued session = sessions.session(token).orElseThrow(PersonalMemoryIdentityChallengeService::unauthorized);
        var chat = session.chat();
        var owner = session.owner();
        if (chat == null || owner == null || chat.sourceExpiresAt() == null
                || !clock.instant().isBefore(session.expiresAt()) || session.expiresAt().isAfter(chat.sourceExpiresAt())
                || !Objects.equals(owner.tenantId(), normalizeTenant(chat.tenantId()))
                || !Objects.equals(owner.appId(), chat.appId())
                || !Objects.equals(owner.projectCode(), chat.projectCode())
                || !Objects.equals(owner.externalUserId(), chat.externalUserId())) throw unauthorized();
        return session;
    }

    private static String normalizeTenant(String tenant) {
        return tenant == null || tenant.isBlank() ? "default" : tenant.trim().toLowerCase(Locale.ROOT);
    }

    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static ResponseStatusException invalidChallenge() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "identity confirmation is invalid or expired");
    }
    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "live employee session is required");
    }
    private record Pending(long id, String sourceDigest, Instant expiresAt, boolean consumed) { }
    public record Challenge(String code, Instant expiresAt) { }
}
