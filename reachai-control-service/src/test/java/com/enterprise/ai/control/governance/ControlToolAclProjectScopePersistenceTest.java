package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Exercises the real controller and mapper so batch scope cannot drift from its SQL predicate. */
class ControlToolAclProjectScopePersistenceTest {
    private SqlSession session;
    private JdbcTemplate jdbc;
    private ControlToolAclController controller;

    @BeforeEach
    void prepareDatabase() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:acl_project_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE control_tool_acl (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, role_code VARCHAR(64),
                  project_id BIGINT, project_code VARCHAR(96), target_kind VARCHAR(16),
                  target_name VARCHAR(128), permission VARCHAR(16), note VARCHAR(128),
                  enabled BOOLEAN, created_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ControlToolAclMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        session = factory.getObject().openSession(true);
        var mapper = session.getMapper(ControlToolAclMapper.class);
        controller = new ControlToolAclController(mapper, new ControlToolAclDecisionService(mapper));
    }

    @AfterEach
    void closeSession() {
        if (session != null) session.close();
    }

    @Test
    void batchUpdatesOnlyTheExactProjectAndIsIdempotent() {
        var global = create(null, null, "DENY");
        var other = create(8L, "billing", "DENY");
        var current = create(7L, "orders", "DENY");

        batch(7L, " orders ", "ALLOW");
        batch(7L, "orders", "ALLOW");

        assertEquals(3, rowCount());
        assertEquals("DENY", permission(global.getId()));
        assertEquals("DENY", permission(other.getId()));
        assertEquals("ALLOW", permission(current.getId()));
        assertEquals("orders", jdbc.queryForObject("SELECT project_code FROM control_tool_acl WHERE id=?",
                String.class, current.getId()));
    }

    @Test
    void batchInsertsAProjectRuleWithoutOverwritingTheGlobalRule() {
        var global = create(null, null, "DENY");
        batch(7L, "orders", "ALLOW");
        batch(7L, "orders", "DENY");

        assertEquals(2, rowCount());
        assertEquals("DENY", permission(global.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM control_tool_acl WHERE project_id=7 AND project_code='orders'",
                Integer.class));
    }

    @Test
    void globalBatchDoesNotMatchAnExistingProjectRule() {
        var current = create(7L, "orders", "DENY");
        batch(null, null, "ALLOW");

        assertEquals(2, rowCount());
        assertEquals("DENY", permission(current.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM control_tool_acl WHERE project_id IS NULL AND project_code IS NULL AND permission='ALLOW'",
                Integer.class));
    }

    @Test
    void partialAndDifferentProjectIdentitiesRemainSeparate() {
        create(null, null, "DENY");
        create(7L, null, "DENY");
        create(null, "orders", "DENY");
        create(7L, "billing", "DENY");

        batch(7L, "orders", "ALLOW");
        batch(7L, "orders", "ALLOW");

        assertEquals(5, rowCount());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM control_tool_acl WHERE permission='DENY'", Integer.class));
    }

    @Test
    void explainUsesBoundJsonProjectIdentityAndKeepsOtherProjectsDenied() throws Exception {
        create(7L, "orders", "ALLOW");
        var json = new ObjectMapper();
        var current = json.readValue("""
                {"roles":["ops"],"targets":[{"kind":"TOOL","name":"orders.search"}],"projectId":7,"projectCode":" orders "}
                """, ControlToolAclController.ExplainRequest.class);
        var other = new ControlToolAclController.ExplainRequest(current.roles(), current.targets(), 8L, "billing");
        var global = new ControlToolAclController.ExplainRequest(current.roles(), current.targets(), null, null);

        assertEquals("ALLOW", controller.explain(current).getBody().get("orders.search"));
        assertEquals("DENY_NO_MATCH", controller.explain(other).getBody().get("orders.search"));
        assertEquals("DENY_NO_MATCH", controller.explain(global).getBody().get("orders.search"));
    }

