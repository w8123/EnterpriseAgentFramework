package com.enterprise.ai.text.tooling.scanner.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.enterprise.ai.text.tooling.scanner.manifest.ParameterLocation;
import com.enterprise.ai.text.tooling.scanner.manifest.HttpApiOperation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolDefinition;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolManifest;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolParameterDefinition;
import com.enterprise.ai.text.tooling.scanner.support.TestPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import com.enterprise.ai.text.tooling.scanner.ScanOptions;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        ToolParameterDefinition responseRoot = getOrder.parameters().get(2);
        assertEquals("返回值", responseRoot.name());
        assertEquals("OrderDetailResponse", responseRoot.type());
        assertEquals(ParameterLocation.RESPONSE, responseRoot.location());
        assertEquals(1, responseRoot.children().size());
        assertEquals("orderId", responseRoot.children().get(0).name());
        assertEquals(ParameterLocation.RESPONSE, responseRoot.children().get(0).location());

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
    void parseFailureDiagnosticsKeepTheRelativePathButNeverEchoSourceLiterals(@TempDir Path tempDir) throws Exception {
        String testOnlySecret = "test-only-controller-secret-should-never-appear-in-log";
        Path brokenFile = tempDir.resolve("BrokenSecretController.java");
        Files.writeString(brokenFile, """
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class BrokenSecretController {
                    String secret = "%s";
                    broken
                }
                """.formatted(testOnlySecret));
        Path validController = tempDir.resolve("HealthController.java");
        Files.writeString(validController, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class HealthController {
                    @GetMapping("/health")
                    String health() { return "ok"; }
                }
                """);

        Logger logger = (Logger) LoggerFactory.getLogger(ControllerAnnotationToolManifestScanner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ToolManifest manifest = scanner.scan(tempDir, new ProjectMetadata("orders", "http://localhost:9002", "/api"));
            String messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));

            assertEquals(1, manifest.tools().size());
            assertTrue(messages.contains("BrokenSecretController.java"));
            assertTrue(messages.contains("PARSE_FAILURE"));
            assertFalse(messages.contains(testOnlySecret));
            assertFalse(messages.contains(tempDir.toString()));
            assertFalse(new ObjectMapper().writeValueAsString(manifest).contains(testOnlySecret));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
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

    @Test
    void emitsIndependentLosslessHttpApiInventoryForStaticSpringMappings(@TempDir Path tempDir) throws IOException {
        Path controller = tempDir.resolve("OrderController.java");
        Files.writeString(controller, """
                package demo;

                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping(path = {"/v1", "/v2"}, headers = "X-Class=one", params = "tenant")
                class OrderController {
                    @RequestMapping(
                            path = {"/orders/{id}", "/orders/by-id/{id}"},
                            method = {RequestMethod.GET, RequestMethod.POST},
                            consumes = "application/json",
                            produces = {"application/json", "application/problem+json"},
                            headers = {"X-Mode=full", "!X-Legacy"},
                            params = "detail!=compact")
                    @ResponseStatus(HttpStatus.CREATED)
                    ResponseEntity<OrderResponse> find(
                            @PathVariable("id") String id,
                            @RequestParam(required = false) String query,
                            @RequestHeader(name = "X-Trace", required = false) String trace,
                            @CookieValue("session") String session,
                            @RequestBody OrderRequest body) {
                        return null;
                    }
                }

                class OrderRequest { @NotBlank String name; }
                class OrderResponse { String id; }
                """);

        ToolManifest manifest = scanner.scan(tempDir, new ProjectMetadata("orders", "http://localhost:9002", "/api"));

        assertEquals(1, manifest.tools().size(), "legacy Tool view remains available");
        assertTrue(manifest.httpApiInventoryComplete());
        assertEquals(8, manifest.httpApis().size(), "2 class paths × 2 method paths × GET/POST");
        HttpApiOperation operation = manifest.httpApis().stream()
                .filter(item -> "GET".equals(item.httpMethod()) && "/v1/orders/{id}".equals(item.endpointPath()))
                .findFirst().orElseThrow();
        assertTrue(operation.sourceKey().startsWith("controller:"));
        assertEquals("OrderController.java#demo.OrderController#find(String,String,String,String,OrderRequest)",
                operation.sourceLocation());
        assertTrue(operation.sourceRevision().startsWith("scan:"));
        assertEquals("/api", operation.contextPath());
        assertEquals(List.of("application/json"), operation.consumes());
        assertEquals(List.of("application/json", "application/problem+json"), operation.produces());
        assertTrue(operation.mappingConditions().contains(
                new HttpApiOperation.MappingCondition("HEADER", "X-Class", "EQUALS", "one")));
        assertTrue(operation.mappingConditions().contains(
                new HttpApiOperation.MappingCondition("PARAM", "detail", "NOT_EQUALS", "compact")));
        assertEquals(List.of("PATH", "QUERY", "HEADER", "COOKIE"),
                operation.parameters().stream().map(HttpApiOperation.Parameter::location).toList());
        assertNotNull(operation.requestBody());
        assertEquals("BODY", operation.requestBody().location());
        assertEquals("object", operation.requestBody().schema().path("type").asText());
        assertEquals("201", operation.responses().get(0).status());
        assertEquals("object", operation.responses().get(0).schema().path("type").asText());
        assertEquals("UNKNOWN", operation.authenticationState());
    }

    @Test
    void marksDynamicOrSecretMappingFactsIncompleteWithoutSerializingTheirLiteral(@TempDir Path tempDir) throws Exception {
        Path controller = tempDir.resolve("SecretController.java");
        Files.writeString(controller, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class SecretController {
                    @GetMapping(headers = "Authorization=Bearer never-persist-this")
                    String secret() { return "ok"; }
                }
                """);

        ToolManifest manifest = scanner.scan(tempDir, new ProjectMetadata("orders", "http://localhost:9002", "/api"));

        assertEquals(1, manifest.tools().size(), "old Tool scanning stays compatible");
        assertEquals(List.of(), manifest.httpApis());
        assertFalse(manifest.httpApiInventoryComplete());
        assertFalse(new ObjectMapper().writeValueAsString(manifest).contains("never-persist-this"));
    }

    @Test
    void followsSpringContentNegotiationHeaderSemanticsWithoutRetainingThemAsGenericHeaders(@TempDir Path tempDir)
            throws IOException {
        Path controller = tempDir.resolve("NegotiationController.java");
        Files.writeString(controller, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class NegotiationController {
                    @GetMapping(value = "/orders", headers = {
                            "Content-Type=application/problem+json",
                            "Accept!=application/json",
                            "X-Mode=internal"})
                    String orders() { return "ok"; }
                }
                """);

        HttpApiOperation operation = scanner.scan(tempDir,
                        new ProjectMetadata("orders", "http://localhost:9002", "/api"))
                .httpApis().get(0);

        assertEquals(List.of("application/problem+json"), operation.consumes());
        assertEquals(List.of("!application/json"), operation.produces());
        assertEquals("DEFAULT", operation.responses().get(0).status());
        assertTrue(operation.mappingConditions().contains(
                new HttpApiOperation.MappingCondition("HEADER", "X-Mode", "EQUALS", "internal")));
        assertFalse(operation.mappingConditions().stream().anyMatch(condition ->
                "HEADER".equals(condition.kind()) && ("content-type".equalsIgnoreCase(condition.name())
                        || "accept".equalsIgnoreCase(condition.name()))));
    }

    @Test
    void keepsSameMethodAndRouteAsSeparateOperationsWhenMappingConditionsDiffer(@TempDir Path tempDir)
            throws IOException {
        Path controller = tempDir.resolve("ConditionalOrdersController.java");
        Files.writeString(controller, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class ConditionalOrdersController {
                    @GetMapping(value = "/orders", headers = "X-Mode=internal")
                    String internal() { return "internal"; }

                    @GetMapping(value = "/orders", headers = "X-Mode=external")
                    String external() { return "external"; }
                }
                """);

        List<HttpApiOperation> operations = scanner.scan(tempDir,
                        new ProjectMetadata("orders", "http://localhost:9002", "/api"))
                .httpApis();

        assertEquals(2, operations.size());
        assertEquals(2, operations.stream().map(HttpApiOperation::sourceKey).distinct().count());
        assertTrue(operations.stream().anyMatch(operation -> operation.mappingConditions().contains(
                new HttpApiOperation.MappingCondition("HEADER", "X-Mode", "EQUALS", "internal"))));
        assertTrue(operations.stream().anyMatch(operation -> operation.mappingConditions().contains(
                new HttpApiOperation.MappingCondition("HEADER", "X-Mode", "EQUALS", "external"))));
    }

    @Test
    void treatsAnInvalidStaticPathAsIncompleteInsteadOfInventingARootOperation(@TempDir Path tempDir)
            throws IOException {
        Path controller = tempDir.resolve("InvalidPathController.java");
        Files.writeString(controller, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class InvalidPathController {
                    @GetMapping("/orders?should-not-be-a-route")
                    String orders() { return "ok"; }
                }
                """);

        ToolManifest manifest = scanner.scan(tempDir, new ProjectMetadata("orders", "http://localhost:9002", "/api"));

        assertEquals(List.of(), manifest.httpApis());
        assertFalse(manifest.httpApiInventoryComplete());
    }

    @Test
    void treatsRecognizableButUnsupportedComposedMappingsAsIncompleteWithoutPenalizingOrdinaryAnnotations(
            @TempDir Path tempDir) throws IOException {
        Path customMapping = tempDir.resolve("CustomMappingController.java");
        Files.writeString(customMapping, """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RequestMapping(method = RequestMethod.GET)
                @interface InternalOrdersRoute { }

                @RestController
                class CustomMappingController {
                    @InternalOrdersRoute
                    String orders() { return "ok"; }
                }
                """);

        ToolManifest customManifest = scanner.scan(tempDir,
                new ProjectMetadata("orders", "http://localhost:9002", "/api"));

        assertEquals(List.of(), customManifest.httpApis());
        assertFalse(customManifest.httpApiInventoryComplete(),
                "a source-declared but unexpandable HTTP mapping must never become a complete empty inventory");

        Files.delete(customMapping);
        Path ordinaryAnnotation = tempDir.resolve("OrdinaryAnnotationController.java");
        Files.writeString(ordinaryAnnotation, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class OrdinaryAnnotationController {
                    @AuditTrail
                    @GetMapping("/orders")
                    String orders() { return "ok"; }
                }
                """);

        ToolManifest ordinaryManifest = scanner.scan(tempDir,
                new ProjectMetadata("orders", "http://localhost:9002", "/api"));

        assertEquals(1, ordinaryManifest.httpApis().size());
        assertTrue(ordinaryManifest.httpApiInventoryComplete());
    }

    @Test
    void preservesMissingExplicitEmptyAndPartialHttpApiWireSemantics() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ProjectMetadata project = new ProjectMetadata("orders", "http://localhost:9002", "/api");
        ToolManifest oldWire = new ToolManifest(project, List.of());
        ToolManifest explicitEmpty = new ToolManifest(project, List.of(), List.of(), true);
        ToolManifest partial = new ToolManifest(project, List.of(), List.of(), false);

        String oldPayload = mapper.writeValueAsString(oldWire);
        assertFalse(mapper.readTree(oldPayload).has("httpApis"));
        assertNull(mapper.readValue(oldPayload, ToolManifest.class).httpApis());
        assertEquals(true, mapper.readValue(mapper.writeValueAsString(explicitEmpty), ToolManifest.class)
                .httpApiInventoryComplete());
        assertEquals(List.of(), mapper.readValue(mapper.writeValueAsString(explicitEmpty), ToolManifest.class).httpApis());
        assertEquals(false, mapper.readValue(mapper.writeValueAsString(partial), ToolManifest.class)
                .httpApiInventoryComplete());
    }

    @Test
    void opaqueExternalAnnotationOnAnOtherwiseUnmappedControllerMethodIsUnknownCoverage(@TempDir Path root)
            throws IOException {
        Files.writeString(root.resolve("OrdersController.java"), """
                import org.springframework.web.bind.annotation.RestController;
                import external.routes.ReadRoute;
                @RestController
                class OrdersController {
                    @ReadRoute
                    String orders() { return "ok"; }
                }
                """);
        ToolManifest unknown = scanner.scan(root, new ProjectMetadata("orders", "http://localhost:9002", "/api"));
        assertEquals(List.of(), unknown.httpApis());
        assertFalse(unknown.httpApiInventoryComplete(),
                "unresolved annotations may be composed HTTP mappings; unknown is not confirmed absence");
    }

    @Test
    void makesAnIncrementalControllerInventoryExplicitlyPartial(@TempDir Path tempDir) throws IOException {
        Path controller = tempDir.resolve("HealthController.java");
        Files.writeString(controller, """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class HealthController {
                    @GetMapping("/health")
                    String health() { return "ok"; }
                }
                """);
        ScanOptions options = ScanOptions.empty();
        options.setIncrementalMode(ScanOptions.MODE_MTIME);

        ToolManifest manifest = scanner.scan(tempDir, new ProjectMetadata("orders", "http://localhost:9002", "/api"),
                options, System.currentTimeMillis() + 1_000L);

        assertEquals(List.of(), manifest.httpApis());
        assertFalse(manifest.httpApiInventoryComplete());
    }
}
