package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityLinkLookup;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.platform.EmployeePortalSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.ai.common.exception.GlobalExceptionHandler;
import com.enterprise.ai.control.platform.EmployeeMemoryIdentityController;
import com.enterprise.ai.control.platform.PersonalMemoryIdentityAdminController;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;

import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PersonalMemoryIdentityLinkServiceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private DataSourceTransactionManager transactionManager;
    private PersonalMemoryIdentityLinkService service;
    private PersonalMemoryIdentityLinkLookup lookup;
    private final PlatformAuthenticatedSession admin = new PlatformAuthenticatedSession(
            new PlatformPrincipal(9L, "admin", "Admin"), "session", null, List.of(), List.of(),
            List.of(new PlatformPermissionGrant(PlatformPermissions.BUSINESS_USER_MANAGE, "GLOBAL", "*")));

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:link_write_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        transactionManager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("CREATE TABLE control_business_user (id BIGINT PRIMARY KEY, tenant_id VARCHAR(96), status VARCHAR(24))");
        jdbc.execute("CREATE TABLE control_external_user_binding (id BIGINT PRIMARY KEY, tenant_id VARCHAR(96), business_user_id BIGINT, app_id VARCHAR(96), external_user_id VARCHAR(128), status VARCHAR(24))");
        jdbc.execute("CREATE TABLE control_personal_memory_identity_link (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(96), left_binding_id BIGINT, right_binding_id BIGINT, left_business_user_id BIGINT, right_business_user_id BIGINT, left_app_id VARCHAR(96), left_external_user_id VARCHAR(128), right_app_id VARCHAR(96), right_external_user_id VARCHAR(128), state VARCHAR(16), active_marker TINYINT, verification_method VARCHAR(24), verified_by VARCHAR(128), verification_note VARCHAR(512), created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE(left_binding_id,right_binding_id,active_marker))");
        jdbc.execute("CREATE TABLE control_personal_memory_identity_link_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, link_id BIGINT, action VARCHAR(16), verification_method VARCHAR(24), actor VARCHAR(128), verification_note VARCHAR(512), created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE control_personal_memory_identity_challenge (id BIGINT AUTO_INCREMENT PRIMARY KEY, challenge_hash CHAR(64) UNIQUE, source_session_hash CHAR(64), expires_at TIMESTAMP, consumed_at TIMESTAMP, created_at TIMESTAMP)");
        jdbc.update("INSERT INTO control_business_user VALUES (1,'tenant','ACTIVE'),(2,'tenant','ACTIVE'),(3,'other','ACTIVE')");
        jdbc.update("INSERT INTO control_external_user_binding VALUES (11,'tenant',1,'erp','Alice','ACTIVE'),(12,'tenant',2,'crm','bob','ACTIVE'),(13,'other',3,'erp','other','ACTIVE')");
        service = new PersonalMemoryIdentityLinkService(jdbc);
        lookup = new PersonalMemoryIdentityLinkLookup(jdbc);
    }

    @Test void administratorCanLinkRevokeAndRelinkWithoutOverwritingHistory() {
        long first = tx.execute(status -> service.linkByAdministrator(admin, 12, 11, "人工核对员工资料"));
        var owner = PersonalMemoryPrincipal.business("tenant", "Alice", "erp");
        assertEquals("bob", lookup.linkedOwners(owner).get(0).owner().runtimeUserId());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> service.linkByAdministrator(admin, 11, 12, "人工核对员工资料"))).getStatusCode().value());
        tx.executeWithoutResult(status -> service.revokeByAdministrator(admin, first, "员工请求解除"));
        assertTrue(lookup.linkedOwners(owner).isEmpty());
        long second = tx.execute(status -> service.linkByAdministrator(admin, 11, 12, "重新核对员工资料"));
        assertNotEquals(first, second);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_link_event", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_link", Integer.class));
        assertEquals("platform:9", jdbc.queryForObject("SELECT verified_by FROM control_personal_memory_identity_link WHERE id=?", String.class, first));
    }

    @Test void administratorListsOnlyTheSelectedIdentityLinksAndMayInspectInvalidatedSnapshots() {
        long id = tx.execute(status -> service.linkByAdministrator(admin, 11, 12, "人工核对员工资料"));
        jdbc.update("UPDATE control_external_user_binding SET status='DISABLED' WHERE id=12");
        var result = service.linksForAdministrator(admin, 11, 0);
        assertEquals(1, result.size());
        assertEquals(id, result.get(0).id());
        assertEquals(12, result.get(0).peerBindingId());
        assertEquals("bob", result.get(0).peerExternalUserId());
        assertTrue(service.linksForAdministrator(admin, 13, 0).isEmpty());
        var unauthorized = new PlatformAuthenticatedSession(new PlatformPrincipal(8L, "x", "X"), "s", null,
                List.of(), List.of(), List.of());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.linksForAdministrator(unauthorized, 11, 0)).getStatusCode().value());
    }

    @Test void refusesUntrustedAdministratorsAndInvalidAccounts() {
        var noPermission = new PlatformAuthenticatedSession(new PlatformPrincipal(8L, "x", "X"), "s", null,
                List.of(), List.of(), List.of());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.linkByAdministrator(noPermission, 11, 12, "人工核对员工资料")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.linkByAdministrator(admin, 11, 13, "人工核对员工资料")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.linkByAdministrator(admin, 11, 12, "x")).getStatusCode().value());
        jdbc.update("UPDATE control_business_user SET status='DISABLED' WHERE id=2");
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.linkByAdministrator(admin, 11, 12, "人工核对员工资料")).getStatusCode().value());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_link", Integer.class));
    }

    @Test void auditFailureRollsBackLinkCreation() {
        jdbc.execute("DROP TABLE control_personal_memory_identity_link_event");
        assertThrows(DataAccessException.class, () -> tx.execute(status ->
                service.linkByAdministrator(admin, 11, 12, "人工核对员工资料")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_link", Integer.class));
    }

    @Test void auditFailureRollsBackRevocation() {
        long id = tx.execute(status -> service.linkByAdministrator(admin, 11, 12, "人工核对员工资料"));
        jdbc.execute("DROP TABLE control_personal_memory_identity_link_event");
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status ->
                service.revokeByAdministrator(admin, id, "员工请求解除")));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM control_personal_memory_identity_link WHERE id=?", String.class, id));
        assertEquals(1, lookup.linkedOwners(PersonalMemoryPrincipal.business("tenant", "Alice", "erp")).size());
    }

    @Test void onlyLinkedEmployeeCanRevokeAndAuditIsAtomic() {
        long id = tx.execute(status -> service.linkByAdministrator(admin, 11, 12, "人工核对员工资料"));
        var foreign = new EmployeeOwner("other", 3L, "erp", "erp", "other", "other", List.of());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> tx.executeWithoutResult(status -> service.revokeByEmployee(foreign, id))).getStatusCode().value());
        var alice = new EmployeeOwner("tenant", 1L, "erp", "erp", "Alice", "Alice", List.of());
        tx.executeWithoutResult(status -> service.revokeByEmployee(alice, id));
        assertTrue(lookup.linkedOwners(PersonalMemoryPrincipal.business("tenant", "Alice", "erp")).isEmpty());
        assertEquals("employee:11", jdbc.queryForObject("SELECT actor FROM control_personal_memory_identity_link_event WHERE link_id=? AND action='REVOKE'", String.class, id));
        assertEquals("SELF", jdbc.queryForObject("SELECT verification_method FROM control_personal_memory_identity_link_event WHERE link_id=? AND action='REVOKE'", String.class, id));
    }

    @Test void employeeCannotRevokeIfLinkSnapshotNoLongerMatches() {
        long id = tx.execute(status -> service.linkByAdministrator(admin, 11, 12, "人工核对员工资料"));
        jdbc.update("UPDATE control_personal_memory_identity_link SET left_external_user_id='different' WHERE id=?", id);
        var alice = new EmployeeOwner("tenant", 1L, "erp", "erp", "Alice", "Alice", List.of());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> tx.executeWithoutResult(status -> service.revokeByEmployee(alice, id))).getStatusCode().value());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM control_personal_memory_identity_link WHERE id=?", String.class, id));
    }

    @Test void employeesConfirmFromTwoLiveAccountsAndCodeCannotBeReused() {
        var sessions = new EmployeePortalSessionService();
        var challenge = new PersonalMemoryIdentityChallengeService(sessions, service, jdbc);
        String source = portal(sessions, "erp", "Alice", 1);
        String target = portal(sessions, "crm", "bob", 2);
        var issued = tx.execute(status -> challenge.issue(source));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_challenge WHERE challenge_hash=?", Integer.class, issued.code()));
        long id = tx.execute(status -> challenge.confirm(target, issued.code()));
        assertEquals(1, lookup.linkedOwners(PersonalMemoryPrincipal.business("tenant", "Alice", "erp")).size());
        assertEquals("SELF", jdbc.queryForObject("SELECT verification_method FROM control_personal_memory_identity_link WHERE id=?", String.class, id));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> challenge.confirm(target, issued.code()))).getStatusCode().value());
    }

    @Test void employeeConfirmationRejectsSignedOutSourceAndDisabledTarget() {
        var sessions = new EmployeePortalSessionService();
        var challenge = new PersonalMemoryIdentityChallengeService(sessions, service, jdbc);
        String source = portal(sessions, "erp", "Alice", 1);
        String target = portal(sessions, "crm", "bob", 2);
        var issued = tx.execute(status -> challenge.issue(source));
        sessions.end(source);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> challenge.confirm(target, issued.code()))).getStatusCode().value());
        String secondSource = portal(sessions, "erp", "Alice", 1);
        var second = tx.execute(status -> challenge.issue(secondSource));
        jdbc.update("UPDATE control_business_user SET status='DISABLED' WHERE id=2");
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> challenge.confirm(target, second.code()))).getStatusCode().value());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_link", Integer.class));
    }

    @Test void employeeChallengeExpiresAndFailedAuditRollsBackItsConsumption() {
        var sessions = new EmployeePortalSessionService();
        var challenge = new PersonalMemoryIdentityChallengeService(sessions, service, jdbc);
        String source = portal(sessions, "erp", "Alice", 1);
        String target = portal(sessions, "crm", "bob", 2);
        var issued = tx.execute(status -> challenge.issue(source));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> challenge.confirm(source, issued.code()))).getStatusCode().value());
        jdbc.update("UPDATE control_personal_memory_identity_challenge SET expires_at=?",
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> challenge.confirm(target, issued.code()))).getStatusCode().value());
        jdbc.update("UPDATE control_personal_memory_identity_challenge SET expires_at=?",
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1));
        jdbc.execute("DROP TABLE control_personal_memory_identity_link_event");
        assertThrows(DataAccessException.class, () -> tx.execute(status -> challenge.confirm(target, issued.code())));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_link", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_challenge WHERE consumed_at IS NOT NULL", Integer.class));
    }

    @Test void concurrentConfirmationsConsumeCodeOnlyOnce() throws Exception {
        var sessions = new EmployeePortalSessionService();
        var challenge = new PersonalMemoryIdentityChallengeService(sessions, service, jdbc);
        String source = portal(sessions, "erp", "Alice", 1);
        String target = portal(sessions, "crm", "bob", 2);
        var issued = tx.execute(status -> challenge.issue(source));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> attempt = () -> {
                gate.await();
                try { tx.execute(status -> challenge.confirm(target, issued.code())); return true; }
                catch (ResponseStatusException conflict) { assertEquals(409, conflict.getStatusCode().value()); return false; }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            gate.countDown();
            assertNotEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_link", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_link_event", Integer.class));
        } finally { executor.shutdownNow(); }
    }

    @Test void sourceLogoutDuringConfirmationMustNotCommitAnAssociation() throws Exception {
        assertLogoutDuringConfirmationRollsBack(true);
    }

    @Test void targetLogoutDuringConfirmationMustNotCommitAnAssociation() throws Exception {
        assertLogoutDuringConfirmationRollsBack(false);
    }

    private void assertLogoutDuringConfirmationRollsBack(boolean sourceLogout) throws Exception {
        var sessions = new EmployeePortalSessionService();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var delayedLinks = new PersonalMemoryIdentityLinkService(jdbc) {
            @Override long linkByVerifiedEmployees(EmployeeOwner first, EmployeeOwner second) {
                entered.countDown();
                try { if (!resume.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return super.linkByVerifiedEmployees(first, second);
            }
        };
        var challenge = new PersonalMemoryIdentityChallengeService(sessions, delayedLinks, jdbc);
        String source = portal(sessions, "erp", "Alice", 1);
        String target = portal(sessions, "crm", "bob", 2);
        var issued = tx.execute(status -> challenge.issue(source));
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> tx.execute(status -> challenge.confirm(target, issued.code())));
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            sessions.end(sourceLogout ? source : target);
            resume.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_link", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_link_event", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_challenge WHERE consumed_at IS NOT NULL", Integer.class));
        } finally { resume.countDown(); pool.shutdownNow(); }
    }

    @Test void employeeAndAdministratorOperateThroughHttpWithRealTransactions() throws Exception {
        var sessions = new EmployeePortalSessionService();
        var linkProxy = transactional(service);
        var challenge = transactional(new PersonalMemoryIdentityChallengeService(sessions, linkProxy, jdbc));
        var auth = mock(PlatformRequestAuthorization.class);
        when(auth.requireGlobalPermission(any(), eq(PlatformPermissions.BUSINESS_USER_MANAGE))).thenReturn(admin);
        var mvc = MockMvcBuilders.standaloneSetup(new EmployeeMemoryIdentityController(sessions, challenge, linkProxy),
                        new PersonalMemoryIdentityAdminController(auth, linkProxy))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var json = new ObjectMapper();
        String source = portal(sessions, "erp", "Alice", 1);
        String target = portal(sessions, "crm", "bob", 2);
        var issued = mvc.perform(post("/api/employee-portal/memory-identities/challenges")
                        .header("Authorization", "Bearer " + source))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        String code = json.readTree(issued.getResponse().getContentAsString()).path("data").path("code").asText();
        assertTrue(code.startsWith("rml_"));
        var confirmation = mvc.perform(post("/api/employee-portal/memory-identities/confirm")
                        .header("Authorization", "Bearer " + target).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("code", code))))
                .andExpect(status().isOk()).andReturn();
        long linkId = json.readTree(confirmation.getResponse().getContentAsString()).path("data").asLong();
        assertTrue(linkId > 0);
        mvc.perform(post("/api/employee-portal/memory-identities/confirm")
                        .header("Authorization", "Bearer " + target).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("code", code)))).andExpect(status().isConflict());
        mvc.perform(delete("/api/employee-portal/memory-identities/{id}", linkId)
                .header("Authorization", "Bearer " + source)).andExpect(status().isOk());
        var adminCreate = mvc.perform(post("/api/platform/personal-memory-identity-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("firstBindingId", 11, "secondBindingId", 12, "evidence", "人工核对员工资料"))))
                .andExpect(status().isOk()).andReturn();
        long adminId = json.readTree(adminCreate.getResponse().getContentAsString()).path("id").asLong();
        assertTrue(adminId > 0);
        mvc.perform(get("/api/platform/personal-memory-identity-links").param("bindingId", "11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(adminId));
        mvc.perform(post("/api/platform/personal-memory-identity-links/{id}/revoke", adminId)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("evidence", "员工请求解除"))))
                .andExpect(status().isOk());
        assertTrue(lookup.linkedOwners(PersonalMemoryPrincipal.business("tenant", "Alice", "erp")).isEmpty());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_link_event", Integer.class));
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private String portal(EmployeePortalSessionService sessions, String app, String user, long ownerId) {
        var owner = new EmployeeOwner("tenant", ownerId, app, app, user, user, List.of());
        var grant = new EmployeePortalSessionService.ChatGrant("tenant", app, app, "agent", user, null,
                user, List.of(), Map.of(), null, Instant.now().plusSeconds(600));
        return sessions.redeem(sessions.issueTicket(owner, grant).value()).orElseThrow().value();
    }
}
