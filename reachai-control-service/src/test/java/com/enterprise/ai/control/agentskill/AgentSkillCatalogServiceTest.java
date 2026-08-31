package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.ReachAiControlServiceApplication;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportCommand;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportResult;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ReviewCommand;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = ReachAiControlServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:agent_skill_catalog;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:agent-skill-test-schema.sql",
                "spring.data.redis.repositories.enabled=false",
                "eaf.embed-token.secret=test-only-agent-skill-key",
                "reachai.ai-coding-task.secret-pepper=test-only-agent-skill-pepper",
                "reachai.auth.local.bootstrap-admin.enabled=false",
                "reachai.skill.builtin-bootstrap-enabled=false",
                "reachai.context.personal-memory.outbox-enabled=false",
                "reachai.context.personal-memory.lifecycle-enabled=false"
        })
class AgentSkillCatalogServiceTest {

    private static final Path ARTIFACT_ROOT = temporaryArtifactRoot();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("reachai.skill.artifact-root", () -> ARTIFACT_ROOT.toString());
    }

    @Autowired
    private AgentSkillCatalogService catalogService;

    @Test
    void importsReviewsPublishesDownloadsAndDeprecatesImmutableVersion() throws IOException {
        byte[] archive = skillZip("invoice-check", "Check an invoice against the approved controls.", "1.2.0");
        ImportCommand command = new ImportCommand(
                "finance-team", null, "发票检查", "PROJECT", "UPLOAD", "invoice-check.zip", "alice",
                7L, "finance-core");

        ImportResult imported = catalogService.importPackage(archive, command);
        ImportResult idempotent = catalogService.importPackage(archive, command);

        assertTrue(imported.created());
        assertFalse(idempotent.created());
        assertEquals(AgentSkillCatalogService.STATUS_REVIEW_PENDING, imported.version().status());
        assertEquals("1.2.0", imported.version().version());
        assertEquals(1, catalogService.detail(imported.skill().id()).versions().size());

        VersionView approved = catalogService.review(imported.skill().id(), imported.version().id(),
                new ReviewCommand("APPROVE", "业务和安全检查通过", null, "reviewer"));
        VersionView published = catalogService.publish(imported.skill().id(), imported.version().id(), "publisher");

        assertEquals(AgentSkillCatalogService.STATUS_APPROVED, approved.status());
        assertEquals(AgentSkillCatalogService.STATUS_PUBLISHED, published.status());
        assertArrayEquals(archive, catalogService.packageBytes(imported.skill().id(), imported.version().id()));
        AgentSkillContracts.FilePreview preview = catalogService.previewFile(
                imported.skill().id(), imported.version().id(), "SKILL.md");
        assertTrue(preview.previewable());
        assertTrue(preview.content().contains("# invoice-check"));
        assertEquals(64, preview.sha256().length());
        assertEquals(imported.version().id(), catalogService.detail(imported.skill().id()).skill().latestVersionId());
        assertEquals(imported.version().id(), catalogService.detail(imported.skill().id()).skill().defaultVersionId());

        VersionView deprecated = catalogService.deprecate(
                imported.skill().id(), imported.version().id(), "publisher");
        assertEquals(AgentSkillCatalogService.STATUS_DEPRECATED, deprecated.status());
        assertNull(catalogService.detail(imported.skill().id()).skill().latestVersionId());
        assertNull(catalogService.detail(imported.skill().id()).skill().defaultVersionId());
    }

    @Test
    void refusesToOverwriteSameVersionWithDifferentBytes() throws IOException {
        ImportCommand command = new ImportCommand(
                "legal-team", "1.0.0", null, "PRIVATE", "UPLOAD", "legal.zip", "alice",
                7L, null);
        catalogService.importPackage(skillZip("legal-review", "Review legal documents.", null), command);

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> catalogService.importPackage(
                        skillZip("legal-review", "Changed content under the same version.", null), command));

        assertEquals("SKILL_VERSION_CONFLICT", failure.code());
    }

    @Test
    void idempotentReimportRepairsAMissingContentAddressedArtifact() throws IOException {
        byte[] archive = skillZip("artifact-repair", "Repair a missing immutable artifact.", "1.0.0");
        ImportCommand command = new ImportCommand(
                "engineering", null, null, "PRIVATE", "UPLOAD", "artifact-repair.zip", "alice",
                7L, null);
        ImportResult imported = catalogService.importPackage(archive, command);
        String digest = imported.version().sourceSha256();
        Path artifact = ARTIFACT_ROOT.resolve("sha256")
                .resolve(digest.substring(0, 2))
                .resolve(digest + ".zip");
        Files.delete(artifact);

        ImportResult repaired = catalogService.importPackage(archive, command);

        assertFalse(repaired.created());
        assertTrue(Files.isRegularFile(artifact));
        assertArrayEquals(archive, catalogService.packageBytes(
                imported.skill().id(), imported.version().id()));
    }

    @Test
    void reservesReachAiPublisherForTrustedBuiltinPackages() throws IOException {
        ImportCommand untrusted = new ImportCommand(
                "reachai", "9.9.9", null, "PUBLIC", "UPLOAD", "spoofed.zip", "alice");

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> catalogService.importPackage(
                        skillZip("spoofed-platform-skill", "Pretend to be a platform package.", null), untrusted));

        assertEquals("SKILL_ACCESS_DENIED", failure.code());
    }

    @Test
    void allowsIndependentPrivateInstallationsOfTheSameStandardIdentity() throws IOException {
        byte[] archive = skillZip("market-checklist", "Apply a reusable market checklist.", "1.0.0");
        ImportResult alice = catalogService.importPackage(archive, new ImportCommand(
                "marketplace", null, null, "PRIVATE", "MARKET", "market-checklist.zip", "alice",
                7L, null));
        ImportResult bob = catalogService.importPackage(archive, new ImportCommand(
                "marketplace", null, null, "PRIVATE", "MARKET", "market-checklist.zip", "bob",
                8L, null));

        org.junit.jupiter.api.Assertions.assertNotEquals(alice.skill().id(), bob.skill().id());
        assertEquals("marketplace", alice.skill().publisher());
        assertEquals(alice.version().sourceSha256(), bob.version().sourceSha256());
    }

    @Test
    void concurrentSameScopeImportsConvergeOnOneCatalogVersion() throws Exception {
        byte[] archive = skillZip("concurrent-import", "Converge concurrent imports.", "1.0.0");
        ImportCommand command = new ImportCommand(
                "engineering", null, null, "PROJECT", "UPLOAD", "concurrent.zip", "alice",
                7L, "runtime-core");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return catalogService.importPackage(archive, command);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return catalogService.importPackage(archive, command);
            });
            ready.await();
            start.countDown();
            List<ImportResult> results = List.of(first.get(), second.get());

            assertEquals(1L, results.stream().filter(ImportResult::created).count());
            assertEquals(1L, results.stream().map(result -> result.skill().id()).distinct().count());
            assertEquals(1L, results.stream().map(result -> result.version().id()).distinct().count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentBuiltinBootstrapsConvergeOnOnePublishedVersion() throws Exception {
        byte[] archive = skillZip("builtin-concurrent", "Bootstrap once across Control instances.", "1.0.0");
        ImportCommand command = new ImportCommand(
                "reachai", null, null, "PUBLIC", "BUILTIN", "classpath:builtin-concurrent", "SYSTEM");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return catalogService.importTrustedBuiltin(archive, command);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return catalogService.importTrustedBuiltin(archive, command);
            });
            ready.await();
            start.countDown();
            List<VersionView> results = List.of(first.get(), second.get());

            assertEquals(1L, results.stream().map(VersionView::id).distinct().count());
            assertTrue(results.stream().allMatch(version ->
                    AgentSkillCatalogService.STATUS_PUBLISHED.equals(version.status())));
            assertEquals(1, catalogService.detail(results.get(0).skillId()).versions().size());
        } finally {
            executor.shutdownNow();
        }
    }

    private static byte[] skillZip(String name, String description, String declaredVersion) throws IOException {
        String metadata = declaredVersion == null ? "" : "metadata:\n  version: \"" + declaredVersion + "\"\n";
        String skill = "---\nname: " + name + "\ndescription: " + description + "\n"
                + metadata + "---\n# " + name + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> file : Map.of(
                    name + "/SKILL.md", skill.getBytes(StandardCharsets.UTF_8),
                    name + "/references/checklist.md", "# Checklist".getBytes(StandardCharsets.UTF_8)).entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static Path temporaryArtifactRoot() {
        try {
            return Files.createTempDirectory("reachai-agent-skill-test-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
