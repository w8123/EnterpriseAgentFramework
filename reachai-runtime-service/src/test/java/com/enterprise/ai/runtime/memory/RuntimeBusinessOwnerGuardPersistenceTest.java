package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RuntimeBusinessOwnerGuardPersistenceTest {
    @Test
    void findsBusinessSessionsBeyondTheFirstBatchAndConservativelyHandlesCaseInsensitiveIds() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:runtime_owner_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE runtime_conversation_session (id BIGINT PRIMARY KEY, tenant_id VARCHAR(96), user_id VARCHAR_IGNORECASE(128), identity_source VARCHAR(32))");
        jdbc.update("INSERT INTO runtime_conversation_session VALUES (1,'tenant-a','Alice','AGENT'), (2,'tenant-a','alice','AGENT'), (3,'tenant-b','Alice','MCP_EMPLOYEE'), (4,'tenant-a','Alice','EMBED_SESSION')");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(RuntimeConversationSessionMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(config);
        try (var session = factory.getObject().openSession()) {
            var mapper = session.getMapper(RuntimeConversationSessionMapper.class);
            assertEquals(1, mapper.countBusinessOwnerSessions("tenant-a", "alice"));
            assertEquals(1, mapper.countBusinessOwnerSessions("tenant-b", "Alice"));
            assertEquals(0, mapper.countBusinessOwnerSessions("tenant-c", "Alice"));
            var candidates = mapper.selectOwnerEraseCandidates("tenant-a", "Alice", 2);
            assertEquals(2, candidates.size());
            assertEquals("Alice", candidates.get(0).getUserId());
            assertEquals("alice", candidates.get(1).getUserId());
            var store = mock(RuntimeSessionRetentionStore.class);
            var eraser = mock(RuntimeSessionStateEraser.class);
            when(store.ownerEraseCandidates("tenant-a", "Alice", 2)).thenReturn(candidates);
            var service = new RuntimeSessionRetentionService(store, eraser,
                    new RuntimeSessionRetentionProperties(true, 30, 24, 100, 300));
            assertEquals("RUNTIME_OWNER_SCOPE_REQUIRED", assertThrows(RuntimeSessionRetentionException.class,
                    () -> service.eraseOwner("tenant-a", "Alice", "PRIVACY_REQUEST", null, 2, "42")).code());
            verify(store, never()).claimOwnerPurge(any(), anyString(), anyString(), any(), anyString(), anyString(), any());
        }
    }
}
