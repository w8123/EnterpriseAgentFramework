package com.enterprise.ai.skill.scanner.openapi;

import com.enterprise.ai.skill.scanner.manifest.ParameterLocation;
import com.enterprise.ai.skill.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.skill.scanner.manifest.ToolDefinition;
import com.enterprise.ai.skill.scanner.manifest.ToolManifest;
import com.enterprise.ai.skill.scanner.manifest.ToolParameterDefinition;
import com.enterprise.ai.skill.scanner.support.TestPaths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenApiToolManifestScannerTest {

    private final OpenApiToolManifestScanner scanner = new OpenApiToolManifestScanner();

    @Test
    void scansQueryAndBodyParametersIntoToolManifest() {
        ToolManifest manifest = scanner.scan(
                TestPaths.scannerResource("openapi/legacy-crm-openapi.yaml"),
                new ProjectMetadata("legacy-crm", "http://localhost:9001", "/api")
        );

        assertEquals("legacy-crm", manifest.project().name());
        assertEquals(2, manifest.tools().size());

        ToolDefinition queryCustomer = manifest.tools().getFirst();
        assertEquals("query_customer", queryCustomer.name());
        assertEquals("GET", queryCustomer.method());
        assertEquals("/customer/search", queryCustomer.path());
        assertEquals("GET /api/customer/search", queryCustomer.endpoint());
        assertEquals("CustomerPageResponse", queryCustomer.responseType());

        ToolParameterDefinition keyword = queryCustomer.parameters().getFirst();
        assertEquals("keyword", keyword.name());
        assertEquals("string", keyword.type());
        assertEquals(ParameterLocation.QUERY, keyword.location());

        ToolDefinition createOrder = manifest.tools().get(1);
        assertEquals("create_order", createOrder.name());
        assertEquals("POST", createOrder.method());
        assertEquals("/customers/{customerId}/orders", createOrder.path());
        assertEquals("POST /api/customers/{customerId}/orders", createOrder.endpoint());
        assertEquals("CreateOrderRequest", createOrder.requestBodyType());

        ToolParameterDefinition customerId = createOrder.parameters().getFirst();
        assertEquals("customerId", customerId.name());
        assertEquals(ParameterLocation.PATH, customerId.location());

        ToolParameterDefinition bodyJson = createOrder.parameters().get(1);
        assertEquals("body_json", bodyJson.name());
        assertEquals("json", bodyJson.type());
        assertEquals(ParameterLocation.BODY, bodyJson.location());
    }

    @Test
    void capturesSourceLocationForEachOperation() {
        ToolManifest manifest = scanner.scan(
                TestPaths.scannerResource("openapi/legacy-crm-openapi.yaml"),
                new ProjectMetadata("legacy-crm", "http://localhost:9001", "/api")
        );

        ToolDefinition tool = manifest.tools().get(0);

        assertNotNull(tool.source());
        assertEquals("openapi", tool.source().scanner());
        assertEquals("legacy-crm-openapi.yaml#/paths/~1customer~1search/get", tool.source().location());
    }
}
