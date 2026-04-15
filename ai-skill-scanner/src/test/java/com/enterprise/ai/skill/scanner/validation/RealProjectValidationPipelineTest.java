package com.enterprise.ai.skill.scanner.validation;

import com.enterprise.ai.skill.scanner.controller.ControllerAnnotationToolManifestScanner;
import com.enterprise.ai.skill.scanner.generator.SkillServiceProjectGenerator;
import com.enterprise.ai.skill.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.skill.scanner.manifest.ToolManifest;
import com.enterprise.ai.skill.scanner.support.TestPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealProjectValidationPipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void scansRealControllerAndGeneratesStableModuleSkeleton() throws Exception {
        ControllerAnnotationToolManifestScanner scanner = new ControllerAnnotationToolManifestScanner();
        SkillServiceProjectGenerator generator = new SkillServiceProjectGenerator();

        ToolManifest manifest = scanner.scan(
                TestPaths.repoPath("ai-text-service", "src", "main", "java", "com", "enterprise", "ai", "controller", "RetrievalController.java"),
                new ProjectMetadata("ai-text-retrieval", "http://localhost:8080", "/ai")
        );

        assertEquals(1, manifest.tools().size());
        assertEquals("retrieval_test", manifest.tools().get(0).name());
        assertEquals("POST /ai/retrieval/test", manifest.tools().get(0).endpoint());

        Path outputDir = tempDir.resolve("skill-ai-text-retrieval");
        generator.generate(manifest, outputDir, TestPaths.repoPath("templates", "skill-service"));

        assertTrue(Files.exists(outputDir.resolve("src/main/java/com/enterprise/ai/skill/generated/aitextretrieval/tools/RetrievalTestTool.java")));
        assertTrue(Files.readString(outputDir.resolve("pom.xml")).contains("<artifactId>skill-ai-text-retrieval</artifactId>"));
    }
}
