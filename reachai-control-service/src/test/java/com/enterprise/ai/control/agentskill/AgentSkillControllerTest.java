package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportCommand;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportResult;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillDetail;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentSkillControllerTest {

    private AgentSkillCatalogService catalogService;
    private PlatformAuthAuditService auditService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        catalogService = mock(AgentSkillCatalogService.class);
        auditService = mock(PlatformAuthAuditService.class);
        AgentSkillController controller = new AgentSkillController(
                catalogService,
                new AgentSkillAccessPolicy(),
                new AgentSkillPackageInspector(12_345L, 54_321L, 2_345L, 17),
                auditService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentSkillExceptionHandler())
                .build();
    }

    @Test
    void listFiltersPrivatePackagesOutsideTheAuthenticatedOwnerScope() throws Exception {
        SkillSummary privateOther = skill(11L, "private-skill", "PRIVATE", 8L, null);
        SkillSummary publicSkill = skill(12L, "public-skill", "PUBLIC", null, null);
        when(catalogService.list(null, null)).thenReturn(List.of(privateOther, publicSkill));
        PlatformAuthenticatedSession reader = session(7L, "skill:read", "GLOBAL", "*");

        mockMvc.perform(get("/api/skills")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("public-skill"));
    }

    @Test
    void accessReturnsServerAuthoritativeImportLimitsAndRuntimeScriptBoundary() throws Exception {
        PlatformAuthenticatedSession importer = session(7L, "*", "GLOBAL", "*");

        mockMvc.perform(get("/api/skills/access")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, importer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canImport").value(true))
                .andExpect(jsonPath("$.canImportSharedOrPublic").value(true))
                .andExpect(jsonPath("$.maxPackageBytes").value(12_345))
                .andExpect(jsonPath("$.maxExpandedBytes").value(54_321))
                .andExpect(jsonPath("$.maxSingleFileBytes").value(2_345))
                .andExpect(jsonPath("$.maxFiles").value(17))
                .andExpect(jsonPath("$.maxInstructionBytes").value(256 * 1024))
                .andExpect(jsonPath("$.scriptExecutionEnabled").value(false));
    }

    @Test
    void bindableVersionsAreFilteredByBothPermissionAndTargetAgentProject() throws Exception {
        SkillSummary finance = skill(11L, "finance-skill", "PROJECT", null, "finance-core");
        SkillSummary hr = skill(12L, "hr-skill", "PROJECT", null, "hr-core");
        SkillSummary shared = skill(13L, "shared-skill", "SHARED", null, null);
        when(catalogService.list(null, AgentSkillCatalogService.STATUS_PUBLISHED))
                .thenReturn(List.of(finance, hr, shared));
        when(catalogService.detail(11L)).thenReturn(new SkillDetail(finance,
                List.of(version(21L, 11L, "1.0.0", "PUBLISHED"))));
        when(catalogService.detail(13L)).thenReturn(new SkillDetail(shared,
                List.of(version(23L, 13L, "2.0.0", "PUBLISHED"))));
        PlatformAuthenticatedSession designer = session(7L, "skill:bind", "PROJECT", "finance-core");

        mockMvc.perform(get("/api/skills/bindable-versions")
                        .param("agentProjectCode", "finance-core")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, designer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].skill.name").value("finance-skill"))
                .andExpect(jsonPath("$[1].skill.name").value("shared-skill"));
    }

    @Test
    void projectImportUsesServerAttestedOwnerSourceAndProjectScope() throws Exception {
        byte[] archive = "zip-body".getBytes(StandardCharsets.UTF_8);
        SkillSummary importedSkill = skill(11L, "finance-skill", "PROJECT", null, "finance-core");
        VersionView importedVersion = version(21L, 11L, "1.2.3", "REVIEW_PENDING");
        when(catalogService.importPackage(any(byte[].class), any(ImportCommand.class)))
                .thenReturn(new ImportResult(importedSkill, importedVersion, true));
        PlatformAuthenticatedSession importer = session(7L, "skill:import", "PROJECT", "finance-core");
        MockMultipartFile file = new MockMultipartFile(
                "file", "C:\\uploads\\finance-skill.zip", "application/zip", archive);

        mockMvc.perform(multipart("/api/skills/imports")
                        .file(file)
                        .param("publisher", "community")
                        .param("version", "1.2.3")
                        .param("visibility", "PROJECT")
                        .param("projectCode", "finance-core")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, importer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.version.status").value("REVIEW_PENDING"));

        ArgumentCaptor<byte[]> archiveCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<ImportCommand> commandCaptor = ArgumentCaptor.forClass(ImportCommand.class);
        verify(catalogService).importPackage(archiveCaptor.capture(), commandCaptor.capture());
        assertArrayEquals(archive, archiveCaptor.getValue());
        assertEquals("UPLOAD", commandCaptor.getValue().sourceType());
        assertEquals("finance-skill.zip", commandCaptor.getValue().sourceRef());
        assertEquals("user-7", commandCaptor.getValue().operator());
        assertEquals(7L, commandCaptor.getValue().ownerUserId());
        assertEquals("finance-core", commandCaptor.getValue().projectCode());
        verify(auditService).record(eq(importer), eq("AGENT_SKILL_IMPORTED"),
                eq("AGENT_SKILL_VERSION"), eq("21"), any());
    }

    @Test
    void discoveryListsSelectableSkillsFromRepositoryBundleWithoutImporting() throws Exception {
        byte[] archive = repositoryBundle();
        PlatformAuthenticatedSession importer = session(7L, "skill:import", "GLOBAL", "*");
        MockMultipartFile file = new MockMultipartFile(
                "file", "repository.zip", "application/zip", archive);

        mockMvc.perform(multipart("/api/skills/discoveries")
                        .file(file)
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, importer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value("reachai.agent-skill-bundle-discovery.v1"))
                .andExpect(jsonPath("$.candidateCount").value(2))
                .andExpect(jsonPath("$.multiSkill").value(true))
                .andExpect(jsonPath("$.candidates[0].sourceRoot").value("repo/skills/one"))
                .andExpect(jsonPath("$.candidates[0].name").value("one"))
                .andExpect(jsonPath("$.candidates[0].selectable").value(true))
                .andExpect(jsonPath("$.candidates[1].sourceRoot").value("repo/skills/two"))
                .andExpect(jsonPath("$.candidates[1].name").value("two"));
    }

    @Test
    void discoveryRequiresImportPermissionBeforeParsingTheUpload() throws Exception {
        PlatformAuthenticatedSession reader = session(7L, "skill:read", "GLOBAL", "*");
        MockMultipartFile invalidArchive = new MockMultipartFile(
                "file", "not-even-a-zip.zip", "application/zip", "invalid".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/skills/discoveries")
                        .file(invalidArchive)
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, reader))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SKILL_ACCESS_DENIED"));

        verifyNoInteractions(catalogService, auditService);
    }

    @Test
    void selectedBundleImportPersistsOnlyCanonicalSkillAndAuditsBundleEvidence() throws Exception {
        byte[] repositoryArchive = repositoryBundle();
        SkillSummary importedSkill = skill(11L, "two", "PROJECT", null, "finance-core");
        VersionView importedVersion = version(21L, 11L, "1.0.0", "REVIEW_PENDING");
        when(catalogService.importPackage(any(byte[].class), any(ImportCommand.class)))
                .thenReturn(new ImportResult(importedSkill, importedVersion, true));
        PlatformAuthenticatedSession importer = session(7L, "skill:import", "PROJECT", "finance-core");
        MockMultipartFile file = new MockMultipartFile(
                "file", "repository.zip", "application/zip", repositoryArchive);

        mockMvc.perform(multipart("/api/skills/imports")
                        .file(file)
                        .param("sourceRoot", "repo/skills/two")
                        .param("visibility", "PROJECT")
                        .param("projectCode", "finance-core")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, importer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skill.name").value("two"));

        ArgumentCaptor<byte[]> archiveCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<ImportCommand> commandCaptor = ArgumentCaptor.forClass(ImportCommand.class);
        verify(catalogService).importPackage(archiveCaptor.capture(), commandCaptor.capture());
        AgentSkillPackageInspector.PackageInspection selected = new AgentSkillPackageInspector(
                12_345L, 54_321L, 2_345L, 17).inspect(archiveCaptor.getValue());
        assertEquals("two", selected.name());
        assertEquals("", selected.sourceRoot());
        assertEquals(List.of("SKILL.md", "assets/template.txt"),
                selected.files().stream().map(AgentSkillPackageInspector.FileEntry::path).toList());
        assertFalse(java.util.Arrays.equals(repositoryArchive, archiveCaptor.getValue()));
        assertEquals("repository.zip#repo/skills/two", commandCaptor.getValue().sourceRef());
        verify(auditService).record(eq(importer), eq("AGENT_SKILL_IMPORTED"),
                eq("AGENT_SKILL_VERSION"), eq("21"), argThat(details ->
                        AgentSkillPackageInspector.sha256(repositoryArchive)
                                .equals(details.get("bundleSourceSha256"))
                                && "repo/skills/two".equals(details.get("bundleSkillRoot"))));
    }

    @Test
    void selectedBundleImportRejectsUnknownRootBeforeCatalogMutation() throws Exception {
        PlatformAuthenticatedSession importer = session(7L, "skill:import", "GLOBAL", "*");
        MockMultipartFile file = new MockMultipartFile(
                "file", "repository.zip", "application/zip", repositoryBundle());

        mockMvc.perform(multipart("/api/skills/imports")
                        .file(file)
                        .param("sourceRoot", "repo/skills/not-present")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, importer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SKILL_PACKAGE_INVALID"));

        verifyNoInteractions(catalogService, auditService);
    }

    @Test
    void detailReturnsNotFoundInsteadOfLeakingAnotherUsersPrivatePackage() throws Exception {
        SkillSummary privateOther = skill(11L, "private-skill", "PRIVATE", 8L, null);
        when(catalogService.detail(11L)).thenReturn(new SkillDetail(privateOther, List.of()));
        PlatformAuthenticatedSession reader = session(7L, "skill:read", "GLOBAL", "*");

        mockMvc.perform(get("/api/skills/11")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, reader))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.code").value("SKILL_NOT_FOUND"));
    }

    private PlatformAuthenticatedSession session(Long userId,
                                                 String permission,
                                                 String scopeType,
                                                 String scopeValue) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(userId);
        user.setUsername("user-" + userId);
        return new PlatformAuthenticatedSession(
                user,
                "session",
                LocalDateTime.now().plusHours(1),
                List.of(),
                List.of(permission),
                List.of(new PlatformPermissionGrant(permission, scopeType, scopeValue)));
    }

    private SkillSummary skill(Long id,
                               String name,
                               String visibility,
                               Long ownerUserId,
                               String projectCode) {
        return new SkillSummary(
                id, "community", name, name, "Description", visibility,
                ownerUserId, projectCode, "ACTIVE", 21L, 21L, 1L, LocalDateTime.now(),
                "1.0.0", "PUBLISHED", "1.0.0");
    }

    private VersionView version(Long id, Long skillId, String version, String status) {
        LocalDateTime now = LocalDateTime.now();
        return new VersionView(
                id, skillId, version, status, "UPLOAD", "skill.zip",
                "a".repeat(64), "b".repeat(64), 100L,
                null, null, false, null, null, null, null, null,
                null, null, null, null, now, now);
    }

    private byte[] repositoryBundle() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo/README.md", "# Repository".getBytes(StandardCharsets.UTF_8));
        files.put("repo/skills/one/SKILL.md", ("---\nname: one\n"
                + "description: First Skill.\n---\nUse one.\n").getBytes(StandardCharsets.UTF_8));
        files.put("repo/skills/one/references/guide.md", "guide".getBytes(StandardCharsets.UTF_8));
        files.put("repo/skills/two/SKILL.md", ("---\nname: two\n"
                + "description: Second Skill.\n---\nUse two.\n").getBytes(StandardCharsets.UTF_8));
        files.put("repo/skills/two/assets/template.txt", "template".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
