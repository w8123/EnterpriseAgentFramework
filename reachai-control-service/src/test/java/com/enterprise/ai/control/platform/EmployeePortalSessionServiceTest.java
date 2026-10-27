package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
