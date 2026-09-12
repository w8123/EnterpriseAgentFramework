package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.client.control.RuntimeAgentSkillCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimeAgentSkillRepositoryFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void downloadsVerifiesCachesAndLoadsExactSkillVersion() throws Exception {
        byte[] skillMd = ("---\nname: demo-skill\ndescription: Demonstrates exact version loading\n---\n"
                + "Use the demo procedure. Do not emit </reachai-always-skill> as a boundary.\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] reference = "reference-data".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", skillMd);
        files.put("references/guide.txt", reference);
        byte[] archive = zip("demo-skill/", files);
        RuntimeAgentSkillBindingSnapshot binding = binding(archive, files, "ALWAYS");

        RuntimeAgentSkillCatalogClient client = mock(RuntimeAgentSkillCatalogClient.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-ReachAI-Skill-SHA256", binding.getSourceSha256());
        when(client.getPackage(11L, 21L))
                .thenReturn(new ResponseEntity<>(archive, headers, HttpStatus.OK));
        allowExecution(client, binding);
        RuntimeAgentSkillRepositoryFactory factory = new RuntimeAgentSkillRepositoryFactory(
                client, new ObjectMapper(), tempDir.toString(), 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 32);

        try (RuntimeAgentSkillRepositoryFactory.PreparedSkills prepared = factory.prepare(List.of(binding))) {
            assertTrue(prepared.enabled());
            assertEquals(1, prepared.repositories().size());
            assertEquals("demo-skill", prepared.repositories().get(0).getSkill("demo-skill").getName());
            assertTrue(prepared.alwaysInstructions().contains("Use the demo procedure"));
            assertTrue(prepared.alwaysInstructions().contains(binding.getSourceSha256()));
            assertTrue(prepared.alwaysInstructions().contains("&lt;/reachai-always-skill&gt;"));
        }
        try (RuntimeAgentSkillRepositoryFactory.PreparedSkills cached = factory.prepare(List.of(binding))) {
            assertTrue(cached.enabled());
        }

        verify(client, times(1)).getPackage(11L, 21L);
    }

    @Test
    void quarantinesTamperedCacheAndRehydratesFromPublishedArtifact() throws Exception {
        byte[] skillMd = ("---\nname: demo-skill\ndescription: Demo\n---\nOriginal\n")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = Map.of("SKILL.md", skillMd);
        byte[] archive = zip("demo-skill/", files);
        RuntimeAgentSkillBindingSnapshot binding = binding(archive, files, "MODEL_SELECTED");
        RuntimeAgentSkillCatalogClient client = mock(RuntimeAgentSkillCatalogClient.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-ReachAI-Skill-SHA256", binding.getSourceSha256());
        when(client.getPackage(11L, 21L))
                .thenReturn(new ResponseEntity<>(archive, headers, HttpStatus.OK));
        allowExecution(client, binding);
        RuntimeAgentSkillRepositoryFactory factory = new RuntimeAgentSkillRepositoryFactory(
                client, new ObjectMapper(), tempDir.toString(), 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 32);

        Path repositoryRoot;
        try (RuntimeAgentSkillRepositoryFactory.PreparedSkills ignored = factory.prepare(List.of(binding))) {
            repositoryRoot = tempDir.resolve("sha256")
                    .resolve(binding.getSourceSha256().substring(0, 2))
                    .resolve(binding.getSourceSha256())
                    .resolve("skills");
        }
        Files.writeString(repositoryRoot.resolve("demo-skill/SKILL.md"), "tampered");

        try (RuntimeAgentSkillRepositoryFactory.PreparedSkills repaired = factory.prepare(List.of(binding))) {
            assertEquals("demo-skill", repaired.repositories().get(0).getSkill("demo-skill").getName());
        }
        assertTrue(Files.exists(tempDir.resolve("quarantine")));
        verify(client, times(2)).getPackage(11L, 21L);
    }

    @Test
    void rejectsPackageWhoseBytesDoNotMatchPinnedDigest() throws Exception {
        byte[] skillMd = ("---\nname: demo-skill\ndescription: Demo\n---\nOriginal\n")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = Map.of("SKILL.md", skillMd);
        byte[] archive = zip("demo-skill/", files);
        RuntimeAgentSkillBindingSnapshot binding = binding(archive, files, "MODEL_SELECTED");
        byte[] otherArchive = zip("demo-skill/", Map.of("SKILL.md", "other".getBytes(StandardCharsets.UTF_8)));
        RuntimeAgentSkillCatalogClient client = mock(RuntimeAgentSkillCatalogClient.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-ReachAI-Skill-SHA256", binding.getSourceSha256());
        when(client.getPackage(11L, 21L))
                .thenReturn(new ResponseEntity<>(otherArchive, headers, HttpStatus.OK));
        allowExecution(client, binding);
        RuntimeAgentSkillRepositoryFactory factory = new RuntimeAgentSkillRepositoryFactory(
                client, new ObjectMapper(), tempDir.toString(), 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 32);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> factory.prepare(List.of(binding)));

        assertTrue(error.getMessage().contains("PACKAGE_DIGEST_MISMATCH"));
        assertFalse(Files.exists(tempDir.resolve("sha256")
                .resolve(binding.getSourceSha256().substring(0, 2))
                .resolve(binding.getSourceSha256())));
    }

    @Test
    void revocationBlocksExecutionEvenWhenArtifactIsAlreadyCached() throws Exception {
        byte[] skillMd = ("---\nname: demo-skill\ndescription: Demo\n---\nOriginal\n")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = Map.of("SKILL.md", skillMd);
        byte[] archive = zip("demo-skill/", files);
        RuntimeAgentSkillBindingSnapshot binding = binding(archive, files, "MODEL_SELECTED");
        RuntimeAgentSkillCatalogClient client = mock(RuntimeAgentSkillCatalogClient.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-ReachAI-Skill-SHA256", binding.getSourceSha256());
        when(client.getPackage(11L, 21L))
                .thenReturn(new ResponseEntity<>(archive, headers, HttpStatus.OK));
        when(client.resolveExecution(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(new RuntimeAgentSkillCatalogClient.ExecutionResolution(
                                11L, 21L, "PUBLISHED", binding.getSourceSha256(), true, "EXECUTABLE")),
                        List.of(new RuntimeAgentSkillCatalogClient.ExecutionResolution(
                                11L, 21L, "REVOKED", binding.getSourceSha256(), false, "STATUS_REVOKED")));
        RuntimeAgentSkillRepositoryFactory factory = new RuntimeAgentSkillRepositoryFactory(
                client, new ObjectMapper(), tempDir.toString(), 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 32);

        try (RuntimeAgentSkillRepositoryFactory.PreparedSkills prepared = factory.prepare(List.of(binding))) {
            assertTrue(prepared.enabled());
        }
        IllegalStateException revoked = assertThrows(IllegalStateException.class,
                () -> factory.prepare(List.of(binding)));

        assertTrue(revoked.getMessage().contains("STATUS_REVOKED"));
        verify(client, times(1)).getPackage(11L, 21L);
    }

    @Test
    void optionalRevokedSkillIsSafelySkippedAndReported() throws Exception {
        byte[] skillMd = ("---\nname: demo-skill\ndescription: Demo\n---\nOriginal\n")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = Map.of("SKILL.md", skillMd);
        byte[] archive = zip("demo-skill/", files);
        RuntimeAgentSkillBindingSnapshot binding = binding(archive, files, "MODEL_SELECTED").toBuilder()
                .required(false)
                .build();
        RuntimeAgentSkillCatalogClient client = mock(RuntimeAgentSkillCatalogClient.class);
        when(client.resolveExecution(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(new RuntimeAgentSkillCatalogClient.ExecutionResolution(
                        11L, 21L, "REVOKED", binding.getSourceSha256(), false, "STATUS_REVOKED")));
        RuntimeAgentSkillRepositoryFactory factory = new RuntimeAgentSkillRepositoryFactory(
                client, new ObjectMapper(), tempDir.toString(), 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 32);

        try (RuntimeAgentSkillRepositoryFactory.PreparedSkills prepared = factory.prepare(List.of(binding))) {
            assertFalse(prepared.enabled());
            assertEquals(1, prepared.skippedSkills().size());
            assertEquals("STATUS_REVOKED", prepared.skippedSkills().get(0).reason());
        }
        verify(client, times(0)).getPackage(11L, 21L);
    }

    @Test
    void missingOptionalSkillDoesNotPoisonValidRequiredSkillInSameBatch() throws Exception {
        byte[] skillMd = ("---\nname: demo-skill\ndescription: Demo\n---\nOriginal\n")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = Map.of("SKILL.md", skillMd);
        byte[] archive = zip("demo-skill/", files);
        RuntimeAgentSkillBindingSnapshot required = binding(archive, files, "MODEL_SELECTED");
        RuntimeAgentSkillBindingSnapshot missingOptional = binding(archive, files, "MODEL_SELECTED").toBuilder()
                .skillId(12L)
                .skillVersionId(22L)
                .standardName("missing-skill")
                .required(false)
                .build();

        RuntimeAgentSkillCatalogClient client = mock(RuntimeAgentSkillCatalogClient.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-ReachAI-Skill-SHA256", required.getSourceSha256());
        when(client.getPackage(11L, 21L))
                .thenReturn(new ResponseEntity<>(archive, headers, HttpStatus.OK));
        when(client.resolveExecution(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(
                        new RuntimeAgentSkillCatalogClient.ExecutionResolution(
                                12L, 22L, "MISSING", null, false, "NOT_FOUND"),
                        new RuntimeAgentSkillCatalogClient.ExecutionResolution(
                                11L, 21L, "PUBLISHED", required.getSourceSha256(), true, "EXECUTABLE")));
        RuntimeAgentSkillRepositoryFactory factory = new RuntimeAgentSkillRepositoryFactory(
                client, new ObjectMapper(), tempDir.toString(), 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 32);

        try (RuntimeAgentSkillRepositoryFactory.PreparedSkills prepared = factory.prepare(
                List.of(missingOptional, required))) {
            assertTrue(prepared.enabled());
            assertEquals(1, prepared.activeBindings().size());
            assertEquals(21L, prepared.activeBindings().get(0).getSkillVersionId());
            assertEquals(1, prepared.skippedSkills().size());
            assertEquals(22L, prepared.skippedSkills().get(0).skillVersionId());
            assertEquals("NOT_FOUND", prepared.skippedSkills().get(0).reason());
        }
        verify(client, times(1)).getPackage(11L, 21L);
        verify(client, times(0)).getPackage(12L, 22L);
    }

    @Test
    void malformedOptionalBindingIsSkippedBeforeStatusBatchAndValidRequiredSkillStillLoads() throws Exception {
        byte[] skillMd = ("---\nname: demo-skill\ndescription: Demo\n---\nOriginal\n")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = Map.of("SKILL.md", skillMd);
        byte[] archive = zip("demo-skill/", files);
        RuntimeAgentSkillBindingSnapshot required = binding(archive, files, "MODEL_SELECTED");
        RuntimeAgentSkillBindingSnapshot malformedOptional = binding(archive, files, "MODEL_SELECTED").toBuilder()
                .skillId(12L)
                .skillVersionId(22L)
                .standardName("malformed-skill")
                .sourceSha256(null)
                .required(false)
                .build();

        RuntimeAgentSkillCatalogClient client = mock(RuntimeAgentSkillCatalogClient.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-ReachAI-Skill-SHA256", required.getSourceSha256());
        when(client.getPackage(11L, 21L))
                .thenReturn(new ResponseEntity<>(archive, headers, HttpStatus.OK));
        allowExecution(client, required);
        RuntimeAgentSkillRepositoryFactory factory = new RuntimeAgentSkillRepositoryFactory(
                client, new ObjectMapper(), tempDir.toString(), 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 32);

        try (RuntimeAgentSkillRepositoryFactory.PreparedSkills prepared = factory.prepare(
                List.of(malformedOptional, required))) {
            assertTrue(prepared.enabled());
            assertEquals(1, prepared.activeBindings().size());
            assertEquals(1, prepared.skippedSkills().size());
            assertEquals("BINDING_IDENTITY_INVALID", prepared.skippedSkills().get(0).reason());
        }
        verify(client, times(1)).resolveExecution(org.mockito.ArgumentMatchers.anyList());
        verify(client, times(1)).getPackage(11L, 21L);
        verify(client, times(0)).getPackage(12L, 22L);
    }

    @Test
    void executableResolutionStillRequiresAuthoritativeDigest() throws Exception {
        byte[] skillMd = ("---\nname: demo-skill\ndescription: Demo\n---\nOriginal\n")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = Map.of("SKILL.md", skillMd);
        RuntimeAgentSkillBindingSnapshot binding = binding(
                zip("demo-skill/", files), files, "MODEL_SELECTED");
        RuntimeAgentSkillCatalogClient client = mock(RuntimeAgentSkillCatalogClient.class);
        when(client.resolveExecution(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(new RuntimeAgentSkillCatalogClient.ExecutionResolution(
                        11L, 21L, "PUBLISHED", null, true, "EXECUTABLE")));
        RuntimeAgentSkillRepositoryFactory factory = new RuntimeAgentSkillRepositoryFactory(
                client, new ObjectMapper(), tempDir.toString(), 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 32);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> factory.prepare(List.of(binding)));

        assertTrue(failure.getMessage().contains("DIGEST_MISMATCH"));
        verify(client, times(0)).getPackage(11L, 21L);
    }

    @Test
    void projectScopeMismatchFailsBeforeControlOrCacheAccess() throws Exception {
        byte[] skillMd = ("---\nname: demo-skill\ndescription: Demo\n---\nOriginal\n")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = Map.of("SKILL.md", skillMd);
        RuntimeAgentSkillBindingSnapshot binding = binding(
                zip("demo-skill/", files), files, "MODEL_SELECTED").toBuilder()
                .visibility("PROJECT").projectCode("finance-core").build();
        RuntimeAgentSkillCatalogClient client = mock(RuntimeAgentSkillCatalogClient.class);
        RuntimeAgentSkillRepositoryFactory factory = new RuntimeAgentSkillRepositoryFactory(
                client, new ObjectMapper(), tempDir.toString(), 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 32);

        IllegalStateException mismatch = assertThrows(IllegalStateException.class,
                () -> factory.prepare(List.of(binding), "hr-core"));

        assertTrue(mismatch.getMessage().contains("no longer matches"));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsPortablePathCollisionFromAStoredBindingSnapshot() throws Exception {
        byte[] skillMd = "---\nname: demo-skill\ndescription: Demo\n---\n"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", skillMd);
        files.put("references/Guide.md", "one".getBytes(StandardCharsets.UTF_8));
        files.put("references/guide.md", "two".getBytes(StandardCharsets.UTF_8));
        byte[] archive = zip("demo-skill/", files);
        RuntimeAgentSkillBindingSnapshot binding = binding(archive, files, "MODEL_SELECTED");
        RuntimeAgentSkillCatalogClient client = mock(RuntimeAgentSkillCatalogClient.class);
        allowExecution(client, binding);
        RuntimeAgentSkillRepositoryFactory factory = new RuntimeAgentSkillRepositoryFactory(
                client, new ObjectMapper(), tempDir.toString(), 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 32);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> factory.prepare(List.of(binding)));

        assertTrue(failure.getMessage().contains("PACKAGE_MANIFEST_INVALID"));
        verify(client, times(0)).getPackage(11L, 21L);
    }

    @Test
    void rejectsFileDirectoryConflictFromAStoredBindingSnapshot() throws Exception {
        byte[] skillMd = "---\nname: demo-skill\ndescription: Demo\n---\n"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", skillMd);
        files.put("references", "not a directory".getBytes(StandardCharsets.UTF_8));
        files.put("references/guide.md", "guide".getBytes(StandardCharsets.UTF_8));
        byte[] archive = zip("demo-skill/", files);
        RuntimeAgentSkillBindingSnapshot binding = binding(archive, files, "MODEL_SELECTED");
        RuntimeAgentSkillCatalogClient client = mock(RuntimeAgentSkillCatalogClient.class);
        allowExecution(client, binding);
        RuntimeAgentSkillRepositoryFactory factory = new RuntimeAgentSkillRepositoryFactory(
                client, new ObjectMapper(), tempDir.toString(), 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 32);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> factory.prepare(List.of(binding)));

        assertTrue(failure.getMessage().contains("PACKAGE_MANIFEST_INVALID"));
        verify(client, times(0)).getPackage(11L, 21L);
    }

    @Test
    void rejectsManifestWhoseDeclaredTreeDigestDoesNotMatchItsFiles() throws Exception {
        byte[] skillMd = "---\nname: demo-skill\ndescription: Demo\n---\n"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = Map.of("SKILL.md", skillMd);
        byte[] archive = zip("demo-skill/", files);
        RuntimeAgentSkillBindingSnapshot sourceBinding = binding(archive, files, "MODEL_SELECTED").toBuilder()
                .contentTreeSha256("f".repeat(64))
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = new ObjectMapper().readValue(
                sourceBinding.getPackageManifestJson(), Map.class);
        manifest.put("contentTreeSha256", sourceBinding.getContentTreeSha256());
        RuntimeAgentSkillBindingSnapshot binding = sourceBinding.toBuilder()
                .packageManifestJson(new ObjectMapper().writeValueAsString(manifest)).build();
        RuntimeAgentSkillCatalogClient client = mock(RuntimeAgentSkillCatalogClient.class);
        allowExecution(client, binding);
        RuntimeAgentSkillRepositoryFactory factory = new RuntimeAgentSkillRepositoryFactory(
                client, new ObjectMapper(), tempDir.toString(), 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 32);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> factory.prepare(List.of(binding)));

        assertTrue(failure.getMessage().contains("PACKAGE_MANIFEST_INVALID"));
        verify(client, times(0)).getPackage(11L, 21L);
    }

    private RuntimeAgentSkillBindingSnapshot binding(byte[] archive,
                                                    Map<String, byte[]> files,
                                                    String activationMode) throws Exception {
        String sourceSha = sha256(archive);
        List<Map<String, Object>> manifestFiles = files.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("path", entry.getKey());
                    value.put("size", entry.getValue().length);
                    value.put("sha256", sha256(entry.getValue()));
                    value.put("kind", "INSTRUCTION");
                    return value;
                })
                .toList();
        String treeSha = treeSha256(files);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "reachai.agent-skill-package-manifest.v1");
        manifest.put("name", "demo-skill");
        manifest.put("sourceRoot", "demo-skill/");
        manifest.put("sourceSha256", sourceSha);
        manifest.put("contentTreeSha256", treeSha);
        manifest.put("files", manifestFiles);

        RuntimeAgentSkillBindingSnapshot binding = RuntimeAgentSkillBindingSnapshot.builder()
                .id(31L)
                .agentId("agent-1")
                .agentConfigVersionId(41L)
                .skillId(11L)
                .skillVersionId(21L)
                .publisher("community")
                .standardName("demo-skill")
                .displayName("Demo Skill")
                .visibility("PUBLIC")
                .version("1.2.3")
                .sourceSha256(sourceSha)
                .contentTreeSha256(treeSha)
                .sourceRoot("demo-skill/")
                .packageManifestJson(new ObjectMapper().writeValueAsString(manifest))
                .hasScripts(false)
                .activationMode(activationMode)
                .scriptPolicy("DENY")
                .required(true)
                .enabled(true)
                .build();
        return binding;
    }

    private byte[] zip(String root, Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(root + file.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private void allowExecution(RuntimeAgentSkillCatalogClient client,
                                RuntimeAgentSkillBindingSnapshot binding) {
        when(client.resolveExecution(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(new RuntimeAgentSkillCatalogClient.ExecutionResolution(
                        binding.getSkillId(), binding.getSkillVersionId(), "PUBLISHED",
                        binding.getSourceSha256(), true, "EXECUTABLE")));
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String treeSha256(Map<String, byte[]> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                byte[] content = entry.getValue();
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(sha256(content).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) 0);
                digest.update(Long.toString(content.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) '\n');
            });
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
