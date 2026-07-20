package com.enterprise.ai.control.aiassist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlAiAccessSessionServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ControlAiAccessSessionService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:ai_access_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
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
        service = new ControlAiAccessSessionService(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void persistsCanonicalSixStepProgressAcrossServiceInstances() {
        var opened = service.openSdkSession(7L, "orders", "Codex", null);
        assertEquals("sdk-access-7", opened.sessionId());
        assertEquals(6, opened.totalSteps());
        assertEquals(0, opened.completedSteps());
        assertEquals(6, opened.steps().size());

        List<String> reportedKeys = List.of(
                "project-manifest",
                "backend-sdk",
                "gateway-route",
                "connectivity-check",
                "embed-token-broker",
                "handoff-summary");
        for (String stepKey : reportedKeys) {
            service.reportSdkStep(7L, "orders", "sdk-access-7", stepKey, Map.of(
                    "status", "PASS",
                    "message", stepKey + " verified",
                    "files", List.of("proof.txt"),
                    "evidence", Map.of("verified", true)), "Codex");
        }

        ControlAiAccessSessionService reloadedService =
                new ControlAiAccessSessionService(jdbcTemplate, new ObjectMapper());
        var latest = reloadedService.openSdkSession(7L, "orders", null, "sdk-access-7");
        assertEquals("PASS", latest.status());
        assertEquals(6, latest.completedSteps());
        assertEquals(6, latest.steps().size());
        assertEquals(List.of("PROJECT", "STARTER", "GATEWAY", "BUSINESS_API", "EMBED_TOKEN", "FINAL_CHECK"),
                latest.steps().stream().map(ControlAiAssistProjectController.AiAccessStepView::stepKey).toList());
        assertEquals("后端 Starter", latest.steps().get(1).title());
        assertEquals("proof.txt", latest.steps().get(1).files().get(0));
        assertEquals(true, latest.steps().get(1).evidence().get("verified"));
    }

    @Test
    void mergesPlatformReadinessWithoutDowngradingExplicitPass() {
        service.openSdkSession(7L, "orders", "Codex", null);
        service.reportSdkStep(7L, "orders", "sdk-access-7", "gateway-route",
                Map.of("status", "PASS", "message", "gateway verified"), "Codex");

        var check = new ControlAiAssistProjectController.SdkAccessCheckResponse(
                7L,
                "orders",
                "WARN",
                List.of(
                        new ControlAiAssistProjectController.SdkAccessReadiness(
                                "CODE_READY", "代码接入", "PASS", "code ready"),
                        new ControlAiAssistProjectController.SdkAccessReadiness(
                                "RUNTIME_READY", "Runtime 就绪", "PASS", "runtime ready"),
                        new ControlAiAssistProjectController.SdkAccessReadiness(
                                "E2E_READY", "端到端", "PENDING", "pending explicit evidence")),
                List.of());

        var merged = service.applySdkCheckResult(7L, "orders", "sdk-access-7", check);
        assertEquals(4, merged.completedSteps());
        assertEquals("PASS", statusOf(merged, "PROJECT"));
        assertEquals("PASS", statusOf(merged, "STARTER"));
        assertEquals("PASS", statusOf(merged, "GATEWAY"));
        assertEquals("PASS", statusOf(merged, "BUSINESS_API"));
        assertEquals("TODO", statusOf(merged, "EMBED_TOKEN"));
        assertEquals("WARN", statusOf(merged, "FINAL_CHECK"));
    }

    private String statusOf(ControlAiAssistProjectController.AiAccessSessionView session, String stepKey) {
        return session.steps().stream()
                .filter(step -> stepKey.equals(step.stepKey()))
                .findFirst()
                .orElseThrow()
                .status();
    }
}
