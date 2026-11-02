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
    private PersonalMemoryMapper memoryMapper;

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
        memoryMapper = session.getMapper(PersonalMemoryMapper.class);
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

    @Test void recallDoesNotMixSameUserAcrossBusinessSystemsOrLegacyDomain() {
        var a = PersonalMemoryPrincipal.business("tenant", "42", "erp-a");
        var b = PersonalMemoryPrincipal.business("tenant", "42", "erp-b");
        var legacy = new PersonalMemoryPrincipal("tenant", "42", null, null, null);
        service.remember(a, new RememberCommand("PREFERENCE", null, null, "系统甲偏好", null, null, null,
                null, null, null, null, null, null));
        service.remember(b, new RememberCommand("PREFERENCE", null, null, "系统乙偏好", null, null, null,
                null, null, null, null, null, null));
        service.remember(legacy, new RememberCommand("PREFERENCE", null, null, "历史偏好", null, null, null,
                null, null, null, null, null, null));
        var recall = new PersonalMemoryRecallService(service, 8, 6000);
        String first = recall.recall(a, java.util.Map.of("message", "偏好", "projectCode", "erp-a")).toString();
        String second = recall.recall(b, java.util.Map.of("message", "偏好", "projectCode", "erp-b")).toString();
        assertTrue(first.contains("系统甲偏好"));
        assertFalse(first.contains("系统乙偏好"));
        assertFalse(first.contains("历史偏好"));
        assertTrue(second.contains("系统乙偏好"));
        assertFalse(second.contains("系统甲偏好"));
        assertFalse(second.contains("历史偏好"));
    }

    @Test void knowledgeCandidatesAreRecheckedAgainstOwnerAndDeletedRows() {
        var a = PersonalMemoryPrincipal.business("tenant", "42", "erp-a");
        var b = PersonalMemoryPrincipal.business("tenant", "42", "erp-b");
        var legacy = new PersonalMemoryPrincipal("tenant", "42", null, null, null);
        var command = new RememberCommand("NOTE", null, null, "候选内容", null, null, null,
                null, null, null, null, null, null);
        long own = service.remember(a, command).memory().id();
        long other = service.remember(b, command).memory().id();
        long old = service.remember(legacy, command).memory().id();
        long deleted = service.remember(a, new RememberCommand("NOTE", null, null, "已删除内容", null,
                null, null, null, null, null, null, null, null)).memory().id();
        service.forget(a, deleted);

        var knowledge = mock(PersonalMemoryKnowledgeQueryClient.class);
        when(knowledge.query("tenant", "42", "候选", 24, 6000, "BUSINESS:erp-a"))
                .thenReturn(new PersonalMemoryKnowledgeQueryClient.QueryAttempt(
                        PersonalMemoryKnowledgeQueryMode.ACTIVE, true, true,
                        List.of(other, old, deleted, own), 0, "SUCCESS"));
        var withKnowledge = new PersonalMemoryService(memoryMapper, groups, new ObjectMapper(),
                null, knowledge, null, 1000);
        var result = withKnowledge.query(a, new PersonalMemoryService.QueryCommand(
                "候选", null, 8, 6000, null, null, null, "erp-a"));
        assertEquals(List.of(own), result.hits().stream().map(PersonalMemoryService.QueryHit::id).toList());
    }

    @Test void employeeConnectionCannotForgetAnUnconnectedProjectMemory() {
        var owner = PersonalMemoryPrincipal.business("tenant", "42", "erp-a");
        long otherProject = service.remember(owner, new RememberCommand("NOTE", null, null, "其他项目记忆",
                null, null, null, null, null, null, null, "erp-b", null)).memory().id();
        long connected = service.remember(owner, new RememberCommand("NOTE", null, null, "已连接项目记忆",
                null, null, null, null, null, null, null, "erp-a", null)).memory().id();
        long personal = service.remember(owner, new RememberCommand("NOTE", null, null, "个人记忆",
                null, null, null, null, null, null, null, null, null)).memory().id();
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.forgetInProjects(owner, otherProject, java.util.Set.of("erp-a"))).getStatusCode().value());
        assertNotNull(service.get(owner, otherProject));
        service.forgetInProjects(owner, connected, java.util.Set.of("erp-a"));
        service.forgetInProjects(owner, personal, java.util.Set.of("erp-a"));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get(owner, connected)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get(owner, personal)).getStatusCode().value());
    }

    @Test void sharedRecallTracksLiveBindingWithoutReadingOtherProjectOrLegacyData() {
        jdbc.execute("CREATE TABLE control_business_user (id BIGINT PRIMARY KEY, tenant_id VARCHAR(96), status VARCHAR(24))");
        jdbc.execute("CREATE TABLE control_external_user_binding (id BIGINT PRIMARY KEY, tenant_id VARCHAR(96), business_user_id BIGINT, app_id VARCHAR(96), external_user_id VARCHAR(128), status VARCHAR(24))");
        jdbc.execute("CREATE TABLE control_personal_memory_identity_link (id BIGINT PRIMARY KEY, tenant_id VARCHAR(96), left_binding_id BIGINT, right_binding_id BIGINT, left_business_user_id BIGINT, right_business_user_id BIGINT, left_app_id VARCHAR(96), left_external_user_id VARCHAR(128), right_app_id VARCHAR(96), right_external_user_id VARCHAR(128), state VARCHAR(16), active_marker TINYINT)");
        jdbc.update("INSERT INTO control_business_user VALUES (1,'tenant','ACTIVE'),(2,'tenant','ACTIVE')");
        jdbc.update("INSERT INTO control_external_user_binding VALUES (11,'tenant',1,'erp-a','42','ACTIVE'),(12,'tenant',2,'erp-b','99','ACTIVE')");
        jdbc.update("INSERT INTO control_personal_memory_identity_link VALUES (7,'tenant',11,12,1,2,'erp-a','42','erp-b','99','REVOKED',NULL)");
        var a = PersonalMemoryPrincipal.business("tenant", "42", "erp-a");
        var b = PersonalMemoryPrincipal.business("tenant", "99", "erp-b");
        var legacy = new PersonalMemoryPrincipal("tenant", "42", null, null, null);
        service.remember(a, new RememberCommand("PREFERENCE", null, null, "甲的偏好", null, null, null,
                null, null, null, null, null, null));
        service.remember(b, new RememberCommand("PREFERENCE", null, null, "乙的个人偏好", null, null, null,
                null, null, null, null, null, null));
        service.remember(b, new RememberCommand("PREFERENCE", null, null, "乙的项目偏好", null, null, null,
                null, null, null, null, "erp-b", null));
        service.remember(legacy, new RememberCommand("PREFERENCE", null, null, "历史偏好", null, null, null,
                null, null, null, null, null, null));
        var shared = new PersonalMemorySharedQueryService(service, new PersonalMemoryIdentityLinkLookup(jdbc));
        var recall = new PersonalMemoryRecallService(service, shared, 8, 6000);
        var command = new PersonalMemoryService.QueryCommand("偏好", null, 8, 6000, null, null, null, "erp-a");
        assertEquals(1, shared.query(a, command).hits().size());
        assertFalse(recall.recall(a, java.util.Map.of("message", "偏好", "projectCode", "erp-a")).toString().contains("乙的个人偏好"));
        jdbc.update("UPDATE control_personal_memory_identity_link SET state='ACTIVE', active_marker=1 WHERE id=7");
        String linked = shared.query(a, command).hits().toString();
        assertTrue(linked.contains("甲的偏好"));
        assertTrue(linked.contains("乙的个人偏好"));
        assertFalse(linked.contains("乙的项目偏好"));
        assertFalse(linked.contains("历史偏好"));
        String payload = recall.recall(a, java.util.Map.of("message", "偏好", "projectCode", "erp-a")).toString();
        assertTrue(payload.contains("乙的个人偏好"));
        assertFalse(payload.contains("乙的项目偏好"));
        jdbc.update("UPDATE control_personal_memory_identity_link SET state='REVOKED', active_marker=NULL WHERE id=7");
        assertEquals(1, shared.query(a, command).hits().size());
        assertFalse(recall.recall(a, java.util.Map.of("message", "偏好", "projectCode", "erp-a")).toString().contains("乙的个人偏好"));
    }
}
