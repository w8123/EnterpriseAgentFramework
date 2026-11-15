package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationClient;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationCommand;
import com.enterprise.ai.control.aicoding.security.AiCodingActivationAttemptService;
import com.enterprise.ai.control.aicoding.security.AiCodingSecretDigester;
import com.enterprise.ai.control.aicoding.security.AiCodingTaskTokenGuard;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiCodingMcpClaimPersistenceTest {
    @Test
    void issuedCodeIsHashedSingleUseAndBoundToOneConnectionUntilReissued() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:claim_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        for (String table : List.of("control_ai_coding_task", "control_ai_coding_task_handoff", "control_ai_coding_task_event")) {
            var ddl = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS `" + table + "`\\s*\\(.*?\\)\\s*ENGINE=.*?;").matcher(baseline);
            assertTrue(ddl.find(), table);
            jdbc.execute(ddl.group().replaceAll("(?is)\\) ENGINE=.*?;", ");")
                    .replaceAll("(?i)KEY `([^`]+)`", "KEY `" + table + "_$1`"));
        }
        jdbc.update("INSERT INTO control_ai_coding_task (task_id, project_id, project_code, capability_key, task_kind, "
                + "executor_provider, title, objective, access_mode, result_contract_key, result_contract_version, context_snapshot_json, created_by) "
                + "VALUES ('task-1', 7, 'orders', 'page', 'PAGE_MAP_SCAN', 'CODEX', 'test', 'test', 'READ_WRITE', 'report', 'v1', '{}', 'builder')");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(AiCodingTaskMapper.class);
        config.addMapper(AiCodingTaskHandoffMapper.class);
        config.addMapper(AiCodingTaskEventMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        var plugins = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor());
        factory.setPlugins(plugins);
        var sql = new SqlSessionTemplate(factory.getObject());
        var tasks = sql.getMapper(AiCodingTaskMapper.class);
        var handoffMapper = sql.getMapper(AiCodingTaskHandoffMapper.class);
        var properties = new AiCodingTaskProperties();
        properties.setSecretPepper("fixture-pepper-at-least-24-characters");
        var digester = new AiCodingSecretDigester(properties);
        var policies = mock(AiCodingCredentialPolicyService.class);
        when(policies.handoffActivationTtl(7L)).thenReturn(Duration.ofHours(1));
        when(policies.taskTokenTtl(7L)).thenReturn(Duration.ofHours(1));
        var handoffs = new AiCodingHandoffApplicationService(tasks, handoffMapper,
                sql.getMapper(AiCodingTaskEventMapper.class), properties, policies, digester,
                new AiCodingActivationAttemptService(handoffMapper), mock(AiCodingHandoffPromptFactory.class),
                mock(AiCodingPowerShellBootstrapFactory.class));
        var claims = new AiCodingConnectionClaimService(tasks, handoffs);
        var guard = new AiCodingTaskTokenGuard(tasks, handoffMapper, digester, properties);
        var session = new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"),
                "s", LocalDateTime.now().plusHours(1), List.of(), List.of(), List.of());
        var connection = new ConnectionIdentity(41L, "CODEX", session);
        var other = new ConnectionIdentity(42L, "CODEX", session);
        try {
            var issued = handoffs.issueClaimCode("task-1", "builder");
            String stored = jdbc.queryForObject("SELECT activation_code_hash FROM control_ai_coding_task_handoff", String.class);
            assertNotEquals(issued.activationCode(), stored);
            assertTrue(digester.matches(issued.activationCode(), stored));
            var legacyCommand = new ActivationCommand(AiCodingHandoffApplicationService.ACTIVATION_SCHEMA,
                    issued.activationCode(), new ActivationClient("CODEX", "legacy"));
            String handoffId = handoffs.latest("task-1").getHandoffId();
            assertEquals(410, assertThrows(ResponseStatusException.class,
                    () -> handoffs.activate(handoffId, legacyCommand, "https://example.test")).getStatusCode().value());
            properties.setLegacyHandoffEnabled(true);
            assertEquals(410, assertThrows(ResponseStatusException.class,
                    () -> handoffs.activate(handoffId, legacyCommand, "https://example.test")).getStatusCode().value());
            properties.setLegacyHandoffEnabled(false);
            assertEquals(410, assertThrows(ResponseStatusException.class,
                    () -> handoffs.issue("task-1", "https://example.test", "builder")).getStatusCode().value());
            assertEquals(401, assertThrows(ResponseStatusException.class,
                    () -> claims.claim("task-1", "wrong", connection, "https://example.test")).getStatusCode().value());
            assertEquals(1, jdbc.queryForObject("SELECT activation_attempts FROM control_ai_coding_task_handoff", Integer.class));

            var claim = claims.claim("task-1", issued.activationCode(), connection, "https://example.test");
            assertFalse(claim.toString().contains("rtt_"));
            assertEquals("mcp-connection:41", guard.requireConnectionTaskAccess("task-1", 41).getClientSessionRef());
            assertEquals(403, assertThrows(ResponseStatusException.class,
                    () -> guard.requireConnectionTaskAccess("task-1", 42)).getStatusCode().value());
            assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> claims.claim("task-1", issued.activationCode(), other, "https://example.test")).getStatusCode().value());

            var replacement = handoffs.issueClaimCode("task-1", "builder");
            assertEquals(403, assertThrows(ResponseStatusException.class,
                    () -> guard.requireConnectionTaskAccess("task-1", 41)).getStatusCode().value());
            claims.claim("task-1", replacement.activationCode(), other, "https://example.test");
            assertEquals("mcp-connection:42", guard.requireConnectionTaskAccess("task-1", 42).getClientSessionRef());

            var expiring = handoffs.issueClaimCode("task-1", "builder");
            jdbc.update("UPDATE control_ai_coding_task_handoff SET activation_expires_at = ? WHERE activation_code_hash = ?",
                    LocalDateTime.now().minusMinutes(1), digester.digest(expiring.activationCode()));
            assertEquals(410, assertThrows(ResponseStatusException.class,
                    () -> claims.claim("task-1", expiring.activationCode(), connection, "https://example.test")).getStatusCode().value());

            var limited = handoffs.issueClaimCode("task-1", "builder");
            for (int i = 0; i < properties.getMaxActivationAttempts(); i++) {
                assertEquals(401, assertThrows(ResponseStatusException.class,
                        () -> claims.claim("task-1", "incorrect", connection, "https://example.test")).getStatusCode().value());
            }
            assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> claims.claim("task-1", limited.activationCode(), connection, "https://example.test")).getStatusCode().value());
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }
}
