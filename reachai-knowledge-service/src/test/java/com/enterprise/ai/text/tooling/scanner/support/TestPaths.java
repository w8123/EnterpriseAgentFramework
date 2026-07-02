package com.enterprise.ai.text.tooling.scanner.support;

import java.nio.file.Files;
import java.nio.file.Path;

public final class TestPaths {

    private TestPaths() {
    }

    public static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("reachai-knowledge-service"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Failed to locate repository root from current working directory");
    }

    public static Path knowledgeServiceModuleRoot() {
        return repoRoot().resolve("reachai-knowledge-service");
    }

    public static Path scannerResource(String relativePath) {
        return knowledgeServiceModuleRoot().resolve("src/test/resources/tooling/scanner").resolve(relativePath).normalize();
    }
}
