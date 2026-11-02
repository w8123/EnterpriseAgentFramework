package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BusinessUserIdentityPersistenceTest {
    private SqlSession session;
    private JdbcTemplate jdbc;
    private BusinessUserDirectoryController controller;

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:business_identity_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE control_business_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(96),
                 global_user_id VARCHAR(128), display_name VARCHAR(128), email VARCHAR(128), mobile VARCHAR(64),
                 status VARCHAR(24), source VARCHAR(32), last_seen_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE control_external_user_binding (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(96),
                 business_user_id BIGINT, app_id VARCHAR(96), external_user_id VARCHAR(128), external_user_name VARCHAR(128),
                 dept_id VARCHAR(128), dept_name VARCHAR(128), status VARCHAR(24), last_seen_at TIMESTAMP,
                 created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE (tenant_id, app_id, external_user_id))
                """);
        jdbc.execute("""
                CREATE TABLE control_external_user_role_binding (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(96),
                 business_user_id BIGINT, app_id VARCHAR(96), external_user_id VARCHAR(128), role_code VARCHAR(96),
                 role_name VARCHAR(128), source VARCHAR(32), status VARCHAR(24), created_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE control_embed_session (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(96),
                 external_user_id VARCHAR(128), global_user_id VARCHAR(128), status VARCHAR(24))
                """);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(BusinessUserMapper.class); config.addMapper(ExternalUserBindingMapper.class);
        config.addMapper(ExternalUserRoleBindingMapper.class);
        config.addMapper(com.enterprise.ai.control.platform.PlatformEmbedSessionMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        session = factory.getObject().openSession(true);
        controller = new BusinessUserDirectoryController(session.getMapper(BusinessUserMapper.class),
                session.getMapper(ExternalUserBindingMapper.class), session.getMapper(ExternalUserRoleBindingMapper.class),
                mock(PlatformRequestAuthorization.class), mock(PlatformAuthAuditService.class));
        jdbc.update("INSERT INTO control_business_user(id,tenant_id,global_user_id,status) VALUES (7,'tenant-a','user-7','ACTIVE'),(8,'tenant-a','user-8','ACTIVE')");
        jdbc.update("INSERT INTO control_external_user_binding(id,tenant_id,business_user_id,app_id,external_user_id,status) VALUES (11,'tenant-a',8,'old-app','old-user','ACTIVE')");
        jdbc.update("INSERT INTO control_external_user_role_binding(tenant_id,business_user_id,app_id,external_user_id,role_code,status) VALUES ('tenant-a',8,'old-app','old-user','ADMIN','ACTIVE')");
    }
    @AfterEach void close() { if (session != null) session.close(); }

    @Test void legacyErasureDetectsDisabledBusinessBindingsAndOldEmbedSessions() {
        var guard = new com.enterprise.ai.control.compliance.BusinessIdentityErasureGuard(
                session.getMapper(ExternalUserBindingMapper.class),
                session.getMapper(com.enterprise.ai.control.platform.PlatformEmbedSessionMapper.class));
        jdbc.update("UPDATE control_external_user_binding SET status='DISABLED' WHERE id=11");
        jdbc.update("INSERT INTO control_embed_session(tenant_id,external_user_id,global_user_id,status) VALUES ('tenant-a','embed-user','global-user','EXPIRED'),(NULL,'old-default','old-global','EXPIRED')");
        assertTrue(guard.hasPotentialBusinessIdentity("tenant-a", "old-user"));
        assertTrue(guard.hasPotentialBusinessIdentity("tenant-a", "embed-user"));
        assertTrue(guard.hasPotentialBusinessIdentity("tenant-a", "global-user"));
        assertTrue(guard.hasPotentialBusinessIdentity("default", "old-default"));
        assertFalse(guard.hasPotentialBusinessIdentity("tenant-b", "embed-user"));
        assertFalse(guard.hasPotentialBusinessIdentity("tenant-a", "other-user"));
    }

    @Test void differentSystemsCannotLinkByClaimingTheSameGlobalId() {
        var sync = new BusinessUserSyncService(session.getMapper(BusinessUserMapper.class),
                session.getMapper(ExternalUserBindingMapper.class), session.getMapper(ExternalUserRoleBindingMapper.class));
        var first = sync.sync(new BusinessUserSyncService.AttestedBusinessUser(
                "tenant-a", "erp-a", "42", "claimed-person", "A", List.of()));
        var second = sync.sync(new BusinessUserSyncService.AttestedBusinessUser(
                "tenant-a", "erp-b", "42", "claimed-person", "B", List.of()));
        assertNotEquals(first.businessUserId(), second.businessUserId());
        assertNotEquals(first.globalUserId(), second.globalUserId());
        assertEquals(first.businessUserId(), sync.sync(new BusinessUserSyncService.AttestedBusinessUser(
                "tenant-a", "erp-a", "42", "different-claim", "A", List.of())).businessUserId());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM control_external_user_binding WHERE external_user_id='42'", Integer.class));
    }

    @Test void rejectsReparentingAndReplacesRolesWhenTheOwnerChangesItsOwnIdentityKey() {
        var request = mock(HttpServletRequest.class);
        var command = new BusinessUserDirectoryController.ExternalIdentityCommand(11L, "new-app", "new-user", "name", null, null, "ACTIVE", List.of("READER"));
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.saveBusinessUserIdentity(request, 7L, command)).getStatusCode().value());
        assertEquals(8L, jdbc.queryForObject("SELECT business_user_id FROM control_external_user_binding WHERE id=11", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM control_external_user_role_binding WHERE role_code='ADMIN'", Integer.class));
        controller.saveBusinessUserIdentity(request, 8L, command);
        assertEquals("new-app", jdbc.queryForObject("SELECT app_id FROM control_external_user_binding WHERE id=11", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM control_external_user_role_binding WHERE role_code='ADMIN'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM control_external_user_role_binding WHERE tenant_id='tenant-a' AND business_user_id=8 AND app_id='new-app' AND external_user_id='new-user' AND role_code='READER'", Integer.class));
    }
}
