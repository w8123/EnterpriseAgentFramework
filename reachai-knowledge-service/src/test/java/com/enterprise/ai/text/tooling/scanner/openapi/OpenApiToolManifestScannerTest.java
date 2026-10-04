package com.enterprise.ai.text.tooling.scanner.openapi;

import com.enterprise.ai.text.tooling.scanner.ScanOptions;
import com.enterprise.ai.text.tooling.scanner.manifest.HttpApiOperation;
import com.enterprise.ai.text.tooling.scanner.manifest.ParameterLocation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolDefinition;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolManifest;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolParameterDefinition;
import com.enterprise.ai.text.tooling.scanner.support.TestPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(manifest.httpApiInventoryComplete());
        assertEquals(2, manifest.httpApis().size());

        ToolDefinition queryCustomer = manifest.tools().stream().findFirst().orElseThrow();
        assertEquals("query_customer", queryCustomer.name());
        assertEquals("GET", queryCustomer.method());
        assertEquals("/customer/search", queryCustomer.path());
        assertEquals("GET /api/customer/search", queryCustomer.endpoint());
        assertEquals("CustomerPageResponse", queryCustomer.responseType());

        ToolParameterDefinition keyword = queryCustomer.parameters().stream().findFirst().orElseThrow();
        assertEquals("keyword", keyword.name());
        assertEquals("string", keyword.type());
        assertEquals(ParameterLocation.QUERY, keyword.location());

        ToolDefinition createOrder = manifest.tools().get(1);
        assertEquals("create_order", createOrder.name());
        assertEquals("POST", createOrder.method());
        assertEquals("/customers/{customerId}/orders", createOrder.path());
        assertEquals("POST /api/customers/{customerId}/orders", createOrder.endpoint());
        assertEquals("CreateOrderRequest", createOrder.requestBodyType());

        ToolParameterDefinition customerId = createOrder.parameters().stream().findFirst().orElseThrow();
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

    @Test
    void emitsCompleteOpenApiThreeInventoryWithReferencesAllParameterLocationsAndSecurity(@TempDir Path tempDir)
            throws Exception {
        Path spec = tempDir.resolve("contracts/orders.yaml");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, """
                openapi: 3.0.3
                info: { title: Orders, version: '1' }
                servers:
                  - url: https://ignored.example/saved-context
                security:
                  - ApiKeyAuth: []
                paths:
                  /orders/{id}:
                    parameters:
                      - $ref: '#/components/parameters/OrderId'
                    get:
                      operationId: findOrder
                      responses:
                        '200':
                          $ref: '#/components/responses/OrderResponse'
                    post:
                      operationId: createOrder
                      security: []
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema: { type: string }
                        - name: page
                          in: query
                          required: false
                          schema: { type: integer }
                        - name: X-Trace
                          in: header
                          required: false
                          schema: { type: string, default: test-only-openapi-secret-should-not-appear }
                        - name: locale
                          in: cookie
                          required: false
                          schema: { type: string }
                      requestBody: { $ref: '#/components/requestBodies/CreateOrder' }
                      responses:
                        '201':
                          content:
                            application/json: { schema: { $ref: '#/components/schemas/Order' } }
                            application/vnd.orders+json: { schema: { $ref: '#/components/schemas/Order' } }
                        default:
                          content:
                            text/plain: { schema: { type: string } }
                components:
                  parameters:
                    OrderId:
                      name: id
                      in: path
                      required: true
                      schema: { type: integer }
                  responses:
                    OrderResponse:
                      content:
                        application/json: { schema: { $ref: '#/components/schemas/Order' } }
                  securitySchemes:
                    ApiKeyAuth: { $ref: '#/components/securitySchemes/SharedApiKey' }
                    SharedApiKey: { type: apiKey, in: header, name: X-API-Key }
                  requestBodies:
                    CreateOrder:
                      required: true
                      content:
                        application/json: { schema: { $ref: '#/components/schemas/OrderInput' } }
                        application/vnd.orders+json: { schema: { $ref: '#/components/schemas/OrderInput' } }
                  schemas:
                    OrderInput:
                      type: object
                      required: [quantity]
                      properties:
                        quantity: { type: integer }
                        reference: { type: string }
                    Order:
                      type: object
                      properties:
                        id: { type: integer }
                        state: { type: string }
                """);

        ProjectMetadata project = new ProjectMetadata("orders", "http://ignored", "/saved-context");
        ToolManifest manifest = scanner.scan(tempDir, spec, project, null, null);

        assertEquals(2, manifest.tools().size(), "legacy tools remain independently available");
        assertTrue(manifest.httpApiInventoryComplete());
        assertEquals(2, manifest.httpApis().size());
        HttpApiOperation get = operation(manifest, "GET");
        HttpApiOperation post = operation(manifest, "POST");
        assertEquals("contracts/orders.yaml#/paths/~1orders~1{id}/get", get.sourceLocation());
        assertTrue(get.sourceKey().startsWith("openapi:contracts/orders.yaml:"));
        assertEquals("REQUIRED", get.authenticationState());
        assertEquals(List.of("API_KEY:ApiKeyAuth"), get.authenticationSchemes());
        assertEquals(List.of("X-API-Key"), get.requiredHeaderNames());
        assertEquals("NONE", post.authenticationState());
        assertEquals(List.of("COOKIE", "HEADER", "PATH", "QUERY"),
                post.parameters().stream().map(HttpApiOperation.Parameter::location).toList());
        assertEquals("string", post.parameters().stream()
                .filter(parameter -> "PATH".equals(parameter.location()) && "id".equals(parameter.name()))
                .findFirst().orElseThrow().schema().path("type").asText(),
                "operation parameters override the matching path-level (in,name) parameter");
        assertTrue(post.requestBody().required());
        assertEquals(List.of("application/json", "application/vnd.orders+json"), post.requestBody().contentTypes());
        assertEquals("integer", post.requestBody().schema().path("properties").path("quantity").path("type").asText());
        assertEquals(List.of("201", "DEFAULT"), post.responses().stream()
                .map(HttpApiOperation.Response::status).toList());
        assertFalse(new ObjectMapper().writeValueAsString(manifest)
                .contains("test-only-openapi-secret-should-not-appear"));

        Files.writeString(spec, Files.readString(spec).replace("createOrder", "renamedOperationOnly"));
        HttpApiOperation renamed = operation(scanner.scan(tempDir, spec, project, null, null), "POST");
        assertEquals(post.sourceKey(), renamed.sourceKey());
        assertEquals(post.sourceRevision(), renamed.sourceRevision());

        HttpApiOperation differentBaseUrl = operation(scanner.scan(tempDir, spec,
                new ProjectMetadata("orders", "https://other.example", "/saved-context"), null, null), "POST");
        assertEquals(post.sourceKey(), differentBaseUrl.sourceKey());
        assertEquals(post.sourceRevision(), differentBaseUrl.sourceRevision(),
                "server host/base URL is never an API identity or source revision fact");
    }

    @Test
    void keepsProvableFactsButMakesRemoteRefsMediaDifferencesComplexSecurityAndUnknownMethodsPartial(
            @TempDir Path tempDir) throws Exception {
        String fakeSecret = "test-only-openapi-secret-should-never-serialize";
        Path spec = tempDir.resolve("unsafe.json");
        Files.writeString(spec, """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Unsafe", "version": "1"},
                  "paths": {
                    "/safe": {"get": {"responses": {"200": {"content": {"application/json": {"schema": {"type": "object"}}}}}}},
                    "/remote": {"get": {"parameters": [{"$ref": "https://example.invalid/parameter.json"}], "responses": {"200": {}}}},
                    "/media": {"post": {"requestBody": {"content": {"application/json": {"schema": {"type": "object"}}, "text/plain": {"schema": {"type": "string"}}}}, "responses": {"200": {}}}},
                    "/security": {"get": {"security": [{"a": []}, {"b": []}], "responses": {"200": {}}}},
                    "/optional": {"get": {"security": [{}, {"a": []}], "responses": {"200": {}}}},
                    "/scoped": {"get": {"security": [{"oauth": ["read"]}], "responses": {"200": {}}}},
                    "/sensitive": {"get": {"description": "%s", "parameters": [{"name": "Authorization", "in": "header", "schema": {"type": "string", "default": "%s", "enum": ["%s"]}}], "responses": {"200": {}}}},
                    "/composition": {"get": {"responses": {"200": {"content": {"application/json": {"schema": {"allOf": [{"type": "object"}]}}}}}}},
                    "/serialized": {"get": {"parameters": [{"name": "filter", "in": "query", "style": "deepObject", "schema": {"type": "object"}}], "responses": {"200": {}}}},
                    "/encoded": {"post": {"requestBody": {"content": {"multipart/form-data": {"schema": {"type": "object"}, "encoding": {"file": {"contentType": "image/png"}}}}}, "responses": {"200": {}}}},
                    "/missing": {"get": {"responses": {"200": {"$ref": "#/components/responses/Missing"}}}},
                    "/cycle": {"get": {"responses": {"200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Loop"}}}}}}},
                    "/unknown": {"connect": {"responses": {"200": {}}}}
                  },
                  "components": {
                    "schemas": {"Loop": {"$ref": "#/components/schemas/Loop"}},
                    "securitySchemes": {
                      "a": {"type": "apiKey", "in": "header", "name": "X-A"},
                      "b": {"type": "apiKey", "in": "header", "name": "X-B"},
                      "oauth": {"type": "oauth2", "flows": {"clientCredentials": {"tokenUrl": "https://example.invalid/token", "scopes": {"read": "Read"}}}}
                    }
                  }
                }
                """.formatted(fakeSecret, fakeSecret, fakeSecret));

        ToolManifest manifest = scanner.scan(spec, new ProjectMetadata("unsafe", "http://ignored", ""));

        assertFalse(manifest.httpApiInventoryComplete());
        assertEquals(1, manifest.httpApis().size());
        assertEquals("/safe", manifest.httpApis().get(0).endpointPath());
        assertEquals("UNKNOWN", manifest.httpApis().get(0).authenticationState());
        assertFalse(new ObjectMapper().writeValueAsString(manifest).contains(fakeSecret));
    }

    @Test
    void inventoriesAllSupportedHttpMethodsWithoutExpandingLegacyToolRows(@TempDir Path tempDir) throws Exception {
        Path spec = tempDir.resolve("verbs.yaml");
        Files.writeString(spec, """
                openapi: 3.0.3
                info: { title: Verbs, version: '1' }
                paths:
                  /verbs:
                    delete: { responses: { '204': {} } }
                    get: { responses: { '200': {} } }
                    head: { responses: { '204': {} } }
                    options: { responses: { '200': {} } }
                    patch: { responses: { '200': {} } }
                    post: { responses: { '201': {} } }
                    put: { responses: { '200': {} } }
                    trace: { responses: { '200': {} } }
                """);

        ToolManifest manifest = scanner.scan(tempDir, spec,
                new ProjectMetadata("verbs", "https://ignored", ""), null, null);

        assertTrue(manifest.httpApiInventoryComplete());
        assertEquals(List.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "TRACE"),
                manifest.httpApis().stream().map(HttpApiOperation::httpMethod).sorted().toList());
        assertEquals(5, manifest.tools().size(),
                "the legacy Tool compatibility view remains limited to its historical five methods");
        assertEquals("READ_ONLY", operation(manifest, "TRACE").sideEffect());
        assertEquals("WRITE", operation(manifest, "DELETE").sideEffect());
    }

    @Test
    void multiSchemeHeaderAssociationMustNotBeConsideredComparable(@TempDir Path tempDir) throws Exception {
        Path spec = tempDir.resolve("auth.yaml");
        ProjectMetadata project = new ProjectMetadata("auth", "https://ignored", "");

        writeHeaderAssociationSpec(spec, "X-A", "X-B");
        ToolManifest first = scanner.scan(tempDir, spec, project, null, null);
        writeHeaderAssociationSpec(spec, "X-B", "X-A");
        ToolManifest swapped = scanner.scan(tempDir, spec, project, null, null);

        assertFalse(first.httpApiInventoryComplete());
        assertFalse(swapped.httpApiInventoryComplete());
        assertEquals(4, first.httpApis().size());
        assertEquals(4, swapped.httpApis().size());
        assertTrue(first.httpApis().stream().noneMatch(item -> "/multi".equals(item.endpointPath())));
        assertTrue(swapped.httpApis().stream().noneMatch(item -> "/multi".equals(item.endpointPath())));
        assertEquals(5, first.tools().size(), "legacy Tool rows remain available");

        HttpApiOperation single = operationAtPath(first, "/single");
        assertEquals("REQUIRED", single.authenticationState());
        assertEquals(List.of("API_KEY:onlyKey"), single.authenticationSchemes());
        assertEquals(List.of("X-Only"), single.requiredHeaderNames());
        assertEquals("REQUIRED", operationAtPath(first, "/http").authenticationState());
        assertEquals(List.of("HTTP:bearer:bearerAuth"),
                operationAtPath(first, "/http").authenticationSchemes());
        assertEquals(List.of("Authorization"),
                operationAtPath(first, "/http").requiredHeaderNames());
        assertEquals("NONE", operationAtPath(first, "/none").authenticationState());
        assertEquals("UNKNOWN", operationAtPath(first, "/unknown").authenticationState());
        assertEquals(single.sourceKey(), operationAtPath(swapped, "/single").sourceKey());
        assertEquals(single.sourceRevision(), operationAtPath(swapped, "/single").sourceRevision());
    }

    @Test
    void treatsIncompatibleOrTemplatedServersAsPartialInsteadOfChangingSavedScope(@TempDir Path tempDir)
            throws Exception {
        Path spec = tempDir.resolve("server.yaml");
        Files.writeString(spec, """
                openapi: 3.0.3
                info: { title: Server, version: '1' }
                servers:
                  - url: https://api.example/not-the-saved-context
                paths:
                  /orders:
                    get: { responses: { '200': {} } }
                """);

        ToolManifest incompatible = scanner.scan(tempDir, spec,
                new ProjectMetadata("orders", "https://saved.example", "/saved-context"), null, null);
        assertEquals(1, incompatible.tools().size(), "legacy Tool parsing remains separate from source intake");
        assertTrue(incompatible.httpApis().isEmpty());
        assertFalse(incompatible.httpApiInventoryComplete());

        Files.writeString(spec, Files.readString(spec).replace("https://api.example/not-the-saved-context",
                "https://{tenant}.example/{basePath}"));
        ToolManifest templated = scanner.scan(tempDir, spec,
                new ProjectMetadata("orders", "https://saved.example", "/saved-context"), null, null);
        assertTrue(templated.httpApis().isEmpty());
        assertFalse(templated.httpApiInventoryComplete());
    }

    @Test
    void treatsSwaggerTwoAndUnchangedIncrementalSpecsAsPartialRatherThanRemovalInventories(@TempDir Path tempDir)
            throws Exception {
        Path swagger = tempDir.resolve("legacy.json");
        Files.writeString(swagger, """
                {"swagger":"2.0","info":{"title":"Legacy","version":"1"},"paths":{"/legacy":{"get":{"responses":{"200":{"description":"ok"}}}}}}
                """);
        ToolManifest legacy = scanner.scan(swagger, new ProjectMetadata("legacy", "http://ignored", ""));
        assertEquals(1, legacy.tools().size(), "legacy Tool parsing remains compatible");
        assertEquals(List.of(), legacy.httpApis());
        assertFalse(legacy.httpApiInventoryComplete());

        Path unknown = tempDir.resolve("unknown-version.json");
        Files.writeString(unknown, """
                {"openapi":"3.not-a-version","info":{"title":"Unknown","version":"1"},"paths":{}}
                """);
        ToolManifest unknownVersion = scanner.scan(unknown, new ProjectMetadata("unknown", "http://ignored", ""));
        assertEquals(List.of(), unknownVersion.httpApis());
        assertFalse(unknownVersion.httpApiInventoryComplete());

        Path empty = tempDir.resolve("empty.yaml");
        Files.writeString(empty, """
                openapi: 3.0.3
                info: { title: Empty, version: '1' }
                paths: {}
                """);
        ToolManifest completeEmpty = scanner.scan(empty, new ProjectMetadata("empty", "http://ignored", ""));
        assertEquals(List.of(), completeEmpty.httpApis());
        assertTrue(completeEmpty.httpApiInventoryComplete(),
                "a well-formed full document with no paths is an explicit safe removal inventory");

        Path current = tempDir.resolve("current.yaml");
        Files.writeString(current, """
                openapi: 3.0.3
                info: { title: Current, version: '1' }
                paths: { /health: { get: { responses: { '200': {} } } } }
                """);
        Files.setLastModifiedTime(current, FileTime.from(Instant.now().minusSeconds(60)));
        ScanOptions options = ScanOptions.empty();
        options.setIncrementalMode(ScanOptions.MODE_MTIME);

        ToolManifest unchanged = scanner.scan(current, new ProjectMetadata("current", "http://ignored", ""), options,
                Instant.now().toEpochMilli());
        assertEquals(List.of(), unchanged.tools());
        assertEquals(List.of(), unchanged.httpApis());
        assertFalse(unchanged.httpApiInventoryComplete());
    }

    private HttpApiOperation operation(ToolManifest manifest, String method) {
        return manifest.httpApis().stream().filter(operation -> method.equals(operation.httpMethod()))
                .findFirst().orElseThrow();
    }

    @Test
    void passwordFormatIsStructuralMetadataNotASecretLiteral(@TempDir Path tempDir) throws Exception {
        Path spec = tempDir.resolve("declared-sensitive.yaml");
        Files.writeString(spec, """
                openapi: 3.0.3
                info: {title: Declared sensitive input, version: '1'}
                paths:
                  /notes:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                accessCode: {type: string, format: password}
                                deliveryMark: {type: string, writeOnly: true}
                      responses: {'200': {content: {application/json: {schema: {type: object}}}}}
                """);
        ToolManifest manifest = scanner.scan(spec, new ProjectMetadata("orders", "http://localhost", ""));
        assertEquals(1, manifest.httpApis().size(), "normal password-format operation must be discovered");
        var schema = manifest.httpApis().get(0).requestBody().schema();
        assertEquals("password", schema.at("/properties/accessCode/format").asText());
        assertTrue(schema.at("/properties/deliveryMark/writeOnly").asBoolean());
        // Allow only the exact structural format; the generic text secret filter remains unchanged.
        Files.writeString(spec, Files.readString(spec).replace("format: password", "format: token-placeholder"));
        assertTrue(scanner.scan(spec, new ProjectMetadata("orders", "http://localhost", "")).httpApis().isEmpty());
    }

    private HttpApiOperation operationAtPath(ToolManifest manifest, String path) {
        return manifest.httpApis().stream().filter(operation -> path.equals(operation.endpointPath()))
                .findFirst().orElseThrow();
    }

    private void writeHeaderAssociationSpec(Path spec, String keyAHeader, String keyBHeader) throws Exception {
        Files.writeString(spec, """
                openapi: 3.0.3
                info: { title: Authentication, version: '1' }
                paths:
                  /multi:
                    get:
                      security: [{ keyA: [], keyB: [] }]
                      responses: { '200': {} }
                  /single:
                    get:
                      security: [{ onlyKey: [] }]
                      responses: { '200': {} }
                  /http:
                    get:
                      security: [{ bearerAuth: [] }]
                      responses: { '200': {} }
                  /none:
                    get:
                      security: []
                      responses: { '200': {} }
                  /unknown:
                    get:
                      responses: { '200': {} }
                components:
                  securitySchemes:
                    keyA: { type: apiKey, in: header, name: %s }
                    keyB: { type: apiKey, in: header, name: %s }
                    onlyKey: { type: apiKey, in: header, name: X-Only }
                    bearerAuth: { type: http, scheme: bearer }
                """.formatted(keyAHeader, keyBHeader));
    }
}
