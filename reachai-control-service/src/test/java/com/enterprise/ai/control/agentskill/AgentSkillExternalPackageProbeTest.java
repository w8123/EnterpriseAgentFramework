package com.enterprise.ai.control.agentskill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in acceptance probe for a real Skill directory downloaded or installed outside this repository.
 *
 * <p>The regular build does not depend on a developer-specific directory. When
 * {@code -Dreachai.external-skill-dir=<path>} is supplied, the probe packages the directory exactly as a
 * root-layout market ZIP and passes it through the production inspector. Local runtime caches are excluded,
 * but all source files and unknown extensions are retained. No script is ever launched.</p>
 */
class AgentSkillExternalPackageProbeTest {

    private static final String EXTERNAL_SKILL_DIR = "reachai.external-skill-dir";
    private static final String REQUIRED_LAYOUT = "reachai.external-skill-required-layout";
    private static final String REQUIRE_UNKNOWN_FILES = "reachai.external-skill-require-unknown-files";
    private static final String REQUIRE_CODEX_DEPENDENCIES = "reachai.external-skill-require-codex-dependencies";
    private static final String EXPECTED_IMPLICIT_INVOCATION = "reachai.external-skill-expected-implicit-invocation";
    private static final Set<String> LOCAL_ONLY_DIRECTORIES = Set.of(
            ".git", ".playwright-cli", "node_modules", "__pycache__");

    private final AgentSkillPackageInspector inspector = new AgentSkillPackageInspector(
            20 * 1024 * 1024,
            100 * 1024 * 1024,
            20 * 1024 * 1024,
            1024);

    @Test
    @EnabledIfSystemProperty(named = EXTERNAL_SKILL_DIR, matches = ".+")
    void validatesARealExternalSkillWithTheProductionImportContract() throws IOException {
        Path source = Path.of(System.getProperty(EXTERNAL_SKILL_DIR)).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(source), () -> "External Skill directory does not exist: " + source);
        assertTrue(Files.isRegularFile(source.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS),
                () -> "External Skill directory has no SKILL.md: " + source);

        Map<String, byte[]> sourceFiles = readSourceFiles(source);
        byte[] firstArchive = zip(sourceFiles);
        byte[] secondArchive = zip(readSourceFiles(source));
        assertArrayEquals(firstArchive, secondArchive,
                "The external directory must materialize to a deterministic ZIP");

        AgentSkillPackageInspector.PackageInspection first = inspector.inspect(firstArchive);
        AgentSkillPackageInspector.PackageInspection second = inspector.inspect(secondArchive);

        assertEquals("", first.sourceRoot(), "The probe must exercise the standard root-layout ZIP");
        assertEquals(first.sourceSha256(), second.sourceSha256());
        assertEquals(first.contentTreeSha256(), second.contentTreeSha256());
        assertEquals(sourceFiles.size(), first.files().size(),
                "Every external source file must survive package inspection");
        assertTrue(first.files().stream().anyMatch(file -> "SKILL.md".equals(file.path())));
        assertRequiredLayout(first.files());

        if (Boolean.getBoolean(REQUIRE_UNKNOWN_FILES)) {
            assertTrue(first.files().stream().anyMatch(file ->
                            file.kind() == AgentSkillPackageInspector.FileKind.OTHER),
                    "Unknown market extension files must be preserved as OTHER");
        }

        boolean hasScripts = first.files().stream().anyMatch(file ->
                file.path().startsWith("scripts/"));
        if (hasScripts) {
            assertTrue(first.hasScripts());
            assertEquals("SCRIPT_REVIEW_REQUIRED", first.riskReport().get("level"));
            assertEquals("REVIEW_REQUIRED", first.compatibilityReport().get("policy"));
        } else {
            assertFalse(first.hasScripts());
        }

        if (sourceFiles.containsKey("agents/openai.yaml")) {
            Map<?, ?> hosts = requiredMap(first.compatibilityReport().get("hosts"), "hosts");
            Map<?, ?> codex = requiredMap(hosts.get("CODEX"), "hosts.CODEX");
            Map<?, ?> metadata = requiredMap(codex.get("hostMetadata"), "hosts.CODEX.hostMetadata");
            assertEquals("VALID", metadata.get("status"));

            String expectedImplicit = System.getProperty(EXPECTED_IMPLICIT_INVOCATION, "").trim();
            if (!expectedImplicit.isEmpty()) {
                assertEquals(Boolean.parseBoolean(expectedImplicit), metadata.get("allowImplicitInvocation"));
            }
            if (Boolean.getBoolean(REQUIRE_CODEX_DEPENDENCIES)) {
                assertEquals("DECLARED_NOT_RESOLVED", first.compatibilityReport().get("dependency"));
                assertTrue(((Number) metadata.get("toolDependencyCount")).intValue() > 0);
                assertTrue(metadata.get("toolDependencies") instanceof List<?>);
            }
        }
    }

    private static Map<String, byte[]> readSourceFiles(Path source) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (var paths = Files.walk(source)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !isLocalOnlyFile(source, path))
                    .forEach(path -> {
                        String relative = source.relativize(path).toString().replace('\\', '/');
                        try {
                            files.put(relative, Files.readAllBytes(path));
                        } catch (IOException exception) {
                            throw new UncheckedIOException("Cannot read external Skill file: " + path, exception);
                        }
                    });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        assertFalse(files.isEmpty(), "External Skill directory contains no package files");
        return files;
    }

    private static boolean isLocalOnlyFile(Path source, Path path) {
        Path relative = source.relativize(path);
        for (Path segment : relative) {
            if (LOCAL_ONLY_DIRECTORIES.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return filename.endsWith(".pyc") || ".ds_store".equals(filename);
    }

    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(0L);
                archive.putNextEntry(entry);
                archive.write(file.getValue());
                archive.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static void assertRequiredLayout(List<AgentSkillPackageInspector.FileEntry> files) {
        String configured = System.getProperty(REQUIRED_LAYOUT, "");
        Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .forEach(directory -> assertTrue(files.stream().anyMatch(file ->
                                file.path().startsWith(directory + "/")),
                        () -> "External Skill package is missing required layout: " + directory + "/"));
    }

    private static Map<?, ?> requiredMap(Object value, String field) {
        assertNotNull(value, () -> "Missing compatibility field: " + field);
        assertTrue(value instanceof Map<?, ?>, () -> "Compatibility field is not an object: " + field);
        return (Map<?, ?>) value;
    }

}
