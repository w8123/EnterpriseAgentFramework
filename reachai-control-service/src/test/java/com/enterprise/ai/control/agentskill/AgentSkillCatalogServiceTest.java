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
                // H2 的可重复读下 FOR UPDATE 也读快照；读已提交才能模拟 MySQL 当前读看到并发事务提交的行
                "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
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
    void employeePresentationFollowsPublishedVersionInsteadOfPendingImport() throws IOException {
        var first = catalogService.importPackage(skillZip("version-presentation", "Original description.", "1.0.0"),
                new ImportCommand("presentation-test", "1.0.0", "原名称", "PROJECT", "UPLOAD", "one.zip", "operator", 7L, "yudao"));
        Long skillId = first.skill().id();
        catalogService.review(skillId, first.version().id(), new ReviewCommand("APPROVE", null, null, "reviewer"));
        catalogService.publish(skillId, first.version().id(), "operator");

        var second = catalogService.importPackage(skillZip("version-presentation", "Updated description.", "1.0.1"),
                new ImportCommand("presentation-test", "1.0.1", "新名称", "PROJECT", "UPLOAD", "two.zip", "operator", 7L, "yudao"));
        assertEquals(skillId, second.skill().id());
        assertEquals("新名称", second.version().displayNameSnapshot());
        assertEquals("原名称", catalogService.detail(skillId).skill().displayName());
        assertEquals("Original description.", catalogService.detail(skillId).skill().description());
        catalogService.review(skillId, second.version().id(), new ReviewCommand("APPROVE", null, null, "reviewer"));
        catalogService.publish(skillId, second.version().id(), "operator");
        assertEquals("新名称", catalogService.detail(skillId).skill().displayName());
        assertEquals("Updated description.", catalogService.detail(skillId).skill().description());
        catalogService.revoke(skillId, second.version().id(), "operator");
        assertEquals("原名称", catalogService.detail(skillId).skill().displayName());
        assertEquals("Original description.", catalogService.detail(skillId).skill().description());
    }

    @Autowired
    private com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort aiCatalog;

    @Test
    void personalAiUsesTheSamePrivateCatalogAndValidatesStoredPackagePaths() throws IOException {
        var imported = catalogService.importPackage(skillZip("ai-private-catalog", "Read-only catalog fixture.", "1.0.0"),
                new ImportCommand("ai-fixture", null, "私有目录测试", "PRIVATE", "UPLOAD", "fixture.zip", "builder", 7L, null));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, "previous-session");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            var actor = aiActor(7L);
            var page = (Map<?, ?>) aiCatalog.list(actor, "ai-private-catalog", null, 0, 1).getBody();
            assertEquals(1, page.get("total"));
            assertEquals(imported.skill().id(), ((AgentSkillContracts.SkillSummary) ((List<?>) page.get("items")).get(0)).id());
            assertEquals(200, aiCatalog.get(actor, imported.skill().id()).getStatusCode().value());
            assertEquals(imported.version().id(), ((VersionView) aiCatalog.version(actor, imported.skill().id(), imported.version().id()).getBody()).id());
            var file = (AgentSkillContracts.FilePreview) aiCatalog.file(actor, imported.skill().id(), imported.version().id(), "SKILL.md").getBody();
            assertTrue(file.content().contains("Read-only catalog fixture."));
            assertEquals(400, aiCatalog.file(actor, imported.skill().id(), imported.version().id(), "../SKILL.md").getStatusCode().value());
            assertEquals(404, aiCatalog.get(aiActor(8L), imported.skill().id()).getStatusCode().value());
            assertEquals(404, aiCatalog.file(aiActor(8L), imported.skill().id(), imported.version().id(), "SKILL.md").getStatusCode().value());
            assertEquals(0, ((Map<?, ?>) aiCatalog.list(aiActor(8L), "ai-private-catalog", null, 0, 1).getBody()).get("total"));
            assertEquals(0, ((Map<?, ?>) aiCatalog.bindableVersions(actor, "ai-private-catalog", null, 0, 1).getBody()).get("total"));
            assertEquals("previous-session", request.getAttribute(com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    private com.enterprise.ai.control.identity.PlatformAuthenticatedSession aiActor(long id) {
        var user = new com.enterprise.ai.control.identity.PlatformUserEntity(); user.setId(id); user.setUsername("builder-" + id);
        return new com.enterprise.ai.control.identity.PlatformAuthenticatedSession(
                com.enterprise.ai.control.identity.PlatformPrincipal.fromUser(user), "pai-fixture", java.time.LocalDateTime.now().plusHours(1),
                List.of("BUILDER"), List.of("skill:read", "skill:bind"), List.of(
                new com.enterprise.ai.control.identity.PlatformPermissionGrant("skill:read", "GLOBAL", "*"),
                new com.enterprise.ai.control.identity.PlatformPermissionGrant("skill:bind", "GLOBAL", "*")));
    }

    @Test
    void personalAiBindableCatalogUsesPublishedVersionsAndTheActualTargetProject() throws IOException {
        List<ImportResult> imported = new java.util.ArrayList<>();
        String[] visibility = { "PROJECT", "PROJECT", "PUBLIC", "PRIVATE", "PUBLIC" };
        String[] project = { "orders", "finance", null, null, null };
        for (int index = 0; index < visibility.length; index++) {
            var item = catalogService.importPackage(skillZip("ai-bind-catalog-" + index, "Binding fixture.", "1.0.0"),
                    new ImportCommand("ai-bind-fixture", null, "目录绑定 " + index, visibility[index], "UPLOAD", "fixture.zip", "builder",
                            index == 3 ? 8L : 7L, project[index]));
            imported.add(item);
            if (index < 4) {
                catalogService.review(item.skill().id(), item.version().id(), new ReviewCommand("APPROVE", "fixture", null, "reviewer"));
                catalogService.publish(item.skill().id(), item.version().id(), "publisher");
            }
        }
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
        try {
            var page = (Map<?, ?>) aiCatalog.bindableVersions(aiActor(7L), "ai-bind-catalog-", "orders", 0, 100).getBody();
            var ids = ((List<?>) page.get("items")).stream().map(item -> ((AgentSkillContracts.BindingDescriptor) item).skill().id()).toList();
            assertEquals(2, page.get("total"));
            assertTrue(ids.containsAll(List.of(imported.get(0).skill().id(), imported.get(2).skill().id())));
            assertEquals(1, ((Map<?, ?>) aiCatalog.bindableVersions(aiActor(7L), "ai-bind-catalog-", null, 0, 100).getBody()).get("total"));
            assertEquals(200, aiCatalog.reviews(aiActor(7L), imported.get(0).skill().id(), imported.get(0).version().id()).getStatusCode().value());
            assertFalse(((List<?>) aiCatalog.reviews(aiActor(7L), imported.get(0).skill().id(), imported.get(0).version().id()).getBody()).isEmpty());
            assertFalse(((AgentSkillContracts.SkillAccessCapabilities) aiCatalog.access(aiActor(7L)).getBody()).canImport());
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

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

    @Test
    void builtinRepackagedWithOtherZipTimestampsKeepsThePublishedVersion() throws IOException {
        ImportCommand command = new ImportCommand(
                "reachai", null, null, "PUBLIC", "BUILTIN", "classpath:builtin-timezone", "SYSTEM");
        VersionView published = catalogService.importTrustedBuiltin(
                skillZip("builtin-timezone", "Same files, other zip metadata.", "1.0.0", 0L), command);

        VersionView again = catalogService.importTrustedBuiltin(
                skillZip("builtin-timezone", "Same files, other zip metadata.", "1.0.0", 28_800_000L), command);

        assertEquals(published.id(), again.id());
        assertEquals(AgentSkillCatalogService.STATUS_PUBLISHED, again.status());
        assertEquals(published.sourceSha256(), again.sourceSha256());

        ImportCommand upload = new ImportCommand(
                "engineering", null, null, "PRIVATE", "UPLOAD", "upload-timezone.zip", "alice", 7L, null);
        catalogService.importPackage(skillZip("upload-timezone", "Uploads stay strict.", "1.0.0", 0L), upload);
        assertEquals("SKILL_VERSION_CONFLICT", assertThrows(AgentSkillException.class,
                () -> catalogService.importPackage(
                        skillZip("upload-timezone", "Uploads stay strict.", "1.0.0", 28_800_000L), upload)).code());
    }

    private static byte[] skillZip(String name, String description, String declaredVersion) throws IOException {
        return skillZip(name, description, declaredVersion, 0L);
    }

    private static byte[] skillZip(String name, String description, String declaredVersion, long entryTime)
            throws IOException {
        String metadata = declaredVersion == null ? "" : "metadata:\n  version: \"" + declaredVersion + "\"\n";
        String skill = "---\nname: " + name + "\ndescription: " + description + "\n"
                + metadata + "---\n# " + name + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> file : Map.of(
                    name + "/SKILL.md", skill.getBytes(StandardCharsets.UTF_8),
                    name + "/references/checklist.md", "# Checklist".getBytes(StandardCharsets.UTF_8)).entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(entryTime);
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
