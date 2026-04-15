package com.enterprise.ai.skill.scanner.cli;

import com.enterprise.ai.skill.scanner.support.TestPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillScannerCliTest {

    @TempDir
    Path tempDir;

    @Test
    void scansControllerAndGeneratesSkillServiceFromCli() throws Exception {
        SkillScannerCli cli = new SkillScannerCli();
        Path manifestPath = tempDir.resolve("legacy-order.yaml");
        Path outputDir = tempDir.resolve("skill-legacy-order");

        int scanExitCode = cli.run(new String[] {
                "scan-controller",
                "--source", TestPaths.scannerResource("controller/LegacyOrderController.java").toString(),
                "--project-name", "legacy-order",
                "--base-url", "http://localhost:9002",
                "--context-path", "/api",
                "--output", manifestPath.toString()
        });

        assertEquals(0, scanExitCode);
        assertTrue(Files.exists(manifestPath));
        assertTrue(Files.readString(manifestPath).contains("get_order"));

        int generateExitCode = cli.run(new String[] {
                "generate",
                "--manifest", manifestPath.toString(),
                "--template-dir", TestPaths.repoPath("templates", "skill-service").toString(),
                "--output-dir", outputDir.toString()
        });

        assertEquals(0, generateExitCode);
        assertTrue(Files.exists(outputDir.resolve("pom.xml")));
        assertTrue(Files.exists(outputDir.resolve("src/main/java/com/enterprise/ai/skill/generated/legacyorder/tools/CreateOrderTool.java")));
    }
}
