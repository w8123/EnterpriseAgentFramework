package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.agentskill.BuiltinAgentSkillSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlAiAssistSkillControllerTest {

    private final BuiltinAgentSkillSource source = new BuiltinAgentSkillSource();

    @Test
    void builtInSkillZipIsByteDeterministic() {
        assertArrayEquals(
                source.packageBytes("reachai-onboarding"),
                source.packageBytes("reachai-onboarding"));
    }

    @Test
    void packagesReachAiOnboardingSkillFromTheCanonicalClasspathTree() throws IOException {
        byte[] body = source.packageBytes("reachai-onboarding");

        for (String path : List.of(
                "SKILL.md",
                "agents/openai.yaml",
                "references/page-action-result.schema.json",
                "references/page-action-mock.html",
                "references/page-action-contract.md",
                "references/angular-page-action.md",
                "references/embed-chat-quick-reference.md",
                "references/java-sdk-api-reference.md",
                "references/gateway-examples.md",
                "examples/gateway/README.md",
                "examples/gateway/spring-cloud-gateway/ReachAiEmbedProxySecurity.java",
                "examples/gateway/spring-cloud-gateway/application-reachai-gateway.yml",
                "examples/gateway/nginx/reachai.conf",
                "examples/gateway/kong/kong.yml",
                "templates/angular/reachai-page-action.service.ts",
                "templates/angular/page-registry.example.ts",
                "scripts/install-embed-chat.mjs",
                "scripts/install-java-sdk.ps1",
                "scripts/set-reachai-registry-secret.ps1",
                "scripts/reachai-page-actions.ps1",
                "scripts/reachai-doctor.mjs",
                "artifacts/reachai-embed-chat-1.0.0-SNAPSHOT.tgz",
                "artifacts/manifest-artifact.json")) {
            assertZipContains(body, "reachai-onboarding/" + path);
        }
    }

    @Test
    void onboardingEmbedTarballMatchesTheDeclaredClasspathArtifact() throws Exception {
        byte[] skillZip = source.packageBytes("reachai-onboarding");
        byte[] tarballFromSkill = readZipEntry(skillZip, ControlEmbedChatArtifactSupport.ARTIFACT_PATH_WITHIN_SKILL);
        String expectedSha = ControlEmbedChatArtifactSupport.integritySha256();

        assertNotNull(tarballFromSkill);
        assertTrue(tarballFromSkill.length > 100);
        assertEquals(expectedSha, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(tarballFromSkill)));
        assertEquals(ControlEmbedChatArtifactSupport.loadTarballBytes().length, tarballFromSkill.length);
    }

    @Test
    void onboardingScriptsPreserveSecretAndLifecycleSafetyContracts() throws Exception {
        byte[] skillZip = source.packageBytes("reachai-onboarding");
        String installer = utf8(skillZip, "reachai-onboarding/scripts/install-embed-chat.mjs");
        String secretHelper = utf8(skillZip, "reachai-onboarding/scripts/set-reachai-registry-secret.ps1");
        String skill = utf8(skillZip, "reachai-onboarding/SKILL.md");

        assertTrue(installer.contains("spawnSync('tar'"));
        assertTrue(installer.contains("lifecycleScripts=disabled lockfileFormat=preserved"));
        assertFalse(installer.contains("spawnSync('npm'"));
        assertFalse(installer.contains("npm install"));
        assertTrue(secretHelper.contains("-AsSecureString"));
        assertTrue(secretHelper.contains("configured=$variableName target=$Target"));
        assertFalse(secretHelper.contains("Write-Output $plainValue"));
        assertTrue(skill.contains("Mount the launcher only after the authenticated application shell is ready."));
        assertTrue(skill.contains(
                "Do not initialize it on login, logout, silent-refresh, OAuth callback, or public routes."));
    }

    @Test
    void packagesWorkflowAiCodingSkillFromTheCanonicalClasspathTree() throws IOException {
        assertZipContains(source.packageBytes("workflow-ai-coding"), "workflow-ai-coding/SKILL.md");
    }

    @Test
    void packagesAgentAiCodingSkillFromTheCanonicalClasspathTree() throws IOException {
        byte[] body = source.packageBytes("agent-ai-coding");
        assertZipContains(body, "agent-ai-coding/SKILL.md");
        String skill = utf8(body, "agent-ai-coding/SKILL.md");
        assertTrue(skill.contains("/agent-skills/bindable"));
        assertTrue(skill.contains("scriptPolicy=DENY"));
    }

    private static String utf8(byte[] zip, String path) throws IOException {
        return new String(readZipEntry(zip, path), StandardCharsets.UTF_8);
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
