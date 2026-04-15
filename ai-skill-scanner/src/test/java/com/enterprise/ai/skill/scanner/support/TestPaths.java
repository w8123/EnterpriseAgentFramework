package com.enterprise.ai.skill.scanner.support;

import java.nio.file.Files;
import java.nio.file.Path;

public final class TestPaths {

    private TestPaths() {
    }

    public static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) && Files.isDirectory(current.resolve("ai-agent-service"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Failed to locate repository root from current working directory");
    }

    public static Path scannerModuleRoot() {
        return repoRoot().resolve("ai-skill-scanner");
    }

    public static Path scannerResource(String relativePath) {
        return scannerModuleRoot().resolve("src/test/resources").resolve(relativePath).normalize();
    }

    public static Path repoPath(String... segments) {
        Path path = repoRoot();
        for (String segment : segments) {
            path = path.resolve(segment);
        }
        return path.normalize();
    }
}
