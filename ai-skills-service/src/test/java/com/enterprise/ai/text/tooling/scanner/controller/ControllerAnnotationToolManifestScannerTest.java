package com.enterprise.ai.text.tooling.scanner.controller;

import com.enterprise.ai.text.tooling.scanner.manifest.ParameterLocation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolDefinition;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolManifest;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolParameterDefinition;
import com.enterprise.ai.text.tooling.scanner.support.TestPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ControllerAnnotationToolManifestScannerTest {

    private final ControllerAnnotationToolManifestScanner scanner = new ControllerAnnotationToolManifestScanner();

    @Test
    void scansSpringMvcControllerIntoManifest() {
        ToolManifest manifest = scanner.scan(
                TestPaths.scannerResource("controller/LegacyOrderController.java"),
                new ProjectMetadata("legacy-order", "http://localhost:9002", "/api")
        );

        assertEquals("legacy-order", manifest.project().name());
        assertEquals(2, manifest.tools().size());

        ToolDefinition getOrder = manifest.tools().get(0);
        assertEquals("get_order", getOrder.name());
        assertEquals("GET", getOrder.method());
        assertEquals("/orders/{orderId}", getOrder.path());
        assertEquals("GET /api/orders/{orderId}", getOrder.endpoint());
        assertEquals("OrderDetailResponse", getOrder.responseType());

        ToolParameterDefinition orderId = getOrder.parameters().stream().findFirst().orElseThrow();
        assertEquals("orderId", orderId.name());
        assertEquals(ParameterLocation.PATH, orderId.location());

        ToolParameterDefinition detailLevel = getOrder.parameters().get(1);
        assertEquals("detailLevel", detailLevel.name());
        assertEquals("string", detailLevel.type());
        assertEquals(ParameterLocation.QUERY, detailLevel.location());

        ToolDefinition createOrder = manifest.tools().get(1);
        assertEquals("create_order", createOrder.name());
        assertEquals("POST", createOrder.method());
        assertEquals("CreateOrderRequest", createOrder.requestBodyType());

        ToolParameterDefinition bodyJson = createOrder.parameters().stream().findFirst().orElseThrow();
        assertEquals("body_json", bodyJson.name());
        assertEquals("json", bodyJson.type());
        assertEquals(ParameterLocation.BODY, bodyJson.location());

        List<ToolParameterDefinition> bodyChildren = bodyJson.children();
        assertEquals(4, bodyChildren.size());

        ToolParameterDefinition productCode = bodyChildren.get(0);
        assertEquals("productCode", productCode.name());
        assertEquals("string", productCode.type());
        assertEquals(ParameterLocation.BODY, productCode.location());
        assertEquals("商品编码", productCode.description());

        ToolParameterDefinition quantity = bodyChildren.get(1);
        assertEquals("quantity", quantity.name());
        assertEquals("integer", quantity.type());

        ToolParameterDefinition address = bodyChildren.get(2);
        assertEquals("address", address.name());
        assertEquals("Address", address.type());
        assertEquals(2, address.children().size());
        assertEquals("street", address.children().get(0).name());
        assertEquals("city", address.children().get(1).name());

        ToolParameterDefinition items = bodyChildren.get(3);
        assertEquals("items", items.name());
        assertEquals("List<OrderItem>", items.type());
        assertEquals(2, items.children().size());
        assertEquals("sku", items.children().get(0).name());
        assertEquals("count", items.children().get(1).name());
        assertEquals("integer", items.children().get(1).type());
    }

    @Test
    void usesControllerSourceLocationAndJavaDocDescription() {
        ToolManifest manifest = scanner.scan(
                TestPaths.scannerResource("controller/LegacyOrderController.java"),
                new ProjectMetadata("legacy-order", "http://localhost:9002", "/api")
        );

        ToolDefinition getOrder = manifest.tools().get(0);

        assertEquals("查询订单详情", getOrder.description());
        assertNotNull(getOrder.source());
        assertEquals("controller", getOrder.source().scanner());
        assertEquals("LegacyOrderController.java#LegacyOrderController#getOrder", getOrder.source().location());
    }

    @Test
    void ignoresProjectStoreAndStillScansValidController(@TempDir Path tempDir) throws IOException {
        Path ignoredDir = tempDir.resolve(".project-store").resolve("publish").resolve("version").resolve("ancestor");
        Files.createDirectories(ignoredDir);
        Files.writeString(ignoredDir.resolve("BrokenController.java"), "this is not java source");

        Path validController = tempDir.resolve("ValidController.java");
        Files.writeString(validController, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class ValidController {
                    @GetMapping("/ok")
                    String ok() {
                        return "ok";
                    }
                }
                """);

        ToolManifest manifest = scanner.scan(
                tempDir,
                new ProjectMetadata("demo", "http://localhost:9002", "/api")
        );

        assertEquals(1, manifest.tools().size());
        assertEquals("ok", manifest.tools().get(0).name());
    }

    @Test
    void skipsUnparsableJavaFileAndContinues(@TempDir Path tempDir) throws IOException {
        Path brokenFile = tempDir.resolve("BrokenController.java");
        Files.writeString(brokenFile, "broken java");

        Path validController = tempDir.resolve("HealthController.java");
        Files.writeString(validController, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class HealthController {
                    @GetMapping("/health")
                    String health() {
                        return "ok";
                    }
                }
                """);

        ToolManifest manifest = scanner.scan(
                tempDir,
                new ProjectMetadata("demo", "http://localhost:9002", "/api")
        );

        assertEquals(1, manifest.tools().size());
        assertEquals("health", manifest.tools().get(0).name());
    }

    @Test
    void prefersApiOperationOverMethodNameWhenJavaDocMissing(@TempDir Path tempDir) throws IOException {
        Path controller = tempDir.resolve("DocController.java");
        Files.writeString(controller, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class DocController {
                    @ApiOperation("按编号查询用户")
                    @GetMapping("/users/{id}")
                    String fetchUserById() {
                        return "ok";
                    }
                }
                """);

        ToolManifest manifest = scanner.scan(
                tempDir,
                new ProjectMetadata("demo", "http://localhost:9002", "/api")
        );

        assertEquals(1, manifest.tools().size());
        assertEquals("fetch_user_by_id", manifest.tools().get(0).name());
        assertEquals("按编号查询用户", manifest.tools().get(0).description());
    }

    @Test
    void prefersOpenApiOperationSummaryWhenJavaDocMissing(@TempDir Path tempDir) throws IOException {
        Path controller = tempDir.resolve("OasController.java");
        Files.writeString(controller, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class OasController {
                    @Operation(summary = "健康检查")
                    @GetMapping("/health")
                    String ping() {
                        return "ok";
                    }
                }
                """);

        ToolManifest manifest = scanner.scan(
                tempDir,
                new ProjectMetadata("demo", "http://localhost:9002", "/api")
        );

        assertEquals(1, manifest.tools().size());
        assertEquals("ping", manifest.tools().get(0).name());
        assertEquals("健康检查", manifest.tools().get(0).description());
    }

    @Test
    void blankApiOperationFallsBackToMethodName(@TempDir Path tempDir) throws IOException {
        Path controller = tempDir.resolve("BareController.java");
        Files.writeString(controller, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class BareController {
                    @ApiOperation("")
                    @GetMapping("/x")
                    String listItems() {
                        return "ok";
                    }
                }
                """);

        ToolManifest manifest = scanner.scan(
                tempDir,
                new ProjectMetadata("demo", "http://localhost:9002", "/api")
        );

        assertEquals(1, manifest.tools().size());
        assertEquals("list_items", manifest.tools().get(0).name());
        assertEquals("listItems", manifest.tools().get(0).description());
    }

    @Test
    void renamesDuplicateToolNamesToKeepManifestValid(@TempDir Path tempDir) throws IOException {
        Path controller = tempDir.resolve("ExportController.java");
        Files.writeString(controller, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class ExportController {
                    @GetMapping("/v1/export")
                    String export() {
                        return "ok";
                    }

                    @GetMapping("/v2/export")
                    String export(String type) {
                        return "ok";
                    }
                }
                """);

        ToolManifest manifest = scanner.scan(
                tempDir,
                new ProjectMetadata("demo", "http://localhost:9002", "/api")
        );

        List<String> names = manifest.tools().stream().map(ToolDefinition::name).toList();
        assertEquals(List.of("export", "export_2"), names);
    }
}
