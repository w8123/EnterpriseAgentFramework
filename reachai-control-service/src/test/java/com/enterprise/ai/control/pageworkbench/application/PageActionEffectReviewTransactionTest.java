package com.enterprise.ai.control.pageworkbench.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionInput;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageReport;
import com.enterprise.ai.control.pageworkbench.domain.PageWorkbenchValues.PageSource;
import com.enterprise.ai.control.pageworkbench.persistence.*;
import com.enterprise.ai.control.platform.PageActionEffectReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** H2 transaction evidence, not a substitute for MySQL isolation tests. */
class PageActionEffectReviewTransactionTest {
    @Test void reviewSerializesWithCatalogChangeAndAbaCannotRestoreIt() throws Exception {
        var f = fixture();
        var preview = f.reviews.preview(f.actor, "mall", "profile", "fill");
        var approval = f.reviews.approve(f.actor, "mall", "profile", "fill",
                new PageActionEffectReviewService.ReviewCommand("PAGE_LOCAL_DRAFT", preview.catalogHash(), "build-1", "个人资料", preview.currentRevision(), true, Map.of("nickname", "昵称")));
        assertTrue(f.reviews.resolveApproved("mall", "profile", "fill").isPresent());
        f.catalog.upsertPageCatalog(7L, "mall", report("/a", f.input), PageSource.SDK, false);
        assertEquals(approval.revision(), f.reviews.resolveApproved("mall", "profile", "fill").orElseThrow().revision());

        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(inv -> {
            Object value = inv.callRealMethod();
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
            return value;
        }).when(f.catalogTarget).findActiveActionAndPageForUpdate("mall", "profile", "fill");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var read = pool.submit(() -> f.reviews.resolveApproved("mall", "profile", "fill"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var started = new CountDownLatch(1);
            var write = pool.submit(() -> { started.countDown(); return f.catalog.upsertPageCatalog(7L, "mall", report("/b", f.input), PageSource.SDK, false); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> write.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(read.get(5, TimeUnit.SECONDS).isPresent());
            write.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
        doCallRealMethod().when(f.catalogTarget).findActiveActionAndPageForUpdate("mall", "profile", "fill");
        f.catalog.upsertPageCatalog(7L, "mall", report("/a", f.input), PageSource.SDK, false);
        assertTrue(f.reviews.resolveApproved("mall", "profile", "fill").isEmpty());
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM control_page_action_effect_review", Integer.class));
        var fresh = f.reviews.preview(f.actor, "mall", "profile", "fill");
        f.reviews.approve(f.actor, "mall", "profile", "fill",
                new PageActionEffectReviewService.ReviewCommand("PAGE_LOCAL_DRAFT", fresh.catalogHash(), "build-1", "个人资料", fresh.currentRevision(), true, Map.of("nickname", "昵称")));
        var json = new ObjectMapper();
        var changedSchema = json.createObjectNode().put("type", "object");
        changedSchema.putObject("properties").putObject("nickname").put("type", "integer");
        var changed = new ActionInput("fill", "Fill", null, "PAGE_ACTION", "READ", false, null,
                changedSchema, json.createObjectNode(), json.createObjectNode(), List.of(), null, null);
        f.catalog.upsertPageCatalog(7L, "mall", report("/a", changed), PageSource.SDK, false);
        f.catalog.upsertPageCatalog(7L, "mall", report("/a", f.input), PageSource.SDK, false);
        assertTrue(f.reviews.resolveApproved("mall", "profile", "fill").isEmpty());
    }

    private static PageReport report(String route, ActionInput input) {
        return new PageReport("profile", null, null, "Profile", null, route, null, null, null, List.of(input));
    }
    private static <T> T transactional(T target, DataSourceTransactionManager manager) {
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
    private static Fixture fixture() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:effect_review_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE control_project_page(id BIGINT PRIMARY KEY,project_id BIGINT,project_code VARCHAR(96),page_key VARCHAR(160),module_key VARCHAR(128),module_name VARCHAR(160),name VARCHAR(160),description VARCHAR(1000),route_pattern VARCHAR(512),business_page_url VARCHAR(1000),component_path VARCHAR(1000),source_type VARCHAR(32),lifecycle_status VARCHAR(24),last_discovered_at TIMESTAMP,last_verified_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE control_page_action(id BIGINT PRIMARY KEY,page_id BIGINT,project_id BIGINT,project_code VARCHAR(96),page_key VARCHAR(160),action_key VARCHAR(160),title VARCHAR(160),description VARCHAR(1000),action_type VARCHAR(32),risk_level VARCHAR(24),confirm_required BOOLEAN,permission_key VARCHAR(160),input_schema_json CLOB,output_schema_json CLOB,sample_args_json CLOB,allowed_agent_ids_json CLOB,implementation_ref VARCHAR(1000),source_type VARCHAR(32),status VARCHAR(24),metadata_json CLOB,last_verified_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE control_page_action_effect_generation(action_id BIGINT PRIMARY KEY,page_id BIGINT NOT NULL,revision BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE control_page_action_effect_review(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,action_id BIGINT,page_id BIGINT,project_id BIGINT,project_code VARCHAR(96),page_key VARCHAR(160),action_key VARCHAR(160),revision BIGINT,decision VARCHAR(24),effect VARCHAR(32),catalog_hash CHAR(64),implementation_version VARCHAR(1000),target_alias VARCHAR(80),field_labels_json CLOB,actor_user_id BIGINT,actor_session_id VARCHAR(128),created_at TIMESTAMP)");
        jdbc.update("INSERT INTO control_project_page(id,project_id,project_code,page_key,name,route_pattern,source_type,lifecycle_status) VALUES(1,7,'mall','profile','Profile','/a','SDK','ACTIVE')");
        jdbc.update("INSERT INTO control_page_action(id,page_id,project_id,project_code,page_key,action_key,title,action_type,risk_level,confirm_required,input_schema_json,output_schema_json,sample_args_json,allowed_agent_ids_json,implementation_ref,source_type,status) VALUES(2,1,7,'mall','profile','fill','Fill','PAGE_ACTION','READ',FALSE,'{\"type\":\"object\",\"properties\":{\"nickname\":{\"type\":\"string\"}}}','{}','{}','[]',NULL,'SDK','ACTIVE')");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ProjectPageMapper.class); config.addMapper(PageActionMapper.class);
        config.addMapper(PageActionEffectGenerationMapper.class); config.addMapper(PageActionEffectReviewMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        var sql = new SqlSessionTemplate(factory.getObject());
        var generations = sql.getMapper(PageActionEffectGenerationMapper.class);
        var json = new ObjectMapper();
        var target = spy(new PageCatalogApplicationService(sql.getMapper(ProjectPageMapper.class), mock(PageResourceMapper.class),
                sql.getMapper(PageActionMapper.class), json, mock(PageWorkbenchProjectPort.class)));
        target.setEffectGenerations(generations);
        var manager = new DataSourceTransactionManager(source);
        PageCatalogApplicationService catalog = transactional(target, manager);
        var reviewTarget = new PageActionEffectReviewService(catalog,
                new PageActionEffectReviewStore(generations, sql.getMapper(PageActionEffectReviewMapper.class)),
                mock(PlatformAuthAuditService.class), json);
        PageActionEffectReviewService reviews = transactional(reviewTarget, manager);
        var actor = mock(PlatformAuthenticatedSession.class);
        when(actor.user()).thenReturn(new PlatformPrincipal(9L, "reviewer", "Reviewer"));
        when(actor.sessionId()).thenReturn("live-session");
        when(actor.hasResourcePermission(PlatformPermissions.WORKFLOW_PUBLISH, "PROJECT", null, "mall")).thenReturn(true);
        when(actor.hasResourcePermission(PlatformPermissions.EMBED_MANAGE, "PROJECT", null, "mall")).thenReturn(true);
        var schema = json.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("nickname").put("type", "string");
        var input = new ActionInput("fill", "Fill", null, "PAGE_ACTION", "READ", false, null,
                schema, json.createObjectNode(), json.createObjectNode(), List.of(), null, null);
        return new Fixture(jdbc, catalog, target, reviews, actor, input);
    }
    private record Fixture(JdbcTemplate jdbc, PageCatalogApplicationService catalog, PageCatalogApplicationService catalogTarget,
                           PageActionEffectReviewService reviews, PlatformAuthenticatedSession actor, ActionInput input) { }
}
