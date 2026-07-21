package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.model.ControlModelCatalogClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlPageAssistantRegistrationIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private ControlAiCodingProjectController controller;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:page_assistant_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();

        ObjectMapper objectMapper = new ObjectMapper();
        ControlAiAccessSessionService sessionService =
                new ControlAiAccessSessionService(jdbcTemplate, objectMapper);
        ControlPageAssistantCatalogService catalogService =
                new ControlPageAssistantCatalogService(jdbcTemplate, objectMapper);
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        when(capabilityClient.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "bzjs8",
                "projectCode", "bzjs8",
                "projectKind", "REGISTERED",
                "environment", "dev",
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "aic_test")
        ));
        controller = new ControlAiCodingProjectController(
                capabilityClient,
                mock(RuntimeProxyClient.class),
                mock(ControlModelCatalogClient.class),
                sessionService,
                catalogService);
    }

    @Test
    void externalRegisterPersistsCatalogAndDurableProgressIdempotently() {
        var request = new ControlAiAssistProjectController.PageAssistantPageRegisterRequest(
                "page-assistant-7",
                "Cursor",
                "teamArchive.list",
                "班组档案",
                "/team-build/depart-management",
                "angular",
                "12",
                "window.__REACHAI_PAGE_BRIDGE__",
                true,
                List.of(
                        new ControlAiAssistProjectController.PageAssistantFileEvidence(
                                "src/app/pages/team-archive/list.component.ts",
                                "page-component",
                                true,
                                "abc",
                                "VERIFIED",
                                "hash verified"),
                        new ControlAiAssistProjectController.PageAssistantFileEvidence(
                                "src/app/shared/reachai/reachai-page-action.service.ts",
                                "bridge",
                                true,
                                "def",
                                "VERIFIED",
                                "hash verified")),
                List.of(
                        action("getPageState", "获取页面状态", false),
                        action("search", "执行查询", false)),
                Map.of(
                        "static", Map.of("status", "PASS", "message", "static only"),
                        "browserRuntime", Map.of("status", "SKIPPED", "message", "login state unavailable")),
                "Team archive page actions registered; browser runtime remains to be verified.");

        var first = controller.registerPageAssistantPage(7L, request).getBody();
        var second = controller.registerPageAssistantPage(7L, request).getBody();

        assertEquals(List.of("getPageState", "search"), first.registeredActions());
        assertEquals("teamArchive.list", first.registeredPage().pageKey());
        assertEquals("PAGE_ASSISTANT", first.session().scenario());
        assertEquals("teamArchive.list", first.session().targetPageKey());
        assertEquals(9, first.session().totalSteps());
        assertEquals(7, first.session().completedSteps());
        assertEquals("RUNNING", first.session().status());
        assertEquals(first.session().sessionId(), second.session().sessionId());

        assertEquals(1, count("control_page_registry"));
        assertEquals(2, count("control_page_action_registry"));
        assertEquals(1, count("control_ai_access_session"));
        assertEquals(9, count("control_ai_access_step"));
        assertEquals("班组档案", jdbcTemplate.queryForObject(
                "SELECT name FROM control_page_registry WHERE project_code = 'bzjs8' AND page_key = 'teamArchive.list'",
                String.class));

        var sessions = controller.pageAssistantSessions(7L, null).getBody();
        assertEquals(1, sessions.size());
        assertEquals("IN_PROGRESS", sessions.get(0).completionState());
        assertEquals(2, sessions.get(0).actionCount());
    }

    private ControlAiAssistProjectController.PageAssistantCatalogActionRequest action(
            String actionKey,
            String title,
            boolean confirmRequired) {
        return new ControlAiAssistProjectController.PageAssistantCatalogActionRequest(
                actionKey,
                title,
                title,
                confirmRequired,
                Map.of("type", "object"),
                Map.of("type", "object"),
                Map.of(),
                List.of(),
                Map.of("riskLevel", confirmRequired ? "HIGH" : "LOW"));
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(1) FROM " + table, Integer.class);
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE control_page_registry (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    project_code VARCHAR(96) NOT NULL,
                    app_id VARCHAR(96) NOT NULL,
                    page_key VARCHAR(160) NOT NULL,
                    name VARCHAR(160) NOT NULL,
                    route_pattern VARCHAR(512),
                    origin VARCHAR(512) NOT NULL DEFAULT '',
                    current_page_instance_id VARCHAR(128),
                    status VARCHAR(24) NOT NULL,
                    last_seen_at TIMESTAMP,
                    metadata_json CLOB,
                    UNIQUE(project_code, page_key, origin)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE control_page_action_registry (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    project_code VARCHAR(96) NOT NULL,
                    app_id VARCHAR(96) NOT NULL,
                    page_key VARCHAR(160) NOT NULL,
                    action_key VARCHAR(160) NOT NULL,
                    title VARCHAR(160) NOT NULL,
                    description VARCHAR(512),
                    confirm_required BOOLEAN,
                    input_schema_json CLOB,
                    output_schema_json CLOB,
                    sample_args_json CLOB,
                    allowed_agent_ids_json CLOB,
                    metadata_json CLOB,
                    status VARCHAR(24) NOT NULL,
                    last_seen_at TIMESTAMP,
                    UNIQUE(project_code, page_key, action_key)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE control_ai_access_session (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    session_id VARCHAR(96) NOT NULL UNIQUE,
                    project_id BIGINT NOT NULL,
                    project_code VARCHAR(128),
                    tool_name VARCHAR(64),
                    scenario VARCHAR(32) NOT NULL,
                    target_page_key VARCHAR(160),
                    target_route VARCHAR(512),
                    metadata_json CLOB,
                    status VARCHAR(24) NOT NULL,
                    total_steps INT NOT NULL,
                    completed_steps INT NOT NULL,
                    failed_steps INT NOT NULL,
                    last_message VARCHAR(512),
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE control_ai_access_step (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    session_id VARCHAR(96) NOT NULL,
                    project_id BIGINT NOT NULL,
                    step_key VARCHAR(96) NOT NULL,
                    title VARCHAR(128) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    message CLOB,
                    files_json CLOB,
                    evidence_json CLOB,
                    reported_by VARCHAR(96),
                    started_at TIMESTAMP,
                    completed_at TIMESTAMP,
                    updated_at TIMESTAMP,
                    UNIQUE(session_id, step_key)
                )
                """);
    }
}
