package com.enterprise.ai.skill.scanner.generator;

import com.enterprise.ai.skill.scanner.manifest.ParameterLocation;
import com.enterprise.ai.skill.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.skill.scanner.manifest.ToolDefinition;
import com.enterprise.ai.skill.scanner.manifest.ToolManifest;
import com.enterprise.ai.skill.scanner.manifest.ToolParameterDefinition;
import com.enterprise.ai.skill.scanner.manifest.ToolSource;
import com.enterprise.ai.skill.scanner.support.TestPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillServiceProjectGeneratorTest {

    @TempDir
    Path tempDir;

    private final SkillServiceProjectGenerator generator = new SkillServiceProjectGenerator();

    @Test
    void generatesCompileReadySkillServiceSkeleton() throws Exception {
        ToolManifest manifest = new ToolManifest(
                new ProjectMetadata("legacy-crm", "http://localhost:9001", "/api"),
                List.of(
                        new ToolDefinition(
                                "query_customer",
                                "查询客户信息",
                                "GET",
                                "/customer/search",
                                "GET /api/customer/search",
                                List.of(new ToolParameterDefinition(
                                        "keyword",
                                        "string",
                                        "搜索关键词",
                                        true,
                                        ParameterLocation.QUERY
                                )),
                                null,
                                "CustomerPageResponse",
                                new ToolSource("openapi", "openapi.yaml#/paths/~1customer~1search/get")
                        ),
                        new ToolDefinition(
                                "create_order",
                                "创建销售订单",
                                "POST",
                                "/customers/{customerId}/orders",
                                "POST /api/customers/{customerId}/orders",
                                List.of(
                                        new ToolParameterDefinition(
                                                "customerId",
                                                "string",
                                                "客户 ID",
                                                true,
                                                ParameterLocation.PATH
                                        ),
                                        new ToolParameterDefinition(
                                                "body_json",
                                                "json",
                                                "JSON 请求体，对应 CreateOrderRequest",
                                                true,
                                                ParameterLocation.BODY
                                        )
                                ),
                                "CreateOrderRequest",
                                "CreateOrderResponse",
                                new ToolSource("openapi", "openapi.yaml#/paths/~1customers~1{customerId}~1orders/post")
                        )
                )
        );

        Path outputDir = tempDir.resolve("skill-legacy-crm");
        Path templateDir = TestPaths.repoPath("templates", "skill-service");

        Path generatedDir = generator.generate(manifest, outputDir, templateDir);

        assertEquals(outputDir, generatedDir);
        assertTrue(Files.exists(outputDir.resolve("pom.xml")));
        assertTrue(Files.exists(outputDir.resolve("src/main/resources/application.yml")));
        assertTrue(Files.exists(outputDir.resolve("src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")));
        assertTrue(Files.exists(outputDir.resolve("src/main/java/com/enterprise/ai/skill/generated/legacycrm/LegacyCrmClient.java")));
        assertTrue(Files.exists(outputDir.resolve("src/main/java/com/enterprise/ai/skill/generated/legacycrm/tools/QueryCustomerTool.java")));
        assertTrue(Files.exists(outputDir.resolve("src/main/java/com/enterprise/ai/skill/generated/legacycrm/tools/CreateOrderTool.java")));

        String clientSource = Files.readString(outputDir.resolve("src/main/java/com/enterprise/ai/skill/generated/legacycrm/LegacyCrmClient.java"));
        assertTrue(clientSource.contains("class LegacyCrmClient"));
        assertTrue(clientSource.contains("replace(\"{\" + entry.getKey() + \"}\""));
        assertTrue(clientSource.contains("@Value(\"${skill.legacy-crm.base-url:http://localhost:9001}\")"));
        assertTrue(clientSource.contains("case \"PATCH\""));

        String toolSource = Files.readString(outputDir.resolve("src/main/java/com/enterprise/ai/skill/generated/legacycrm/tools/CreateOrderTool.java"));
        assertTrue(toolSource.contains("implements AiTool"));
        assertTrue(toolSource.contains("return \"create_order\";"));
        assertTrue(toolSource.contains("legacyCrmClient.invoke(\"POST\", \"/customers/{customerId}/orders\""));

        String applicationYaml = Files.readString(outputDir.resolve("src/main/resources/application.yml"));
        assertTrue(applicationYaml.contains("base-url: http://localhost:9001"));
    }

    @Test
    void removesStaleGeneratedFilesWhenRegeneratingSameModule() throws Exception {
        Path outputDir = tempDir.resolve("skill-ai-text-retrieval");
        Path templateDir = TestPaths.repoPath("templates", "skill-service");

        ToolManifest initialManifest = new ToolManifest(
                new ProjectMetadata("ai-text-retrieval", "http://localhost:8080", "/ai"),
                List.of(new ToolDefinition(
                        "test",
                        "旧工具",
                        "POST",
                        "/retrieval/test",
                        "POST /ai/retrieval/test",
                        List.of(new ToolParameterDefinition("body_json", "json", "旧请求体", true, ParameterLocation.BODY)),
                        "RetrievalTestRequest",
                        "RetrievalTestResponse",
                        new ToolSource("controller", "old")
                ))
        );
        generator.generate(initialManifest, outputDir, templateDir);

        ToolManifest updatedManifest = new ToolManifest(
                new ProjectMetadata("ai-text-retrieval", "http://localhost:8080", "/ai"),
                List.of(new ToolDefinition(
                        "retrieval_test",
                        "新工具",
                        "POST",
                        "/retrieval/test",
                        "POST /ai/retrieval/test",
                        List.of(new ToolParameterDefinition("body_json", "json", "新请求体", true, ParameterLocation.BODY)),
                        "RetrievalTestRequest",
                        "RetrievalTestResponse",
                        new ToolSource("controller", "new")
                ))
        );
        generator.generate(updatedManifest, outputDir, templateDir);

        assertTrue(Files.exists(outputDir.resolve("src/main/java/com/enterprise/ai/skill/generated/aitextretrieval/tools/RetrievalTestTool.java")));
        assertTrue(Files.notExists(outputDir.resolve("src/main/java/com/enterprise/ai/skill/generated/aitextretrieval/tools/TestTool.java")));
    }
}
