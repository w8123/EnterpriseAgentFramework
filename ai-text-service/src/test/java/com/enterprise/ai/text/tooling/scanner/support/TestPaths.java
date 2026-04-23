package com.enterprise.ai.text.tooling.scanner.support;

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

    public static Path textServiceModuleRoot() {
        return repoRoot().resolve("ai-text-service");
    }

    public static Path scannerResource(String relativePath) {
        return textServiceModuleRoot().resolve("src/test/resources/tooling/scanner").resolve(relativePath).normalize();
    }
}
