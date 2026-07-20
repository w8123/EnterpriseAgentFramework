package com.enterprise.ai.control.aiassist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
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
        assertTrue(response.getBody().files().stream()
                .anyMatch(file -> "references/page-action-result.schema.json".equals(file.path())));
        assertTrue(response.getBody().files().stream()
                .anyMatch(file -> "references/page-action-mock.html".equals(file.path())));
    }

    @Test
    void downloadsReachAiOnboardingSkillZipWithPageActionSchemaAndMock() throws IOException {
        ResponseEntity<byte[]> response = controller.downloadLatestSkill();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertZipContains(response.getBody(), "reachai-onboarding/references/page-action-result.schema.json");
        assertZipContains(response.getBody(), "reachai-onboarding/references/page-action-mock.html");
        assertZipContains(response.getBody(),
                "reachai-onboarding/artifacts/reachai-embed-chat-1.0.0-SNAPSHOT.tgz");
    }

    @Test
    void onboardingSkillZipEmbedChatTarballMatchesManifestSha256AndClasspathArtifact() throws Exception {
        String expectedPath = ControlEmbedChatArtifactSupport.ARTIFACT_PATH_WITHIN_SKILL;
        String expectedSha = ControlEmbedChatArtifactSupport.integritySha256();
        assertNotNull(expectedSha);
        assertEquals(64, expectedSha.length());

        ResponseEntity<byte[]> skillZip = controller.downloadLatestSkill();
        assertEquals(HttpStatus.OK, skillZip.getStatusCode());
        byte[] tarballFromSkill = readZipEntry(skillZip.getBody(), expectedPath);
        assertNotNull(tarballFromSkill);
        assertTrue(tarballFromSkill.length > 100);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String skillTarballSha = HexFormat.of().formatHex(digest.digest(tarballFromSkill));
        assertEquals(expectedSha, skillTarballSha);

        byte[] classpathTarball = ControlEmbedChatArtifactSupport.loadTarballBytes();
        assertEquals(expectedSha, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(classpathTarball)));
        assertEquals(classpathTarball.length, tarballFromSkill.length);
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
        assertNotNull(readZipEntry(body, expectedEntry));
    }

    private static byte[] readZipEntry(byte[] body, String expectedEntry) throws IOException {
        assertNotNull(body);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (expectedEntry.equals(entry.getName())) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    zip.transferTo(out);
                    return out.toByteArray();
                }
            }
        }
        throw new AssertionError("Missing zip entry: " + expectedEntry);
    }
}
