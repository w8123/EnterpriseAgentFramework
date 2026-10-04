package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetStatus;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiContractCanonicalizer;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiServiceScope;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingStatus;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceKind;
import com.enterprise.ai.text.tooling.scanner.controller.ControllerAnnotationToolManifestScanner;
import com.enterprise.ai.text.tooling.scanner.manifest.HttpApiOperation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolManifest;
import com.enterprise.ai.text.tooling.scanner.openapi.OpenApiToolManifestScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real H2/MyBatis coverage for the OpenAPI scanner's independent HTTP source inventory. */
class OpenApiScanHttpApiIntakeServicePersistenceTest {

    private final ObjectMapper json = new ObjectMapper();

    private JdbcTemplate jdbc;
    private HttpApiAssetMapper assets;
    private HttpApiSourceBindingMapper bindings;
    private HttpApiAssetService assetService;
    private ControllerScanHttpApiIntakeService controllerIntake;
    private OpenApiScanHttpApiIntakeService openApiIntake;
    private TransactionTemplate tx;

    @BeforeEach
    void database() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:openapi_http_api_intake_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        createTable("capability_http_api_asset");
        createTable("capability_http_api_source_binding");
        createTable("capability_scan_project_tool");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(HttpApiAssetMapper.class);
        configuration.addMapper(HttpApiSourceBindingMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        assets = session.getMapper(HttpApiAssetMapper.class);
        bindings = session.getMapper(HttpApiSourceBindingMapper.class);
        assetService = new HttpApiAssetService(assets, bindings, new HttpApiContractCanonicalizer(json));
        controllerIntake = new ControllerScanHttpApiIntakeService(assetService, bindings);
        openApiIntake = new OpenApiScanHttpApiIntakeService(assetService, bindings);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Test
    void declaresMandatoryObservationBoundaryAndCompleteInventoryRemovesOnlyOpenApiBindings() throws Exception {
        Transactional annotation = OpenApiScanHttpApiIntakeService.class
                .getMethod("observe", OpenApiScanHttpApiIntakeService.Plan.class)
                .getAnnotation(Transactional.class);
        assertNotNull(annotation);
        assertEquals(Propagation.MANDATORY, annotation.propagation());
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, tx.getIsolationLevel());

        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        OpenApiScanHttpApiIntakeService.Plan first = openApiIntake.prepare(project,
                List.of(operation("openapi:orders.yaml:get")), true);
        HttpApiAssetService.ObserveRequest source = first.operations().get(0).request();
        tx.execute(status -> {
            assetService.observe(new HttpApiAssetService.ObserveRequest(first.scope(), HttpApiSourceKind.CONTROLLER_SCAN,
                    "controller:OrdersController#get", "src/OrdersController.java:42", "controller-r1", source.contract()));
            openApiIntake.observe(first);
            return null;
        });
        assertEquals(HttpApiSourceBindingStatus.EQUIVALENT.name(),
                binding(first.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:get").getStatus());

        OpenApiScanHttpApiIntakeService.Plan replay = openApiIntake.prepare(project,
                List.of(operation("openapi:orders.yaml:get", "scan-r2", "/orders/{id}")), true);
        tx.execute(status -> openApiIntake.observe(replay));
        assertEquals("scan-r2", binding(first.scope(), HttpApiSourceKind.OPENAPI_SCAN,
                "openapi:orders.yaml:get").getSourceRevision());
        assertEquals(1L, bindings.selectCount(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getProjectId, project.getId())
                .eq(HttpApiSourceBindingEntity::getSourceKind, HttpApiSourceKind.OPENAPI_SCAN.name())));

        OpenApiScanHttpApiIntakeService.Summary removed = tx.execute(status -> openApiIntake.observe(
                openApiIntake.prepare(project, List.of(), true)));
        assertEquals(1, removed.removed());
        assertEquals(HttpApiSourceBindingStatus.REMOVED.name(),
                binding(first.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:get").getStatus());
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(first.scope(), HttpApiSourceKind.CONTROLLER_SCAN, "controller:OrdersController#get").getStatus());
    }

    @Test
    void actualOpenApiAndStaticControllerOutputsAreEquivalentThenSchemaDriftConflicts(@TempDir Path tempDir)
            throws Exception {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        HttpApiOperation controllerOperation = scanControllerParity(tempDir).httpApis().get(0);
        HttpApiOperation openApiOperation = scanOpenApiParity(tempDir, "integer").httpApis().get(0);
        ControllerScanHttpApiIntakeService.Plan controller = controllerIntake.prepare(project,
                List.of(scannerData(controllerOperation)), true);
        OpenApiScanHttpApiIntakeService.Plan openApi = openApiIntake.prepare(project,
                List.of(scannerData(openApiOperation)), true);
        HttpApiContractCanonicalizer canonicalizer = new HttpApiContractCanonicalizer(json);

        HttpApiContractCanonicalizer.CanonicalHttpApiContract controllerCanonical = canonicalizer.canonicalize(
                controller.scope(), controller.operations().get(0).request().contract());
        HttpApiContractCanonicalizer.CanonicalHttpApiContract openApiCanonical = canonicalizer.canonicalize(
                openApi.scope(), openApi.operations().get(0).request().contract());
        assertEquals(controllerCanonical.contractJson(), openApiCanonical.contractJson(),
                "actual OpenAPI and Controller scanner facts must canonicalize equally");
        tx.execute(status -> {
            controllerIntake.observe(controller);
            openApiIntake.observe(openApi);
            return null;
        });
        assertEquals(HttpApiSourceBindingStatus.EQUIVALENT.name(),
                binding(openApi.scope(), HttpApiSourceKind.CONTROLLER_SCAN, controllerOperation.sourceKey()).getStatus());
        assertEquals(HttpApiSourceBindingStatus.EQUIVALENT.name(),
                binding(openApi.scope(), HttpApiSourceKind.OPENAPI_SCAN, openApiOperation.sourceKey()).getStatus());

        HttpApiOperation drifted = scanOpenApiParity(tempDir, "string").httpApis().get(0);
        assertEquals(openApiOperation.sourceKey(), drifted.sourceKey());
        assertNotEquals(openApiOperation.sourceRevision(), drifted.sourceRevision());
        tx.execute(status -> openApiIntake.observe(openApiIntake.prepare(project, List.of(scannerData(drifted)), true)));

        HttpApiAssetEntity asset = assets.selectOne(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .eq(HttpApiAssetEntity::getProjectId, project.getId()));
        assertEquals(HttpApiAssetStatus.CONFLICT.name(), asset.getStatus());
        assertEquals(HttpApiSourceBindingStatus.CONFLICT.name(),
                binding(openApi.scope(), HttpApiSourceKind.CONTROLLER_SCAN, controllerOperation.sourceKey()).getStatus());
        assertEquals(HttpApiSourceBindingStatus.CONFLICT.name(),
                binding(openApi.scope(), HttpApiSourceKind.OPENAPI_SCAN, drifted.sourceKey()).getStatus());
    }

    @Test
    void oldWireAndPartialInventoriesDoNotRemoveAndScopeIsolatedFullRemovalDoes() {
        ScanProjectEntity prod = project(41L, "orders", "prod", "/saved-context");
        ScanProjectEntity test = project(42L, "orders", "test", "/saved-context");
        OpenApiScanHttpApiIntakeService.Plan prodPlan = openApiIntake.prepare(prod,
                List.of(operation("openapi:orders.yaml:get")), true);
        OpenApiScanHttpApiIntakeService.Plan testPlan = openApiIntake.prepare(test,
                List.of(operation("openapi:orders.yaml:get")), true);
        tx.execute(status -> {
            openApiIntake.observe(prodPlan);
            openApiIntake.observe(testPlan);
            return null;
        });

        OpenApiScanHttpApiIntakeService.Summary partial = tx.execute(status -> openApiIntake.observe(
                openApiIntake.prepare(prod, List.of(), false)));
        OpenApiScanHttpApiIntakeService.Summary legacy = tx.execute(status -> openApiIntake.observe(
                openApiIntake.prepare(prod, null, null)));
        assertTrue(partial.supported());
        assertFalse(partial.inventoryComplete());
        assertEquals(0, partial.removed());
        assertFalse(legacy.supported());
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(prodPlan.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:get").getStatus());

        tx.execute(status -> openApiIntake.observe(openApiIntake.prepare(test, List.of(), true)));
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(prodPlan.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:get").getStatus());
        assertEquals(HttpApiSourceBindingStatus.REMOVED.name(),
                binding(testPlan.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:get").getStatus());
    }

    @Test
    void realScannerMultiSchemePartialInventoryKeepsPreviouslyObservedBinding(@TempDir Path tempDir)
            throws Exception {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        Path spec = tempDir.resolve("auth.yaml");
        String singleScheme = """
                openapi: 3.0.3
                info: { title: Authentication, version: '1' }
                paths:
                  /protected:
                    get:
                      security: [{ keyA: [] }]
                      responses: { '200': {} }
                  /safe:
                    get:
                      security: []
                      responses: { '200': {} }
                components:
                  securitySchemes:
                    keyA: { type: apiKey, in: header, name: X-A }
                    keyB: { type: apiKey, in: header, name: X-B }
                """;
        OpenApiToolManifestScanner scanner = new OpenApiToolManifestScanner();
        ProjectMetadata metadata = new ProjectMetadata("orders", "https://ignored", "/saved-context");
        Files.writeString(spec, singleScheme);
        ToolManifest complete = scanner.scan(tempDir, spec, metadata, null, null);
        assertTrue(complete.httpApiInventoryComplete());
        assertEquals(2, complete.httpApis().size());
        HttpApiOperation original = complete.httpApis().stream()
                .filter(item -> "/protected".equals(item.endpointPath())).findFirst().orElseThrow();
        OpenApiScanHttpApiIntakeService.Plan initial = openApiIntake.prepare(project,
                complete.httpApis().stream().map(this::scannerData).toList(), complete.httpApiInventoryComplete());
        tx.execute(status -> openApiIntake.observe(initial));
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(initial.scope(), HttpApiSourceKind.OPENAPI_SCAN, original.sourceKey()).getStatus());

        Files.writeString(spec, singleScheme.replace("security: [{ keyA: [] }]",
                "security: [{ keyA: [], keyB: [] }]"));
        ToolManifest partial = scanner.scan(tempDir, spec, metadata, null, null);
        assertFalse(partial.httpApiInventoryComplete());
        assertEquals(List.of("/safe"), partial.httpApis().stream().map(HttpApiOperation::endpointPath).toList());
        OpenApiScanHttpApiIntakeService.Plan update = openApiIntake.prepare(project,
                partial.httpApis().stream().map(this::scannerData).toList(), partial.httpApiInventoryComplete());
        OpenApiScanHttpApiIntakeService.Summary result = tx.execute(status -> openApiIntake.observe(update));
        assertEquals(0, result.removed());
        HttpApiSourceBindingEntity retained = binding(initial.scope(), HttpApiSourceKind.OPENAPI_SCAN,
                original.sourceKey());
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(), retained.getStatus());
        assertEquals(original.sourceRevision(), retained.getSourceRevision());
    }

    @Test
    void invalidOpenApiCandidateIsRejectedBeforeAnySourceFactCanPersist() {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        CapabilityScannerClient.HttpApiData invalid = new CapabilityScannerClient.HttpApiData(
                "openapi:orders.yaml:invalid", "contracts/orders.yaml#/paths/~1orders/get", "scan-r1", "GET", "/ignored",
                "/orders", List.of(), List.of(), List.of(new CapabilityScannerClient.HttpApiMappingConditionData(
                "HEADER", "Authorization", "EQUALS", "Bearer should-not-be-a-contract-value")), List.of(), null,
                List.of(), "UNKNOWN", List.of(), List.of(), "READ_ONLY");

        assertThrows(IllegalArgumentException.class, () -> openApiIntake.prepare(project, List.of(invalid), true));
        assertEquals(0L, assets.selectCount(null));
        assertEquals(0L, bindings.selectCount(null));
    }

    @Test
    void routeChangeRemovesOnlyPriorOpenApiSourceAndPersistenceFailureRollsBackLegacyRows() {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        OpenApiScanHttpApiIntakeService.Plan initial = openApiIntake.prepare(project,
                List.of(operation("openapi:orders.yaml:old", "scan-r1", "/orders/{id}")), true);
        tx.execute(status -> openApiIntake.observe(initial));
        OpenApiScanHttpApiIntakeService.Plan changed = openApiIntake.prepare(project,
                List.of(operation("openapi:orders.yaml:new", "scan-r2", "/orders-v2/{id}")), true);
        tx.execute(status -> openApiIntake.observe(changed));
        assertEquals(HttpApiSourceBindingStatus.REMOVED.name(),
                binding(initial.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:old").getStatus());
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(initial.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:new").getStatus());

        ScanProjectEntity rollbackProject = project(43L, "payments", "test", "/payments");
        OpenApiScanHttpApiIntakeService.Plan failing = openApiIntake.prepare(rollbackProject, List.of(
                operation("openapi:payments.yaml:first", "scan-r1", "/payments/{id}"),
                operation("openapi:payments.yaml:second", "scan-r1", "/payments/{id}/detail")), true);
        jdbc.execute("""
                ALTER TABLE capability_http_api_source_binding
                    ADD CONSTRAINT reject_second_openapi_source
                    CHECK (source_key <> 'openapi:payments.yaml:second')
                """);

        assertThrows(DataIntegrityViolationException.class, () -> tx.execute(status -> {
            jdbc.update("""
                    INSERT INTO capability_scan_project_tool
                        (project_id, name, title, description, source, enabled)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, rollbackProject.getId(), "payments_find", "Payments find", "legacy scan row", "scanner", false);
            openApiIntake.observe(failing);
            return null;
        }));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_scan_project_tool WHERE project_id = 43", Long.class));
        assertEquals(0L, bindings.selectCount(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getProjectId, rollbackProject.getId())));
    }

    private ScanProjectEntity project(Long id, String code, String environment, String contextPath) {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(id);
        project.setProjectCode(code);
        project.setEnvironment(environment);
        project.setContextPath(contextPath);
        return project;
    }

    private ToolManifest scanControllerParity(Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("ParityController.java"), """
                package parity;

                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/schema")
                class ParityController {
                    @PostMapping(value = "/orders/{id}", consumes = "application/json", produces = "application/json")
                    ResponseEntity<ParityResponse> update(@PathVariable("id") long id,
                                                          @RequestBody ParityRequest request) {
                        return null;
                    }
                }

                class ParityRequest {
                    long quantity;
                    String reference;
                }

                class ParityResponse {
                    long id;
                    String state;
                }
                """);
        return new ControllerAnnotationToolManifestScanner().scan(tempDir,
                new ProjectMetadata("orders", "https://ignored", "/saved-context"));
    }

    private ToolManifest scanOpenApiParity(Path tempDir, String quantityType) throws IOException {
        Path spec = tempDir.resolve("contracts/openapi.yaml");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, """
                openapi: 3.0.3
                info: { title: Orders, version: '1' }
                paths:
                  /schema/orders/{id}:
                    post:
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema: { type: integer }
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                quantity: { type: %s }
                                reference: { type: string }
                      responses:
                        default:
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  id: { type: integer }
                                  state: { type: string }
                """.formatted(quantityType));
        return new OpenApiToolManifestScanner().scan(tempDir, spec,
                new ProjectMetadata("orders", "https://ignored", "/saved-context"), null, null);
    }

    private CapabilityScannerClient.HttpApiData scannerData(HttpApiOperation operation) {
        return new CapabilityScannerClient.HttpApiData(operation.sourceKey(), operation.sourceLocation(), operation.sourceRevision(),
                operation.httpMethod(), operation.contextPath(), operation.endpointPath(), operation.consumes(), operation.produces(),
                operation.mappingConditions().stream().map(item -> new CapabilityScannerClient.HttpApiMappingConditionData(
                        item.kind(), item.name(), item.operator(), item.value())).toList(),
                operation.parameters().stream().map(item -> new CapabilityScannerClient.HttpApiParameterData(item.name(),
                        item.location(), item.required(), item.schema(), item.contentTypes())).toList(),
                operation.requestBody() == null ? null : new CapabilityScannerClient.HttpApiRequestBodyData(
                        operation.requestBody().location(), operation.requestBody().required(), operation.requestBody().schema(),
                        operation.requestBody().contentTypes()),
                operation.responses().stream().map(item -> new CapabilityScannerClient.HttpApiResponseData(item.status(),
                        item.schema(), item.contentTypes())).toList(),
                operation.authenticationState(), operation.authenticationSchemes(), operation.requiredHeaderNames(), operation.sideEffect());
    }

    private CapabilityScannerClient.HttpApiData operation(String sourceKey) {
        return operation(sourceKey, "scan-r1", "/orders/{id}");
    }

    private CapabilityScannerClient.HttpApiData operation(String sourceKey, String revision, String endpointPath) {
        return new CapabilityScannerClient.HttpApiData(sourceKey, "contracts/orders.yaml#/paths", revision, "GET", "/ignored",
                endpointPath, List.of("application/json"), List.of("application/json"), List.of(),
                List.of(new CapabilityScannerClient.HttpApiParameterData("id", "PATH", true,
                        json.createObjectNode().put("type", "integer"), List.of())), null,
                List.of(new CapabilityScannerClient.HttpApiResponseData("200",
                        json.createObjectNode().put("type", "object"), List.of("application/json"))),
                "UNKNOWN", List.of(), List.of(), "READ_ONLY");
    }

    private HttpApiSourceBindingEntity binding(HttpApiServiceScope scope, HttpApiSourceKind kind, String sourceKey) {
        HttpApiSourceBindingEntity binding = bindings.selectOne(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getProjectId, scope.projectId())
                .eq(HttpApiSourceBindingEntity::getProjectCode, scope.projectCode())
                .eq(HttpApiSourceBindingEntity::getEnvironment, scope.environment())
                .eq(HttpApiSourceBindingEntity::getSourceKind, kind.name())
                .eq(HttpApiSourceBindingEntity::getSourceKey, sourceKey)
                .last("LIMIT 1"));
        assertNotNull(binding);
        return binding;
    }

    private void createTable(String table) throws Exception {
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        var definition = Pattern.compile("(?is)CREATE TABLE(?: IF NOT EXISTS)? `?" + table + "`?\\s*\\(.*?;")
                .matcher(baseline);
        assertTrue(definition.find(), "baseline missing " + table);
        String sql = definition.group()
                .replaceAll("(?is)\\) ENGINE=.*?;", ");")
                .replaceAll("(?i)CHARACTER SET \\w+", "")
                .replaceAll("(?i)COLLATE \\w+", "")
                .replaceAll("(?i)ENUM\\([^)]*\\)", "VARCHAR(32)")
                .replaceAll("(?i)(KEY\\s+)`([^`]+)`", "$1`" + table + "_$2`")
                .replaceAll("(?i)\\bJSON\\b", "LONGTEXT");
        jdbc.execute(sql);
    }
}
