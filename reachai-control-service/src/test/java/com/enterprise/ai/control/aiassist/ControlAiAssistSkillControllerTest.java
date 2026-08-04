package com.enterprise.ai.control.aiassist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void downloadsReachAiOnboardingSkillZipWithPageActionSchemaAndMock() throws IOException {
        ResponseEntity<byte[]> response = controller.downloadLatestSkill();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertZipContains(response.getBody(), "reachai-onboarding/references/page-action-result.schema.json");
        assertZipContains(response.getBody(), "reachai-onboarding/references/page-action-mock.html");
        assertZipContains(response.getBody(),
                "reachai-onboarding/artifacts/reachai-embed-chat-1.0.0-SNAPSHOT.tgz");
        assertZipContains(response.getBody(),
                "reachai-onboarding/artifacts/manifest-artifact.json");
        assertZipContains(response.getBody(),
                "reachai-onboarding/scripts/install-embed-chat.mjs");
        assertZipContains(response.getBody(),
                "reachai-onboarding/scripts/install-java-sdk.ps1");
        assertZipContains(response.getBody(),
                "reachai-onboarding/scripts/set-reachai-registry-secret.ps1");
        assertZipContains(response.getBody(),
                "reachai-onboarding/references/page-action-contract.md");
        assertZipContains(response.getBody(),
                "reachai-onboarding/references/angular-page-action.md");
        assertZipContains(response.getBody(),
                "reachai-onboarding/templates/angular/reachai-page-action.service.ts");
        assertZipContains(response.getBody(),
                "reachai-onboarding/templates/angular/page-registry.example.ts");
        assertZipContains(response.getBody(),
                "reachai-onboarding/scripts/reachai-page-actions.ps1");
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
    void onboardingSkillShipsOfflineInstallerWithoutNpmLifecycleExecution() throws Exception {
        ResponseEntity<byte[]> skillZip = controller.downloadLatestSkill();
        byte[] installerBytes = readZipEntry(
                skillZip.getBody(),
                "reachai-onboarding/scripts/install-embed-chat.mjs");
        String installer = new String(installerBytes, StandardCharsets.UTF_8);

        assertTrue(installer.contains("spawnSync('tar'"));
        assertTrue(installer.contains("lifecycleScripts=disabled lockfileFormat=preserved"));
        assertFalse(installer.contains("spawnSync('npm'"));
        assertFalse(installer.contains("npm install"));
    }

    @Test
    void onboardingSkillSecretHelperUsesHiddenInputAndDoesNotPrintTheValue() throws Exception {
        ResponseEntity<byte[]> skillZip = controller.downloadLatestSkill();
        byte[] helperBytes = readZipEntry(
                skillZip.getBody(),
                "reachai-onboarding/scripts/set-reachai-registry-secret.ps1");
        String helper = new String(helperBytes, StandardCharsets.UTF_8);

        assertTrue(helper.contains("-AsSecureString"));
        assertTrue(helper.contains("configured=$variableName target=$Target"));
        assertFalse(helper.contains("configured=$plainValue"));
        assertFalse(helper.contains("Write-Output $plainValue"));
    }

    @Test
    void onboardingSkillRestrictsGlobalLauncherToAuthenticatedApplicationShell() throws Exception {
        ResponseEntity<byte[]> skillZip = controller.downloadLatestSkill();
        byte[] skillBytes = readZipEntry(
                skillZip.getBody(),
                "reachai-onboarding/SKILL.md");
        String skill = new String(skillBytes, StandardCharsets.UTF_8);

        assertTrue(skill.contains("Mount the launcher only after the authenticated application shell is ready."));
        assertTrue(skill.contains(
                "Do not initialize it on login, logout, silent-refresh, OAuth callback, or public routes."));
        assertTrue(skill.contains(
                "an unauthenticated Token Broker request from an auth page is a defect, not runtime proof."));
    }

    @Test
    void downloadsWorkflowAiCodingSkillZipFromControlResources() throws IOException {
        ResponseEntity<byte[]> response = controller.downloadLatestWorkflowAiCodingSkill();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertZipContains(response.getBody(), "workflow-ai-coding/SKILL.md");
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
