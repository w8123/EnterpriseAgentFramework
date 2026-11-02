package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class EmployeePortalSessionServiceTest {

    private static final EmployeeOwner OWNER =
            new EmployeeOwner("t", 7L, "yudao-erp", "yudao-erp", "1", "张三", List.of("erp_user"));

    private Instant now = Instant.parse("2026-10-26T08:00:00Z");
    private final EmployeePortalSessionService service = new EmployeePortalSessionService(new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    @Test
    void ticketSignsInTheSameEmployeeExactlyOnce() {
        var ticket = service.issueTicket(OWNER);

        var session = service.redeem(ticket.value()).orElseThrow();

        assertEquals(OWNER, service.resolve(session.value()).orElseThrow());
        assertTrue(service.redeem(ticket.value()).isEmpty(), "a ticket cannot be used twice");
        assertTrue(service.resolve(ticket.value()).isEmpty(), "a ticket is not a session");
    }

    @Test
    void expiredTicketAndSessionAreRefused() {
        var late = service.issueTicket(OWNER);
        var onTime = service.issueTicket(OWNER);
        var session = service.redeem(onTime.value()).orElseThrow();

        now = now.plus(EmployeePortalSessionService.TICKET_TTL);
        assertTrue(service.redeem(late.value()).isEmpty());
        assertTrue(service.resolve(session.value()).isPresent());

        now = now.plus(EmployeePortalSessionService.SESSION_TTL);
        assertTrue(service.resolve(session.value()).isEmpty());
    }

    @Test
    void signOutEndsTheSession() {
        var session = service.redeem(service.issueTicket(OWNER).value()).orElseThrow();

        service.end(session.value());

        assertTrue(service.resolve(session.value()).isEmpty());
        assertTrue(service.resolve(null).isEmpty());
        assertTrue(service.redeem(" ").isEmpty());
    }

    @Test
    void logoutWaitsForCommitValidationAndRunsAfterCompletion() throws Exception {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:portal_commit_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        var session = service.redeem(service.issueTicket(OWNER).value()).orElseThrow();
        var validating = new java.util.concurrent.CountDownLatch(1);
        var allowCommit = new java.util.concurrent.CountDownLatch(1);
        var logoutStarted = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var committing = pool.submit(() -> tx.execute(status -> {
                service.verifyAtCommit(() -> {
                    assertTrue(service.resolve(session.value()).isPresent());
                    validating.countDown();
                    try { assertTrue(allowCommit.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                });
                return true;
            }));
            assertTrue(validating.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var logout = pool.submit(() -> { logoutStarted.countDown(); service.end(session.value()); });
            assertTrue(logoutStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> logout.get(200, java.util.concurrent.TimeUnit.MILLISECONDS));
            allowCommit.countDown();
            assertTrue(committing.get(10, java.util.concurrent.TimeUnit.SECONDS));
            logout.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(service.resolve(session.value()).isEmpty());
        } finally { allowCommit.countDown(); pool.shutdownNow(); }
    }

    @Test
    void portalCannotOutliveTheSourceEmbedToken() {
        var claims = new PlatformEmbedTokenClaims();
        claims.setProjectCode("yudao-erp");
        claims.setExpiresAt(now.plusSeconds(30).getEpochSecond());
        var ticket = service.issueTicket(OWNER, EmployeePortalSessionService.ChatGrant.of(claims));
        var session = service.redeem(ticket.value()).orElseThrow();
        assertEquals(now.plusSeconds(30), session.expiresAt());
        now = now.plusSeconds(30);
        assertTrue(service.resolve(session.value()).isEmpty());
    }

    @Test
    void businessSessionRevocationInvalidatesTicketAndPortalSession() {
        var revocations = mock(PlatformEmbedBusinessSessionRevocation.class);
        var portal = new EmployeePortalSessionService(Clock.fixed(now, ZoneOffset.UTC), revocations);
        var claims = new PlatformEmbedTokenClaims();
        claims.setProjectCode("yudao-erp");
        claims.setBusinessSessionId("a".repeat(32));
        claims.setExpiresAt(now.plusSeconds(300).getEpochSecond());
        var grant = EmployeePortalSessionService.ChatGrant.of(claims);
        var session = portal.redeem(portal.issueTicket(OWNER, grant).value()).orElseThrow();
        var ticket = portal.issueTicket(OWNER, grant);
        doThrow(new PlatformEmbedTokenException("revoked")).when(revocations).requireActive(anyString(), anyString());
        assertTrue(portal.resolve(session.value()).isEmpty());
        assertTrue(portal.redeem(ticket.value()).isEmpty());
    }
}
