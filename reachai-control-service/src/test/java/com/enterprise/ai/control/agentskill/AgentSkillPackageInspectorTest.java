package com.enterprise.ai.control.agentskill;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSkillPackageInspectorTest {

    private final AgentSkillPackageInspector inspector = new AgentSkillPackageInspector(
            1024 * 1024, 2 * 1024 * 1024, 1024 * 1024, 100);

    @Test
    void validatesPortablePackageAndBuildsStableManifest() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("contract-review/SKILL.md", utf8("""
                ---
                name: contract-review
                description: Review contracts using the approved checklist.
                license: Apache-2.0
                compatibility: Requires access to the contract document.
                metadata:
                  version: "2.1.0"
                allowed-tools: Read
                ---
                # Contract review
                Follow the checklist.
                """));
        files.put("contract-review/references/checklist.md", utf8("# Checklist"));

        AgentSkillPackageInspector.PackageInspection result = inspector.inspect(zip(files));

        assertEquals("contract-review", result.name());
        assertEquals("2.1.0", result.declaredVersion());
        assertEquals(2, result.files().size());
        assertEquals(64, result.sourceSha256().length());
        assertEquals(64, result.contentTreeSha256().length());
        assertFalse(result.hasScripts());
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("does not grant")));
        assertEquals("COMPATIBLE", result.compatibilityReport().get("format"));
        assertEquals("reachai.agent-skill-compatibility.v3",
                result.compatibilityReport().get("schema"));
        assertEquals("NONE_DECLARED", result.compatibilityReport().get("dependency"));
        assertEquals("NOT_RUN", result.compatibilityReport().get("hostE2e"));
        assertTrue(result.compatibilityReport().get("hosts") instanceof Map<?, ?>);
        Map<?, ?> hosts = (Map<?, ?>) result.compatibilityReport().get("hosts");
        assertEquals("COMPATIBLE", ((Map<?, ?>) hosts.get("AGENTSCOPE")).get("format"));
        assertEquals("IMPLEMENTED", ((Map<?, ?>) hosts.get("AGENTSCOPE")).get("adapter"));
        assertEquals("UNIT_VERIFIED", ((Map<?, ?>) hosts.get("AGENTSCOPE")).get("evidence"));
        assertEquals("COMPATIBLE", ((Map<?, ?>) hosts.get("CODEX")).get("format"));
        assertEquals("NOT_IMPLEMENTED", ((Map<?, ?>) hosts.get("CODEX")).get("adapter"));
        assertEquals("FORMAT_VERIFIED", ((Map<?, ?>) hosts.get("CODEX")).get("evidence"));
        Map<?, ?> absentCodexMetadata = (Map<?, ?>) ((Map<?, ?>) hosts.get("CODEX")).get("hostMetadata");
        assertEquals("ABSENT", absentCodexMetadata.get("status"));
        assertEquals(null, absentCodexMetadata.get("allowImplicitInvocation"));
        assertEquals("COMPATIBLE", ((Map<?, ?>) hosts.get("OPENCODE")).get("format"));
        assertEquals("NOT_IMPLEMENTED", ((Map<?, ?>) hosts.get("OPENCODE")).get("adapter"));
        assertEquals("FORMAT_VERIFIED", ((Map<?, ?>) hosts.get("OPENCODE")).get("evidence"));
    }

    @Test
    void acceptsRootLayoutAndPreservesHostMetadataAndUnknownFrontmatter() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", utf8("""
                ---
                name: portable-root
                description: Root-layout package with host metadata.
                allowed-tools: Read Search
                x-market-channel: community
                ---
                Follow the portable procedure.
                """));
        files.put("agents/openai.yaml", utf8("""
                interface:
                  display_name: Portable Root
                policy:
                  allow_implicit_invocation: false
                dependencies:
                  tools:
                    - type: mcp
                      value: openaiDeveloperDocs
                      description: Official documentation
                      transport: streamable_http
                      url: https://developers.openai.com/mcp
                      minimum_version: "1"
                """));
        files.put("references/guide.md", utf8("# Guide"));

        AgentSkillPackageInspector.PackageInspection result = inspector.inspect(zip(files));

        assertEquals("portable-root", result.name());
        assertEquals("", result.sourceRoot());
        assertTrue(result.extensionFields().contains("x-market-channel"));
        assertEquals("community", result.frontmatter().get("x-market-channel"));
        assertTrue(result.files().stream().anyMatch(file -> "agents/openai.yaml".equals(file.path())
                && file.kind() == AgentSkillPackageInspector.FileKind.HOST_METADATA));
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("does not grant")));
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("does not grant Tool permissions")));
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("disables implicit Codex invocation")));
        assertEquals(1, result.riskReport().get("codexToolDependenciesDeclared"));
        assertEquals("DECLARED_NOT_RESOLVED", result.compatibilityReport().get("dependency"));
        Map<?, ?> hosts = (Map<?, ?>) result.compatibilityReport().get("hosts");
        Map<?, ?> codex = (Map<?, ?>) hosts.get("CODEX");
        Map<?, ?> hostMetadata = (Map<?, ?>) codex.get("hostMetadata");
        assertEquals("VALID", hostMetadata.get("status"));
        assertEquals(false, hostMetadata.get("allowImplicitInvocation"));
        assertEquals(1, hostMetadata.get("toolDependencyCount"));
        assertTrue(hostMetadata.get("toolDependencies") instanceof java.util.List<?>);
        Map<?, ?> dependency = (Map<?, ?>) ((java.util.List<?>) hostMetadata.get("toolDependencies")).get(0);
        assertEquals("mcp", dependency.get("type"));
        assertEquals(java.util.List.of("minimum_version"), dependency.get("extensionFields"));
    }

    @Test
    void keepsCoreSkillImportableButMarksMalformedCodexMetadataIncompatible() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("portable/SKILL.md", utf8("""
                ---
                name: portable
                description: Portable core Skill with broken optional host metadata.
                ---
                Follow the portable procedure.
                """));
        files.put("portable/agents/openai.yaml", utf8("""
                policy:
                  allow_implicit_invocation: [false
                """));

        AgentSkillPackageInspector.PackageInspection result = inspector.inspect(zip(files));

        assertEquals("COMPATIBLE", result.compatibilityReport().get("format"));
        assertEquals("HOST_METADATA_INVALID", result.compatibilityReport().get("dependency"));
        Map<?, ?> hosts = (Map<?, ?>) result.compatibilityReport().get("hosts");
        Map<?, ?> codex = (Map<?, ?>) hosts.get("CODEX");
        assertEquals("INCOMPATIBLE", codex.get("format"));
        assertEquals("HOST_METADATA_INVALID", codex.get("evidence"));
        Map<?, ?> metadata = (Map<?, ?>) codex.get("hostMetadata");
        assertEquals("INVALID", metadata.get("status"));
        assertEquals("INVALID_YAML", metadata.get("errorCode"));
        assertEquals(null, metadata.get("allowImplicitInvocation"));
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("not valid Codex host metadata")));
    }

    @Test
    void marksScriptsForSeparateRuntimeApproval() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("data-check/SKILL.md", utf8("""
                ---
                name: data-check
                description: Validate a data file with deterministic checks.
                ---
                Run scripts/check.py only when script execution is approved.
                """));
        files.put("data-check/scripts/check.py", utf8("print('ok')"));

        AgentSkillPackageInspector.PackageInspection result = inspector.inspect(zip(files));

        assertTrue(result.hasScripts());
        assertEquals("SCRIPT_REVIEW_REQUIRED", result.riskReport().get("level"));
        assertEquals("REVIEW_REQUIRED", result.compatibilityReport().get("policy"));
    }

    @Test
    void previewsReviewedTextButDoesNotRenderBinaryAssets() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("reviewable/SKILL.md", utf8(
                "---\nname: reviewable\ndescription: Reviewable.\n---\nDo the work.\n"));
        files.put("reviewable/assets/pixel.bin", new byte[] {0, 1, 2, 3});
        byte[] archive = zip(files);

        AgentSkillPackageInspector.PackageFilePreview instructions =
                inspector.previewFile(archive, "SKILL.md");
        AgentSkillPackageInspector.PackageFilePreview binary =
                inspector.previewFile(archive, "assets/pixel.bin");

        assertTrue(instructions.previewable());
        assertTrue(instructions.content().contains("Do the work"));
        assertFalse(binary.previewable());
        assertEquals(null, binary.content());
    }

    @Test
    void rejectsPathTraversalBeforeAnyExtraction() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("safe/SKILL.md", utf8("""
                ---
                name: safe
                description: Safe example.
                ---
                """));
        files.put("safe/../escape.txt", utf8("escape"));

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(zip(files)));

        assertEquals("SKILL_PACKAGE_INVALID", failure.code());
        assertTrue(failure.getMessage().contains("path traversal"));
    }

    @Test
    void rejectsMultipleSkillsInOnePackage() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("one/SKILL.md", utf8("---\nname: one\ndescription: One.\n---\n"));
        files.put("two/SKILL.md", utf8("---\nname: two\ndescription: Two.\n---\n"));

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(zip(files)));

        assertTrue(failure.getMessage().contains("exactly one SKILL.md"));
        assertTrue(failure.getMessage().contains("bundle discovery"));
    }

    @Test
    void discoversRepositoryBundleAndSelectsOneDeterministicCanonicalPackage() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repository-main/README.md", utf8("# Repository"));
        files.put("repository-main/LICENSE", utf8("Apache-2.0"));
        files.put("repository-main/skills/one/SKILL.md", utf8("""
                ---
                name: one
                description: First portable Skill.
                metadata:
                  version: "1.2.3"
                ---
                Use the first procedure.
                """));
        files.put("repository-main/skills/one/references/guide.md", utf8("# Guide"));
        files.put("repository-main/skills/one/scripts/check.py", utf8("print('ok')"));
        files.put("repository-main/skills/one/agents/openai.yaml", utf8("""
                interface:
                  display_name: One
                """));
        files.put("repository-main/skills/two/SKILL.md", utf8("""
                ---
                name: two
                description: Second portable Skill.
                ---
                Use the second procedure.
                """));
        files.put("repository-main/skills/two/assets/template.txt", utf8("template"));
        byte[] archive = zip(files);

        AgentSkillPackageInspector.BundleDiscovery discovery = inspector.discoverBundle(archive);

        assertEquals("reachai.agent-skill-bundle-discovery.v1", discovery.schema());
        assertEquals(AgentSkillPackageInspector.sha256(archive), discovery.bundleSourceSha256());
        assertEquals(files.size(), discovery.archiveFileCount());
        assertEquals(2, discovery.candidateCount());
        assertTrue(discovery.multiSkill());
        AgentSkillPackageInspector.BundleCandidate one = discovery.candidates().stream()
                .filter(candidate -> "one".equals(candidate.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("repository-main/skills/one", one.sourceRoot());
        assertTrue(one.selectable());
        assertTrue(one.hasScripts());
        assertEquals("1.2.3", one.declaredVersion());
        assertEquals(4, one.fileCount());

        AgentSkillPackageInspector.SelectedPackage selected =
                inspector.selectFromBundle(archive, one.sourceRoot());
        AgentSkillPackageInspector.SelectedPackage selectedAgain =
                inspector.selectFromBundle(archive, one.sourceRoot());

        assertEquals(discovery.bundleSourceSha256(), selected.bundleSourceSha256());
        assertEquals("one", selected.inspection().name());
        assertEquals("", selected.inspection().sourceRoot());
        assertEquals(one.selectedSourceSha256(), selected.inspection().sourceSha256());
        assertArrayEquals(selected.archive(), selectedAgain.archive());
        assertEquals(List.of(
                        "SKILL.md",
                        "agents/openai.yaml",
                        "references/guide.md",
                        "scripts/check.py"),
                unzip(selected.archive()).keySet().stream().sorted().toList());
        assertFalse(unzip(selected.archive()).keySet().stream()
                .anyMatch(path -> path.contains("two") || path.contains("README")));
    }

    @Test
    void canonicalSelectionDoesNotDependOnRepositoryZipEntryOrder() throws IOException {
        Map<String, byte[]> forward = new LinkedHashMap<>();
        forward.put("repository/README.md", utf8("# Repository"));
        forward.put("repository/skills/stable/SKILL.md", utf8("""
                ---
                name: stable
                description: Stable canonical package.
                ---
                Follow the stable procedure.
                """));
        forward.put("repository/skills/stable/references/guide.md", utf8("guide"));
        forward.put("repository/skills/stable/assets/template.txt", utf8("template"));
        Map<String, byte[]> reverse = new LinkedHashMap<>();
        forward.entrySet().stream()
                .sorted(Map.Entry.<String, byte[]>comparingByKey().reversed())
                .forEach(entry -> reverse.put(entry.getKey(), entry.getValue()));
        byte[] forwardBundle = zip(forward);
        byte[] reverseBundle = zip(reverse);

        AgentSkillPackageInspector.SelectedPackage selectedForward = inspector.selectFromBundle(
                forwardBundle, "repository/skills/stable");
        AgentSkillPackageInspector.SelectedPackage selectedReverse = inspector.selectFromBundle(
                reverseBundle, "repository/skills/stable");

        assertNotEquals(AgentSkillPackageInspector.sha256(forwardBundle),
                AgentSkillPackageInspector.sha256(reverseBundle));
        assertArrayEquals(selectedForward.archive(), selectedReverse.archive());
        assertEquals(selectedForward.inspection().sourceSha256(),
                selectedReverse.inspection().sourceSha256());
        assertEquals(selectedForward.inspection().contentTreeSha256(),
                selectedReverse.inspection().contentTreeSha256());
    }

    @Test
    void reportsInvalidCandidateWithoutHidingValidSibling() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo/skills/good/SKILL.md",
                utf8("---\nname: good\ndescription: Valid sibling.\n---\n"));
        files.put("repo/skills/bad/SKILL.md",
                utf8("---\nname: different\ndescription: Invalid sibling.\n---\n"));
        byte[] archive = zip(files);

        AgentSkillPackageInspector.BundleDiscovery discovery = inspector.discoverBundle(archive);

        AgentSkillPackageInspector.BundleCandidate good = discovery.candidates().stream()
                .filter(candidate -> "repo/skills/good".equals(candidate.sourceRoot()))
                .findFirst()
                .orElseThrow();
        AgentSkillPackageInspector.BundleCandidate bad = discovery.candidates().stream()
                .filter(candidate -> "repo/skills/bad".equals(candidate.sourceRoot()))
                .findFirst()
                .orElseThrow();
        assertTrue(good.selectable());
        assertFalse(bad.selectable());
        assertEquals("SKILL_PACKAGE_INVALID", bad.errorCode());
        assertTrue(bad.errorMessage().contains("match its package directory"));
        assertThrows(AgentSkillException.class,
                () -> inspector.selectFromBundle(archive, bad.sourceRoot()));
    }

    @Test
    void rejectsContainerCandidateButAllowsNestedLeafSkill() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md",
                utf8("---\nname: bundle-root\ndescription: Container descriptor.\n---\n"));
        files.put(".opencode/skills/leaf/SKILL.md",
                utf8("---\nname: leaf\ndescription: Leaf Skill.\n---\n"));
        byte[] archive = zip(files);

        AgentSkillPackageInspector.BundleDiscovery discovery = inspector.discoverBundle(archive);

        AgentSkillPackageInspector.BundleCandidate root = discovery.candidates().stream()
                .filter(candidate -> candidate.sourceRoot().isEmpty())
                .findFirst()
                .orElseThrow();
        AgentSkillPackageInspector.BundleCandidate leaf = discovery.candidates().stream()
                .filter(candidate -> ".opencode/skills/leaf".equals(candidate.sourceRoot()))
                .findFirst()
                .orElseThrow();
        assertFalse(root.selectable());
        assertEquals("NESTED_SKILL_DESCRIPTOR", root.errorCode());
        assertTrue(leaf.selectable());
        assertEquals("leaf", inspector.selectFromBundle(archive, leaf.sourceRoot()).inspection().name());
        assertThrows(AgentSkillException.class,
                () -> inspector.selectFromBundle(archive, "missing/skill"));
    }

    @Test
    void rejectsCaseFoldedPathCollisionsAcrossSupportedHosts() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("portable/SKILL.md", utf8("---\nname: portable\ndescription: Portable.\n---\n"));
        files.put("portable/references/Guide.md", utf8("one"));
        files.put("portable/references/guide.md", utf8("two"));

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(zip(files)));

        assertTrue(failure.getMessage().contains("collide on a portable filesystem"));
    }

    @Test
    void rejectsFileThatIsAlsoAnAncestorDirectoryOfAnotherEntry() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("portable/SKILL.md", utf8("---\nname: portable\ndescription: Portable.\n---\n"));
        files.put("portable/references", utf8("not a directory"));
        files.put("portable/references/guide.md", utf8("guide"));

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(zip(files)));

        assertTrue(failure.getMessage().contains("file/directory path conflict"));
    }

    @Test
    void rejectsWindowsReservedPackagePaths() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("portable/SKILL.md", utf8("---\nname: portable\ndescription: Portable.\n---\n"));
        files.put("portable/assets/CON.txt", utf8("reserved"));

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(zip(files)));

        assertTrue(failure.getMessage().contains("reserved filesystem path"));
    }

    @Test
    void rejectsDirectoryAndFrontmatterNameMismatch() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("actual/SKILL.md", utf8("---\nname: different\ndescription: Example.\n---\n"));

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(zip(files)));

        assertTrue(failure.getMessage().contains("match its package directory"));
    }

    @Test
    void rejectsNonStringLicenseFrontmatter() throws IOException {
        byte[] archive = zip(Map.of("typed/SKILL.md", utf8("""
                ---
                name: typed
                description: Typed metadata example.
                license:
                  - Apache-2.0
                ---
                Follow the instructions.
                """)));

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(archive));

        assertTrue(failure.getMessage().contains("license must be a string"));
    }

    @Test
    void rejectsNonStringCompatibilityFrontmatter() throws IOException {
        byte[] archive = zip(Map.of("typed/SKILL.md", utf8("""
                ---
                name: typed
                description: Typed metadata example.
                compatibility:
                  runtime: codex
                ---
                Follow the instructions.
                """)));

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(archive));

        assertTrue(failure.getMessage().contains("compatibility must be a string"));
    }

    @Test
    void rejectsNonStringAllowedToolsFrontmatter() throws IOException {
        byte[] archive = zip(Map.of("typed/SKILL.md", utf8("""
                ---
                name: typed
                description: Typed metadata example.
                allowed-tools:
                  - Read
                  - Search
                ---
                Follow the instructions.
                """)));

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(archive));

        assertTrue(failure.getMessage().contains("allowed-tools must be a string"));
    }

    @Test
    void rejectsMetadataValuesThatAreNotStrings() throws IOException {
        byte[] archive = zip(Map.of("typed/SKILL.md", utf8("""
                ---
                name: typed
                description: Typed metadata example.
                metadata:
                  version: "1.0.0"
                  attempts: 2
                ---
                Follow the instructions.
                """)));

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(archive));

        assertTrue(failure.getMessage().contains("metadata must be a string-to-string map"));
    }

    @Test
    void rejectsDuplicateYamlKeysInsteadOfSilentlyChoosingOne() throws IOException {
        byte[] archive = zip(Map.of("duplicate/SKILL.md", utf8("""
                ---
                name: duplicate
                name: replaced
                description: Duplicate key example.
                ---
                Follow the instructions.
                """)));

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(archive));

        assertTrue(failure.getMessage().contains("invalid YAML"));
    }

    @Test
    void rejectsMalformedUtf8InPrimaryInstructions() throws IOException {
        byte[] prefix = utf8("---\nname: invalid-utf8\ndescription: Invalid UTF-8 example.\n---\n");
        byte[] malformed = java.util.Arrays.copyOf(prefix, prefix.length + 2);
        malformed[prefix.length] = (byte) 0xc3;
        malformed[prefix.length + 1] = (byte) 0x28;

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(zip(Map.of("invalid-utf8/SKILL.md", malformed))));

        assertTrue(failure.getMessage().contains("valid UTF-8"));
    }

    @Test
    void rejectsOversizedPrimaryInstructionsBeforeTheyCanInflateModelContext() throws IOException {
        String markdown = "---\nname: huge-skill\ndescription: Oversized example.\n---\n"
                + "x".repeat(260 * 1024);

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> inspector.inspect(zip(Map.of("huge-skill/SKILL.md", utf8(markdown)))));

        assertTrue(failure.getMessage().contains("256 KiB"));
    }

    @Test
    void validatesBothBundledReachAiSkillsAgainstTheSameImportContract() {
        BuiltinAgentSkillSource source = new BuiltinAgentSkillSource();

        for (BuiltinAgentSkillSource.Descriptor descriptor : source.descriptors()) {
            AgentSkillPackageInspector.PackageInspection result = inspector.inspect(
                    source.packageBytes(descriptor.name()));
            assertEquals(descriptor.name(), result.name());
            assertFalse(result.files().isEmpty());
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(
                new java.io.ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    files.put(entry.getName(), input.readAllBytes());
                }
                input.closeEntry();
            }
        }
        return files;
    }
}
