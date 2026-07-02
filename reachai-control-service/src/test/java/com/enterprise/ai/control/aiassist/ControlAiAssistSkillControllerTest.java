package com.enterprise.ai.control.aiassist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class ControlAiAssistSkillControllerTest {

    private final ControlAiAssistSkillController controller = new ControlAiAssistSkillController();

    @Test
    void exposesPageAssistantSkillMetadataFromControlService() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/ai-assist/skills/reachai-page-assistant-onboarding/latest");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(18603);

        ResponseEntity<ControlAiAssistSkillController.SkillPackageResponse> response =
                controller.latestPageAssistantSkill(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("reachai-page-assistant-onboarding", response.getBody().name());
        assertEquals(
                "http://localhost:18603/api/ai-assist/skills/reachai-page-assistant-onboarding/latest.zip",
                response.getBody().downloadUrl());
        assertTrue(response.getBody().files().stream()
                .anyMatch(file -> "scripts/reachai-page-assistant.ps1".equals(file.path())));
    }

    @Test
    void downloadsWorkflowAiCodingSkillZipFromControlResources() throws IOException {
        ResponseEntity<byte[]> response = controller.downloadLatestWorkflowAiCodingSkill();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertZipContains(response.getBody(), "workflow-ai-coding/SKILL.md");
    }

    @Test
    void downloadsPageAssistantHelperScriptFromControlResources() throws IOException {
        ResponseEntity<byte[]> response = controller.downloadPageAssistantHelperScript();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("reachai-page-assistant.ps1 scaffold"));
        assertTrue(body.contains("reachai-page-assistant.ps1 verify"));
    }

    private static void assertZipContains(byte[] body, String expectedEntry) throws IOException {
        assertNotNull(body);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (expectedEntry.equals(entry.getName())) {
                    return;
                }
            }
        }
        throw new AssertionError("Missing zip entry: " + expectedEntry);
    }
}
