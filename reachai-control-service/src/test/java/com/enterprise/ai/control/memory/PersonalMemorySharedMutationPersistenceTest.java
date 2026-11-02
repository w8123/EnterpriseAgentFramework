package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class PersonalMemorySharedMutationPersistenceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private PersonalMemoryService memories;
    private PersonalMemorySharedMutationService mutation;
    private final PersonalMemoryPrincipal a = PersonalMemoryPrincipal.business("tenant", "42", "erp-a");
    private final PersonalMemoryPrincipal b = PersonalMemoryPrincipal.business("tenant", "99", "erp-b");

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:shared_mutation_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE control_personal_memory_group (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(96), owner_id VARCHAR(128), owner_domain VARCHAR(128), scope_key VARCHAR(128), project_code VARCHAR(96), project_name VARCHAR(128), name VARCHAR(64), kind VARCHAR(16), sort_order INT, created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE(tenant_id,owner_domain,owner_id,scope_key,name))");
        jdbc.execute("CREATE TABLE control_personal_memory (id BIGINT AUTO_INCREMENT PRIMARY KEY, memory_key VARCHAR(128) UNIQUE, tenant_id VARCHAR(96), owner_id VARCHAR(128), owner_domain VARCHAR(128), scope_key VARCHAR(128), project_code VARCHAR(96), group_id BIGINT, memory_type VARCHAR(16), title VARCHAR(256), content CLOB, content_sha256 CHAR(64), semantic_key VARCHAR(80), tags_json VARCHAR(2000), source_type VARCHAR(24), source_agent_id VARCHAR(128), source_session_id VARCHAR(128), expires_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE(tenant_id,owner_domain,owner_id,scope_key,content_sha256), UNIQUE(tenant_id,owner_domain,owner_id,scope_key,semantic_key))");
        jdbc.execute("CREATE TABLE control_business_user (id BIGINT PRIMARY KEY, tenant_id VARCHAR(96), status VARCHAR(24))");
        jdbc.execute("CREATE TABLE control_external_user_binding (id BIGINT PRIMARY KEY, tenant_id VARCHAR(96), business_user_id BIGINT, app_id VARCHAR(96), external_user_id VARCHAR(128), status VARCHAR(24))");
        jdbc.execute("CREATE TABLE control_personal_memory_identity_link (id BIGINT PRIMARY KEY, tenant_id VARCHAR(96), left_binding_id BIGINT, right_binding_id BIGINT, left_business_user_id BIGINT, right_business_user_id BIGINT, left_app_id VARCHAR(96), left_external_user_id VARCHAR(128), right_app_id VARCHAR(96), right_external_user_id VARCHAR(128), state VARCHAR(16), active_marker TINYINT)");
        jdbc.update("INSERT INTO control_business_user VALUES (1,'tenant','ACTIVE'),(2,'tenant','ACTIVE')");
        jdbc.update("INSERT INTO control_external_user_binding VALUES (11,'tenant',1,'erp-a','42','ACTIVE'),(12,'tenant',2,'erp-b','99','ACTIVE')");
        jdbc.update("INSERT INTO control_personal_memory_identity_link VALUES (7,'tenant',11,12,1,2,'erp-a','42','erp-b','99','ACTIVE',1)");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PersonalMemoryMapper.class); config.addMapper(PersonalMemoryGroupMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        var template = new SqlSessionTemplate(factory.getObject());
        var memoryMapper = template.getMapper(PersonalMemoryMapper.class);
        ObjectProvider<PersonalMemoryProjectCatalog> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(() -> List.of());
        var groups = new PersonalMemoryGroupService(template.getMapper(PersonalMemoryGroupMapper.class), memoryMapper, provider);
        memories = new PersonalMemoryService(memoryMapper, groups, new ObjectMapper());
        mutation = new PersonalMemorySharedMutationService(memories, new PersonalMemoryIdentityLinkLookup(jdbc), jdbc);
    }

    @Test void deletesLinkedPersonalMemoryButNeverLinkedProjectOrUnrelatedMemory() {
        long personal = remember(b, "共享个人记忆", null);
        long project = remember(b, "对方项目记忆", "erp-b");
        var unrelated = PersonalMemoryPrincipal.business("tenant", "100", "other");
        long foreign = remember(unrelated, "无关记忆", null);
        tx.executeWithoutResult(status -> mutation.forget(a, personal, Set.of("erp-a")));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> memories.get(b, personal)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> mutation.forget(a, project, Set.of("erp-a")))).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> mutation.forget(a, foreign, Set.of("erp-a")))).getStatusCode().value());
        assertNotNull(memories.get(b, project));
        assertNotNull(memories.get(unrelated, foreign));
    }

    @Test void revokedOrReboundIdentityCannotDeleteLinkedMemory() {
        long personal = remember(b, "共享个人记忆", null);
        jdbc.update("UPDATE control_personal_memory_identity_link SET state='REVOKED', active_marker=NULL WHERE id=7");
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> mutation.forget(a, personal, Set.of("erp-a")))).getStatusCode().value());
        jdbc.update("UPDATE control_personal_memory_identity_link SET state='ACTIVE', active_marker=1 WHERE id=7");
        jdbc.update("UPDATE control_external_user_binding SET external_user_id='other' WHERE id=12");
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> mutation.forget(a, personal, Set.of("erp-a")))).getStatusCode().value());
        assertNotNull(memories.get(b, personal));
    }

    @Test void managementListsAndEditsOnlyLinkedPersonalLayer() {
        long personal = remember(b, "原个人记忆", null);
        long project = remember(b, "对方项目记忆", "erp-b");
        long inconsistent = remember(b, "不一致的项目元数据", null);
        jdbc.update("UPDATE control_personal_memory SET project_code='erp-b' WHERE id=?", inconsistent);
        long inverse = remember(b, "不一致的范围标记", "erp-b");
        jdbc.update("UPDATE control_personal_memory SET project_code=NULL WHERE id=?", inverse);
        var management = new PersonalMemorySharedManagementService(new PersonalMemoryIdentityLinkLookup(jdbc), memories);
        assertEquals("erp-b", management.systems(a).get(0).projectCode());
        assertEquals(List.of(personal), management.list(a, 7, null, 50, 0).items().stream()
                .map(PersonalMemoryService.MemoryView::id).toList());
        var edit = new PersonalMemoryService.UpdateCommand(null, null, "修改后的个人记忆", null, null, null);
        tx.execute(status -> mutation.updateLinked(a, 7, personal, edit));
        assertEquals("修改后的个人记忆", memories.get(b, personal).content());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> mutation.updateLinked(a, 7, project, edit))).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> mutation.updateLinked(a, 7, inconsistent, edit))).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> mutation.forgetLinked(a, 7, inverse))).getStatusCode().value());
        var move = new PersonalMemoryService.UpdateCommand(null, null, "x", null, null, 123L);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> mutation.updateLinked(a, 7, personal, move))).getStatusCode().value());
        jdbc.update("UPDATE control_personal_memory_identity_link SET state='REVOKED', active_marker=NULL WHERE id=7");
        assertTrue(management.systems(a).isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> management.list(a, 7, null, 50, 0)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> tx.execute(status -> mutation.updateLinked(a, 7, personal, edit))).getStatusCode().value());
        assertEquals("修改后的个人记忆", memories.get(b, personal).content());
    }

    @Test void revocationBetweenLookupAndLockStopsDeletion() throws Exception {
        long personal = remember(b, "并发撤销记忆", null);
        var captured = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var lookup = new PersonalMemoryIdentityLinkLookup(jdbc) {
            @Override public List<LinkedOwner> linkedOwners(PersonalMemoryPrincipal owner) {
                var result = super.linkedOwners(owner);
                captured.countDown();
                try { if (!resume.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return result;
            }
        };
        var racingMutation = new PersonalMemorySharedMutationService(memories, lookup, jdbc);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var deletion = executor.submit(() -> assertThrows(ResponseStatusException.class,
                    () -> tx.execute(status -> racingMutation.forget(a, personal, Set.of("erp-a")))).getStatusCode().value());
            assertTrue(captured.await(10, java.util.concurrent.TimeUnit.SECONDS));
            jdbc.update("UPDATE control_personal_memory_identity_link SET state='REVOKED', active_marker=NULL WHERE id=7");
            resume.countDown();
            assertEquals(404, deletion.get(10, java.util.concurrent.TimeUnit.SECONDS).intValue());
            assertNotNull(memories.get(b, personal));
        } finally { resume.countDown(); executor.shutdownNow(); }
    }

    private long remember(PersonalMemoryPrincipal owner, String content, String project) {
        return memories.remember(owner, new RememberCommand("NOTE", null, null, content, null, null,
                null, null, null, null, null, project, null)).memory().id();
    }
}
