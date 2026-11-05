package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.pageworkbench.application.PageCatalogApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchProjectPort;
import com.enterprise.ai.control.pageworkbench.persistence.PageActionMapper;
import com.enterprise.ai.control.pageworkbench.persistence.PageResourceMapper;
import com.enterprise.ai.control.pageworkbench.persistence.ProjectPageMapper;
import com.enterprise.ai.control.runtime.RuntimeTrustedAgentExecutionGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real H2/MyBatis transaction check; this is not a substitute for a MySQL deployment test. */
class PlatformPageActionClaimPolicyTransactionTest {
    @Test
    void claimHoldsPageAndActionLocksUntilTheEventIsClaimed() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fixture = fixture(entered, release);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var claim = pool.submit(() -> fixture.claim());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var updaterStarted = new CountDownLatch(1);
            var update = pool.submit(() -> {
                updaterStarted.countDown();
                return fixture.jdbc.update("UPDATE control_page_action SET confirm_required=TRUE WHERE id=1");
            });
            assertTrue(updaterStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> update.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(200, claim.get(5, TimeUnit.SECONDS));
            assertEquals(1, update.get(5, TimeUnit.SECONDS));
            assertEquals("EXECUTING", fixture.jdbc.queryForObject(
                    "SELECT status FROM control_page_action_event WHERE id=1", String.class));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void claimWaitsForStricterPolicyCommitAndRefusesOldUnconfirmedEvent() throws Exception {
        var fixture = fixture(null, null);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var update = pool.submit(() -> fixture.transaction.execute(status -> {
                fixture.jdbc.queryForObject("SELECT id FROM control_project_page WHERE id=1 FOR UPDATE", Long.class);
                fixture.jdbc.update("UPDATE control_page_action SET confirm_required=TRUE WHERE id=1");
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return 1;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var claim = pool.submit(() -> fixture.claim());
            assertThrows(TimeoutException.class, () -> claim.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(1, update.get(5, TimeUnit.SECONDS));
            assertEquals(409, claim.get(5, TimeUnit.SECONDS));
            assertEquals("REQUESTED", fixture.jdbc.queryForObject(
                    "SELECT status FROM control_page_action_event WHERE id=1", String.class));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static Fixture fixture(CountDownLatch entered, CountDownLatch release) throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:claim_policy_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE control_project_page (id BIGINT PRIMARY KEY, project_id BIGINT, project_code VARCHAR(96),
                 page_key VARCHAR(160), module_key VARCHAR(128), module_name VARCHAR(160), name VARCHAR(160), description VARCHAR(1000),
                 route_pattern VARCHAR(512), business_page_url VARCHAR(1000), component_path VARCHAR(1000), source_type VARCHAR(32),
                 lifecycle_status VARCHAR(24), last_discovered_at TIMESTAMP, last_verified_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE control_page_action (id BIGINT PRIMARY KEY, page_id BIGINT, project_id BIGINT, project_code VARCHAR(96),
                 page_key VARCHAR(160), action_key VARCHAR(160), title VARCHAR(160), description VARCHAR(1000), action_type VARCHAR(32),
                 risk_level VARCHAR(24), confirm_required BOOLEAN, permission_key VARCHAR(160), input_schema_json CLOB, output_schema_json CLOB,
                 sample_args_json CLOB, allowed_agent_ids_json CLOB, implementation_ref VARCHAR(1000), source_type VARCHAR(32), status VARCHAR(24),
                 metadata_json CLOB, last_verified_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE control_page_action_event (id BIGINT PRIMARY KEY, request_id VARCHAR(96), session_id VARCHAR(96),
                 tenant_id VARCHAR(96), app_id VARCHAR(96), agent_id VARCHAR(128), command_type VARCHAR(32), parent_request_id VARCHAR(96),
                 node_id VARCHAR(128), action_key VARCHAR(160), title VARCHAR(256), args_json CLOB, target_page_instance_id VARCHAR(128),
                 target_page_key VARCHAR(160), target_route VARCHAR(512), confirm_required BOOLEAN, status VARCHAR(32), result_json CLOB,
                 error_message VARCHAR(1024), requested_at TIMESTAMP, completed_at TIMESTAMP)
                """);
        jdbc.update("INSERT INTO control_project_page(id,project_id,project_code,page_key,lifecycle_status) VALUES (1,1,'erp','orders.list','ACTIVE')");
        jdbc.update("INSERT INTO control_page_action(id,page_id,project_id,project_code,page_key,action_key,confirm_required,status,allowed_agent_ids_json) VALUES (1,1,1,'erp','orders.list','orders.enable',FALSE,'ACTIVE','[]')");
        jdbc.update("INSERT INTO control_page_action_event(id,request_id,session_id,tenant_id,app_id,agent_id,command_type,action_key,target_page_key,confirm_required,status) VALUES (1,'req-1','embed-1','default','erp','agent','PAGE_ACTION','orders.enable','orders.list',FALSE,'REQUESTED')");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ProjectPageMapper.class); config.addMapper(PageActionMapper.class);
        config.addMapper(PlatformPageActionEventMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        var sql = new SqlSessionTemplate(factory.getObject());
        var catalog = spy(new PageCatalogApplicationService(sql.getMapper(ProjectPageMapper.class), mock(PageResourceMapper.class),
                sql.getMapper(PageActionMapper.class), new ObjectMapper(), mock(PageWorkbenchProjectPort.class)));
        if (entered != null) doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
            return result;
        }).when(catalog).findActiveActionForUpdate(anyString(), anyString(), anyString());
        var sessions = mock(PlatformEmbedSessionService.class);
        var session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1"); session.setTenantId("default"); session.setAppId("erp");
        session.setProjectCode("erp"); session.setAgentId("agent"); session.setExternalUserId("user");
        when(sessions.requireActiveSession(eq("embed-1"), any())).thenReturn(session);
        var properties = new PlatformEmbedTokenProperties(); properties.setSecret("test-embed-token-secret");
        var tokens = new PlatformEmbedTokenService(new ObjectMapper(), properties);
        var target = new PlatformEmbedPublicController(tokens, sessions, mock(PlatformEmbedChatEventService.class),
                mock(CapabilityProxyClient.class), mock(RuntimeProxyClient.class), mock(RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class), sql.getMapper(PlatformPageActionEventMapper.class));
        target.setPageCatalog(catalog);
        var manager = new DataSourceTransactionManager(source);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var controller = (PlatformEmbedPublicController) proxy.getProxy();
        var token = tokens.issue(PlatformEmbedTokenIssueCommand.builder().tenantId("default").appId("erp").projectCode("erp")
                .agentId("agent").pageKey("orders.list").pageInstanceId("page-1").route("/orders").origin("http://localhost")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal("user", "user", "User", List.of(), Map.of()))
                .build()).token();
        return new Fixture(jdbc, controller, "Bearer " + token, new TransactionTemplate(manager));
    }

    private record Fixture(JdbcTemplate jdbc, PlatformEmbedPublicController controller, String authorization,
                           TransactionTemplate transaction) {
        int claim() {
            return controller.claimPageAction("embed-1", "req-1", authorization,
                    new PlatformEmbedPublicController.PageActionClaimRequest("req-1", "orders.enable")).getStatusCode().value();
        }
    }
}
