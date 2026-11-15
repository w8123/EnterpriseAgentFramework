package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.pageworkbench.application.PageTransferTargetLookup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Short-lived same-principal page transfer; raw bearer tickets are never persisted. */
@Service
@RequiredArgsConstructor
public class PlatformEmbedPageTransferService {
    private static final String TYPE = "PAGE_TRANSFER";
    private static final int TTL_SECONDS = 600;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PlatformEmbedSessionService sessions;
    private final PlatformEmbedTurnLeaseService turns;
    private final PlatformEmbedChatEventMapper events;
    private final PageTransferTargetLookup pages;
    private final ObjectMapper json;

    public record TransferTicket(String transferTicket, int expiresInSeconds) { }
    public record TransferResult(String sessionId, String pageKey, String pageInstanceId, String route) { }

    @Transactional
    public TransferTicket issue(String sessionId, PlatformEmbedTokenClaims claims) {
        PlatformEmbedSessionEntity session = sessions.requireActiveSessionForNavigationRebind(sessionId, claims);
        requireBoundBusinessIdentity(session, claims);
        if (!Objects.equals(session.getGlobalUserId(), claims.getGlobalUserId())) throw new PlatformEmbedTokenException("business identity changed");
        requireCurrentPage(session, claims);
        PlatformEmbedTurnLeaseService.IdleSnapshot snapshot = turns.idleSnapshot(session);
        byte[] random = new byte[32]; RANDOM.nextBytes(random);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var event = new PlatformEmbedChatEventEntity();
        event.setSessionId(sessionId); event.setEventType(TYPE); event.setRole("ISSUED");
        event.setContent(digest(ticket)); event.setCreatedAt(LocalDateTime.now());
        event.setPayloadJson(write(Map.of("sourceInstance", session.getPageInstanceId(),
                "epoch", snapshot.epoch(), "latestTurnId", snapshot.latestTurnId())));
        if (events.insert(event) != 1 || event.getId() == null) throw new IllegalStateException("page transfer could not be issued");
        return new TransferTicket(ticket, TTL_SECONDS);
    }