    @Test
    void batchBindsJsonScopeAndExplainStillHonorsGlobalDeny() throws Exception {
        var request = new ObjectMapper().readValue("""
                {"roleCode":"ops","permission":"ALLOW","targets":[{"kind":"TOOL","name":"orders.search"}],"projectId":7,"projectCode":"orders"}
                """, ControlToolAclController.GrantBatchRequest.class);
        controller.grantBatch(request);
        create(null, null, "DENY");

        assertEquals("DENY_EXPLICIT", controller.explain(new ControlToolAclController.ExplainRequest(
                List.of("ops"), request.targets(), 7L, "orders")).getBody().get("orders.search"));
        assertEquals("SKIPPED", controller.explain(new ControlToolAclController.ExplainRequest(
                List.of(), request.targets(), 7L, "orders")).getBody().get("orders.search"));
    }

    @Test
    void editWithoutProjectFieldsPreservesTheStoredScopeAndReturnedIdentity() throws Exception {
        var current = create(7L, "orders", "ALLOW");
        var request = new ObjectMapper().readValue("""
                {"roleCode":"ops","targetKind":"TOOL","targetName":"orders.search","permission":"DENY"}
                """, ControlToolAclController.ToolAclUpdateRequest.class);
        var updated = controller.update(current.getId(), request).getBody();

        assertEquals(7L, updated.getProjectId());
        assertEquals("orders", updated.getProjectCode());
        assertEquals(7L, jdbc.queryForObject("SELECT project_id FROM control_tool_acl WHERE id=?", Long.class, current.getId()));
        assertEquals("orders", jdbc.queryForObject("SELECT project_code FROM control_tool_acl WHERE id=?", String.class, current.getId()));
        assertEquals("DENY", permission(current.getId()));
    }

    @Test
    void explicitPlatformEditActuallyClearsBothProjectRestrictions() throws Exception {
        var current = create(7L, "orders", "ALLOW");
        var request = new ObjectMapper().readValue("""
                {"roleCode":"ops","targetKind":"TOOL","targetName":"orders.search","permission":"DENY","projectId":null,"projectCode":null}
                """, ControlToolAclController.ToolAclUpdateRequest.class);
        var updated = controller.update(current.getId(), request).getBody();

        assertNull(updated.getProjectId());
        assertNull(updated.getProjectCode());
        assertNull(jdbc.queryForObject("SELECT project_id FROM control_tool_acl WHERE id=?", Long.class, current.getId()));
        assertNull(jdbc.queryForObject("SELECT project_code FROM control_tool_acl WHERE id=?", String.class, current.getId()));
        assertEquals("DENY", permission(current.getId()));
    }

    @Test
    void partialScopeEditPreservesTheUnspecifiedRestriction() throws Exception {
        var current = create(7L, "orders", "ALLOW");
        var request = new ObjectMapper().readValue("""
                {"roleCode":"ops","targetKind":"TOOL","targetName":"orders.search","permission":"DENY","projectId":null}
                """, ControlToolAclController.ToolAclUpdateRequest.class);
        var updated = controller.update(current.getId(), request).getBody();

        assertNull(updated.getProjectId());
        assertEquals("orders", updated.getProjectCode());
        assertNull(jdbc.queryForObject("SELECT project_id FROM control_tool_acl WHERE id=?", Long.class, current.getId()));
        assertEquals("orders", jdbc.queryForObject("SELECT project_code FROM control_tool_acl WHERE id=?", String.class, current.getId()));
    }

    private ControlToolAclEntity create(Long projectId, String projectCode, String permission) {
        var rule = new ControlToolAclEntity();
        rule.setRoleCode("ops");
        rule.setProjectId(projectId);
        rule.setProjectCode(projectCode);
        rule.setTargetKind("TOOL");
        rule.setTargetName("orders.search");
        rule.setPermission(permission);
        return controller.create(rule).getBody();
    }

    private void batch(Long projectId, String projectCode, String permission) {
        controller.grantBatch(new ControlToolAclController.GrantBatchRequest("ops", permission,
                List.of(new ControlToolAclController.ToolAclTargetRef("TOOL", "orders.search")), null, projectId, projectCode));
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM control_tool_acl", Integer.class);
    }

    private String permission(Long id) {
        return jdbc.queryForObject("SELECT permission FROM control_tool_acl WHERE id=?", String.class, id);
    }
}
