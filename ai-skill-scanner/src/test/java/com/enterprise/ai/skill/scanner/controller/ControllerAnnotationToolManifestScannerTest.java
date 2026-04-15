package com.enterprise.ai.skill.scanner.controller;

import com.enterprise.ai.skill.scanner.manifest.ParameterLocation;
import com.enterprise.ai.skill.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.skill.scanner.manifest.ToolDefinition;
import com.enterprise.ai.skill.scanner.manifest.ToolManifest;
import com.enterprise.ai.skill.scanner.manifest.ToolParameterDefinition;
import com.enterprise.ai.skill.scanner.support.TestPaths;
import org.junit.jupiter.api.Test;

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

        ToolParameterDefinition orderId = getOrder.parameters().getFirst();
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

        ToolParameterDefinition bodyJson = createOrder.parameters().getFirst();
        assertEquals("body_json", bodyJson.name());
        assertEquals("json", bodyJson.type());
        assertEquals(ParameterLocation.BODY, bodyJson.location());
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
}