    @Transactional
    public TransferResult claim(String sessionId, PlatformEmbedTokenClaims claims, String ticket,
                                List<String> bridgeActions, String sdkVersion) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("invalid page transfer");
        PlatformEmbedSessionEntity session = sessions.requireActiveSessionForNavigationRebind(sessionId, claims);
        requireBoundBusinessIdentity(session, claims);
        if (!Objects.equals(session.getGlobalUserId(), claims.getGlobalUserId())) throw new PlatformEmbedTokenException("business identity changed");
        if (!StringUtils.hasText(claims.getPageKey()) || !StringUtils.hasText(claims.getPageInstanceId())
                || !safePath(claims.getRoute())) throw new IllegalArgumentException("invalid target page");
        String ticketDigest = digest(ticket);
        PlatformEmbedChatEventEntity event = events.selectOne(Wrappers.<PlatformEmbedChatEventEntity>lambdaQuery()
                .eq(PlatformEmbedChatEventEntity::getSessionId, sessionId)
                .eq(PlatformEmbedChatEventEntity::getEventType, TYPE)
                .eq(PlatformEmbedChatEventEntity::getContent, ticketDigest)
                .ge(PlatformEmbedChatEventEntity::getCreatedAt, LocalDateTime.now().minusSeconds(TTL_SECONDS))
                .orderByDesc(PlatformEmbedChatEventEntity::getId).last("LIMIT 1 FOR UPDATE"));
        // MySQL DATETIME(0) can round a freshly issued ticket into the next
        // second. This tolerance does not change the existing expiry bound.
        if (event == null || !Objects.equals(event.getSessionId(), sessionId) || !TYPE.equals(event.getEventType())
                || event.getContent() == null || !MessageDigest.isEqual(ticketDigest.getBytes(StandardCharsets.US_ASCII), event.getContent().getBytes(StandardCharsets.US_ASCII))
                || event.getCreatedAt() == null || event.getCreatedAt().isAfter(LocalDateTime.now().plusSeconds(1))
                || event.getCreatedAt().isBefore(LocalDateTime.now().minusSeconds(TTL_SECONDS))) {
            throw new IllegalArgumentException("page transfer expired or unavailable");
        }
        Map<String, Object> saved = read(event.getPayloadJson());
        if ("CONSUMED".equals(event.getRole())) {
            if (!same(saved.get("targetInstance"), claims.getPageInstanceId())
                    || !same(saved.get("targetPage"), claims.getPageKey())
                    || !same(saved.get("targetRoute"), claims.getRoute())) throw new IllegalArgumentException("page transfer was consumed");
            requireCurrentPage(session, claims);
            return result(session);
        }
        if (!"ISSUED".equals(event.getRole()) || !same(saved.get("sourceInstance"), session.getPageInstanceId())) {
            throw new IllegalArgumentException("page transfer is no longer current");
        }
        var page = pages.forUpdate(session.getProjectCode(), claims.getPageKey()).orElse(null);
        if (page == null || !Objects.equals(page.projectCode(), session.getProjectCode())
                || !Objects.equals(page.pageKey(), claims.getPageKey()) || !"ACTIVE".equals(page.lifecycleStatus())
                || !PlatformEmbedPublicController.routeMatches(page.routePattern(), claims.getRoute())) {
            throw new IllegalArgumentException("target page is unavailable");
        }
        PlatformEmbedTurnLeaseService.IdleSnapshot snapshot = turns.idleSnapshot(session);
        if (number(saved.get("epoch")) != snapshot.epoch() || number(saved.get("latestTurnId")) != snapshot.latestTurnId()) {
            throw new IllegalStateException("conversation changed after page transfer was issued");
        }
        sessions.rebindBridgeAfterNavigation(session, claims, claims.getPageKey(), claims.getPageInstanceId(),
                claims.getRoute(), bridgeActions, sdkVersion);
        Map<String, Object> completed = new LinkedHashMap<>(saved);
        completed.put("targetInstance", claims.getPageInstanceId()); completed.put("targetPage", claims.getPageKey());
        completed.put("targetRoute", claims.getRoute());
        event.setRole("CONSUMED"); event.setPayloadJson(write(completed));
        if (events.updateById(event) != 1) throw new IllegalStateException("page transfer changed before claim");
        return result(session);
    }

    private void requireCurrentPage(PlatformEmbedSessionEntity session, PlatformEmbedTokenClaims claims) {
        if (!same(session.getPageInstanceId(), claims.getPageInstanceId()) || !same(session.getPageKey(), claims.getPageKey())
                || !same(session.getRoute(), claims.getRoute())) throw new PlatformEmbedTokenException("page token is not current");
    }

    private void requireBoundBusinessIdentity(PlatformEmbedSessionEntity session, PlatformEmbedTokenClaims claims) {
        if (!StringUtils.hasText(session.getExternalUserId()) || !StringUtils.hasText(session.getBusinessSessionId())
                || !Objects.equals(session.getBusinessSessionId(), claims.getBusinessSessionId())) {
            throw new PlatformEmbedTokenException("verified business session is required for page transfer");
        }
    }

    private boolean safePath(String path) {
        if (!StringUtils.hasText(path) || !path.startsWith("/") || path.startsWith("//") || path.indexOf('\\') >= 0
                || path.toLowerCase(java.util.Locale.ROOT).matches(".*%(2e|2f|5c).*")) return false;
        try {
            URI uri = URI.create(path);
            return uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getRawAuthority() == null
                    && uri.getScheme() == null && path.equals(uri.normalize().getRawPath());
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private TransferResult result(PlatformEmbedSessionEntity session) {
        return new TransferResult(session.getSessionId(), session.getPageKey(), session.getPageInstanceId(), session.getRoute());
    }
    private String digest(String ticket) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("cannot hash page transfer", ex); }
    }
    private Map<String, Object> read(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (Exception ex) { throw new IllegalStateException("invalid page transfer state", ex); }
    }
    private String write(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot encode page transfer", ex); }
    }
    private boolean same(Object left, String right) { return left instanceof String value && StringUtils.hasText(right) && value.equals(right); }
    private long number(Object value) { return value instanceof Number number ? number.longValue() : -1; }
}
