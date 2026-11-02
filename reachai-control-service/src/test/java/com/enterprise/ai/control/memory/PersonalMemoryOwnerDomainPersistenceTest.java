package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real mapper predicates and uniqueness constraints for colliding business user IDs. */
@SuppressWarnings("unchecked")
class PersonalMemoryOwnerDomainPersistenceTest {
    private SqlSession session;
    private JdbcTemplate jdbc;
    private PersonalMemoryService service;
    private PersonalMemoryGroupService groups;

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:pm_domain_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE control_personal_memory_group (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(96) NOT NULL,
                  owner_id VARCHAR(128) NOT NULL, owner_domain VARCHAR(128) NOT NULL DEFAULT 'LEGACY',
                  scope_key VARCHAR(128) NOT NULL, project_code VARCHAR(96), project_name VARCHAR(128),
                  name VARCHAR(64), kind VARCHAR(16), sort_order INT, created_at TIMESTAMP, updated_at TIMESTAMP,
                  UNIQUE (tenant_id, owner_domain, owner_id, scope_key, name))
                """);
        jdbc.execute("""
                CREATE TABLE control_personal_memory (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, memory_key VARCHAR(128) UNIQUE,
                  tenant_id VARCHAR(96) NOT NULL, owner_id VARCHAR(128) NOT NULL,
                  owner_domain VARCHAR(128) NOT NULL DEFAULT 'LEGACY', scope_key VARCHAR(128), project_code VARCHAR(96),
                  group_id BIGINT, memory_type VARCHAR(16), title VARCHAR(256), content CLOB,
                  content_sha256 CHAR(64), semantic_key VARCHAR(80), tags_json VARCHAR(2000), source_type VARCHAR(24),
                  source_agent_id VARCHAR(128), source_session_id VARCHAR(128), expires_at TIMESTAMP,
                  created_at TIMESTAMP, updated_at TIMESTAMP,
                  UNIQUE (tenant_id, owner_domain, owner_id, scope_key, content_sha256),
                  UNIQUE (tenant_id, owner_domain, owner_id, scope_key, semantic_key))
                """);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PersonalMemoryMapper.class); config.addMapper(PersonalMemoryGroupMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        session = factory.getObject().openSession(true);
        var memoryMapper = session.getMapper(PersonalMemoryMapper.class);
        var groupMapper = session.getMapper(PersonalMemoryGroupMapper.class);
        ObjectProvider<PersonalMemoryProjectCatalog> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(() -> List.of());
        groups = new PersonalMemoryGroupService(groupMapper, memoryMapper, provider);
        service = new PersonalMemoryService(memoryMapper, groups, new ObjectMapper());
    }
    @AfterEach void close() { if (session != null) session.close(); }

    @Test void userIdsDifferingOnlyByCaseAreDistinctWithinOneBusinessSystem() {
        var upper = PersonalMemoryPrincipal.business("tenant", "UserA", "erp-a");
        var lower = PersonalMemoryPrincipal.business("tenant", "usera", "erp-a");
        var command = new RememberCommand("NOTE", null, null, "相同的内容", null, null, "same-request",
                null, null, null, null, null, null);
        long first = service.remember(upper, command).memory().id();
        long second = service.remember(lower, command).memory().id();
        assertNotEquals(first, second);
        assertEquals(1, service.list(upper, null, null, null, null, 50, 0).total());
        assertEquals(1, service.list(lower, null, null, null, null, 50, 0).total());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get(lower, first)).getStatusCode().value());
    }

    @Test void sameTenantAndUserStaySeparateForReadDedupGroupsAndErase() {
        var a = PersonalMemoryPrincipal.business("tenant", "42", "erp-a");
        var b = PersonalMemoryPrincipal.business("tenant", "42", "erp-b");
        var legacy = new PersonalMemoryPrincipal("tenant", "42", null, null, null);
        var command = new RememberCommand("NOTE", null, null, "相同的内容", null, null, "same-request",
                null, null, null, null, null, null);
        long first = service.remember(a, command).memory().id();
        long second = service.remember(b, command).memory().id();
        long old = service.remember(legacy, command).memory().id();
        assertNotEquals(first, second); assertNotEquals(first, old); assertNotEquals(second, old);
        assertEquals("BUSINESS:erp-a", service.export(a).ownerDomain());
        assertEquals("reachai-personal-memory-v4", service.export(a).schema());
        assertTrue(service.hasScopedOwnerData("tenant", "42"));
        assertFalse(service.hasScopedOwnerData("tenant", "unknown"));
        assertEquals(1, service.list(a, null, null, null, null, 50, 0).total());
        assertEquals(1, service.list(b, null, null, null, null, 50, 0).total());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get(b, first)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.forget(legacy, first)).getStatusCode().value());
        assertNotEquals(groups.tree(a).layers().get(0).groups().get(0).id(), groups.tree(b).layers().get(0).groups().get(0).id());
        service.eraseAll(a, new PersonalMemoryService.EraseAllCommand(PersonalMemoryService.ERASE_ALL_CONFIRMATION));
        assertEquals(0, service.list(a, null, null, null, null, 50, 0).total());
        assertEquals(1, service.list(b, null, null, null, null, 50, 0).total());
        assertEquals(1, service.list(legacy, null, null, null, null, 50, 0).total());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory", Integer.class));
    }
}
