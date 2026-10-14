package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryIntakeService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryStateEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryStateMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryStateService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryMemberEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryMemberMapper;
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
    private HttpApiInventoryStateMapper inventories;
    private HttpApiInventoryMemberMapper members;
    private HttpApiAssetService assetService;
    private ScannerHttpApiIntakeService controllerIntake;
    private ScannerHttpApiIntakeService openApiIntake;
    private ScannerHttpApiSingleSourceIntakeService single;
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
        createTable("capability_http_api_inventory_state");
        createTable("capability_http_api_inventory_member");
        createTable("capability_scan_project_tool");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(HttpApiAssetMapper.class);
        configuration.addMapper(HttpApiSourceBindingMapper.class);
        configuration.addMapper(HttpApiInventoryStateMapper.class);
        configuration.addMapper(HttpApiInventoryMemberMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        assets = session.getMapper(HttpApiAssetMapper.class);
        bindings = session.getMapper(HttpApiSourceBindingMapper.class);
        inventories = session.getMapper(HttpApiInventoryStateMapper.class);
        members = session.getMapper(HttpApiInventoryMemberMapper.class);
        assetService = new HttpApiAssetService(assets, bindings, new HttpApiContractCanonicalizer(json), org.mockito.Mockito.mock(com.enterprise.ai.capability.catalog.enrichment.CapabilityCatalogGroupingService.class));
        var inventoryIntake = new HttpApiInventoryIntakeService(assetService, bindings,
                new HttpApiInventoryStateService(inventories, members));
        controllerIntake = new ScannerHttpApiIntakeService(inventoryIntake,
                new ScannerHttpApiContractAdapter());
        openApiIntake = new ScannerHttpApiIntakeService(inventoryIntake,
                new ScannerHttpApiContractAdapter());
        single = new ScannerHttpApiSingleSourceIntakeService(openApiIntake, bindings, assets);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Test
    void actualNestedOpenApiSingleRefreshUpdatesFormalContractButNeverCreatesGuessedBinding(@TempDir Path root)
            throws Exception {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        var initialManifest = scanOpenApiParity(root, "integer");
        var initial = scannerData(initialManifest.httpApis().get(0));
        var full = openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project, List.of(initial), true);
        tx.execute(status -> openApiIntake.observe(full));
        var before = binding(full.scope(), HttpApiSourceKind.OPENAPI_SCAN, initial.sourceKey());
        var tool = initialManifest.tools().get(0);
        var observation = new ScanProjectToolEntity();
        observation.setProjectId(project.getId()); observation.setSource("scanner"); observation.setAssetType("HTTP_API");
        observation.setContextPath(project.getContextPath()); observation.setHttpMethod(tool.method());
        observation.setEndpointPath(tool.path()); observation.setSourceLocation(tool.source().location());
        assertNotEquals(observation.getSourceLocation(), initial.sourceLocation());
        assertTrue(initial.sourceLocation().startsWith("contracts/openapi.yaml#"));
        assertTrue(ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.OPENAPI_SCAN,
                observation.getSourceLocation(), initial.sourceLocation()));
        var drift = scannerData(scanOpenApiParity(root, "string").httpApis().get(0));
        var summary = tx.execute(status -> single.observe(single.prepare(HttpApiSourceKind.OPENAPI_SCAN,
                project, observation, List.of(drift))));
        assertEquals(1, summary.observed());
        assertEquals(0, summary.removed());
        assertNotEquals(before.getSourceContractHash(), bindings.selectById(before.getId()).getSourceContractHash());
        assertEquals(before.getAssetId(), bindings.selectById(before.getId()).getAssetId());

        // A same-basename spec in another directory is a second candidate, even for the same route.
        var collision = new CapabilityScannerClient.HttpApiData("openapi:other-spec", "other/openapi.yaml#"
                + initial.sourceLocation().split("#", 2)[1], "other-r1", drift.httpMethod(), drift.contextPath(),
                drift.endpointPath(), drift.consumes(), drift.produces(), drift.mappingConditions(), drift.parameters(),
                drift.requestBody(), drift.responses(), drift.authenticationState(), drift.authenticationSchemes(),
                drift.requiredHeaderNames(), drift.sideEffect());
        var ambiguous = single.prepare(HttpApiSourceKind.OPENAPI_SCAN, project, observation, List.of(drift, collision));
        assertEquals(0, tx.execute(status -> single.observe(ambiguous)).observed());
        assertEquals(1L, bindings.selectCount(null), "ambiguous display evidence cannot invent a new source identity");
        assertNotEquals(inventory(full.scope()).getInventoryToken(), member(before.getId()).getInventoryToken());
    }

    @Test
    void declaresMandatoryObservationBoundaryAndCompleteInventoryRemovesOnlyOpenApiBindings() throws Exception {
        Transactional annotation = ScannerHttpApiIntakeService.class
                .getMethod("observe", ScannerHttpApiIntakeService.Plan.class)
                .getAnnotation(Transactional.class);
        assertNotNull(annotation);
        assertEquals(Propagation.MANDATORY, annotation.propagation());
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, tx.getIsolationLevel());
        assertThrows(NullPointerException.class, () -> openApiIntake.observe(null));

        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        ScannerHttpApiIntakeService.Plan first = openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project,
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
        String firstToken = inventory(first.scope()).getInventoryToken();
        Long sourceBindingId = binding(first.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:get").getId();
        assertEquals(firstToken, member(sourceBindingId).getInventoryToken());

        ScannerHttpApiIntakeService.Plan replay = openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project,
                List.of(operation("openapi:orders.yaml:get", "scan-r2", "/orders/{id}")), true);
        tx.execute(status -> openApiIntake.observe(replay));
        assertEquals("scan-r2", binding(first.scope(), HttpApiSourceKind.OPENAPI_SCAN,
                "openapi:orders.yaml:get").getSourceRevision());
        assertEquals(1L, bindings.selectCount(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getProjectId, project.getId())
                .eq(HttpApiSourceBindingEntity::getSourceKind, HttpApiSourceKind.OPENAPI_SCAN.name())));

        ScannerHttpApiIntakeService.Summary removed = tx.execute(status -> openApiIntake.observe(
                openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project, List.of(), true)));
        assertEquals(1, removed.removed());
        assertTrue(inventory(first.scope()).getComplete());
        assertNotEquals(inventory(first.scope()).getInventoryToken(), member(sourceBindingId).getInventoryToken(),
                "complete empty inventory removes the source without refreshing its old confirmation");
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
        ScannerHttpApiIntakeService.Plan controller = controllerIntake.prepare(HttpApiSourceKind.CONTROLLER_SCAN, project,
                List.of(scannerData(controllerOperation)), true);
        ScannerHttpApiIntakeService.Plan openApi = openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project,
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
        tx.execute(status -> openApiIntake.observe(openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project, List.of(scannerData(drifted)), true)));

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
        ScannerHttpApiIntakeService.Plan prodPlan = openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, prod,
                List.of(operation("openapi:orders.yaml:get")), true);
        ScannerHttpApiIntakeService.Plan testPlan = openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, test,
                List.of(operation("openapi:orders.yaml:get")), true);
        tx.execute(status -> {
            openApiIntake.observe(prodPlan);
            openApiIntake.observe(testPlan);
            return null;
        });
        String prodToken = inventory(prodPlan.scope()).getInventoryToken();
        String testToken = inventory(testPlan.scope()).getInventoryToken();
        Long prodBindingId = binding(prodPlan.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:get").getId();
        assertEquals(prodToken, member(prodBindingId).getInventoryToken());

        ScannerHttpApiIntakeService.Summary partial = tx.execute(status -> openApiIntake.observe(
                openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, prod, List.of(), false)));
        HttpApiInventoryStateEntity partialState = inventory(prodPlan.scope());
        assertTrue(partialState.getSupported());
        assertFalse(partialState.getComplete());
        assertTrue(partialState.getReason().contains("清单不完整"), "Chinese diagnostic must survive the persistence round trip");
        assertNotEquals(prodToken, partialState.getInventoryToken());
        assertEquals(prodToken, member(prodBindingId).getInventoryToken());
        ScannerHttpApiIntakeService.Summary legacy = tx.execute(status -> openApiIntake.observe(
                openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, prod, null, null)));
        assertTrue(partial.supported());
        assertFalse(partial.inventoryComplete());
        assertEquals(0, partial.removed());
        assertFalse(legacy.supported());
        HttpApiInventoryStateEntity unsupportedState = inventory(prodPlan.scope());
        assertFalse(unsupportedState.getSupported());
        assertFalse(unsupportedState.getComplete());
        assertTrue(unsupportedState.getReason().contains("未返回 HTTP API 清单"));
        assertEquals(prodToken, member(prodBindingId).getInventoryToken());
        assertEquals(testToken, inventory(testPlan.scope()).getInventoryToken(), "another scope's confirmation stays unchanged");
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(prodPlan.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:get").getStatus());

        tx.execute(status -> openApiIntake.observe(openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, test, List.of(), true)));
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(prodPlan.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:get").getStatus());
        assertEquals(HttpApiSourceBindingStatus.REMOVED.name(),
                binding(testPlan.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:get").getStatus());
        assertEquals(unsupportedState.getInventoryToken(), inventory(prodPlan.scope()).getInventoryToken());
        assertTrue(inventory(testPlan.scope()).getComplete());
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
        ScannerHttpApiIntakeService.Plan initial = openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project,
                complete.httpApis().stream().map(this::scannerData).toList(), complete.httpApiInventoryComplete());
        tx.execute(status -> openApiIntake.observe(initial));
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(initial.scope(), HttpApiSourceKind.OPENAPI_SCAN, original.sourceKey()).getStatus());

        Files.writeString(spec, singleScheme.replace("security: [{ keyA: [] }]",
                "security: [{ keyA: [], keyB: [] }]"));
        ToolManifest partial = scanner.scan(tempDir, spec, metadata, null, null);
        assertFalse(partial.httpApiInventoryComplete());
        assertEquals(List.of("/safe"), partial.httpApis().stream().map(HttpApiOperation::endpointPath).toList());
        ScannerHttpApiIntakeService.Plan update = openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project,
                partial.httpApis().stream().map(this::scannerData).toList(), partial.httpApiInventoryComplete());
        ScannerHttpApiIntakeService.Summary result = tx.execute(status -> openApiIntake.observe(update));
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

        assertThrows(IllegalArgumentException.class, () -> openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project, List.of(invalid), true));
        assertEquals(0L, assets.selectCount(null));
        assertEquals(0L, bindings.selectCount(null));
    }

    @Test
    void routeChangeRemovesOnlyPriorOpenApiSourceAndPersistenceFailureRollsBackLegacyRows() {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        ScannerHttpApiIntakeService.Plan initial = openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project,
                List.of(operation("openapi:orders.yaml:old", "scan-r1", "/orders/{id}")), true);
        tx.execute(status -> openApiIntake.observe(initial));
        ScannerHttpApiIntakeService.Plan changed = openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, project,
                List.of(operation("openapi:orders.yaml:new", "scan-r2", "/orders-v2/{id}")), true);
        tx.execute(status -> openApiIntake.observe(changed));
        assertEquals(HttpApiSourceBindingStatus.REMOVED.name(),
                binding(initial.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:old").getStatus());
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(initial.scope(), HttpApiSourceKind.OPENAPI_SCAN, "openapi:orders.yaml:new").getStatus());

        ScanProjectEntity rollbackProject = project(43L, "payments", "test", "/payments");
        ScannerHttpApiIntakeService.Plan failing = openApiIntake.prepare(HttpApiSourceKind.OPENAPI_SCAN, rollbackProject, List.of(
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

    private HttpApiInventoryStateEntity inventory(HttpApiServiceScope scope) {
        HttpApiInventoryStateEntity state = inventories.selectOne(Wrappers.<HttpApiInventoryStateEntity>lambdaQuery()
                .eq(HttpApiInventoryStateEntity::getProjectId, scope.projectId())
                .eq(HttpApiInventoryStateEntity::getProjectCode, scope.projectCode())
                .eq(HttpApiInventoryStateEntity::getEnvironment, scope.environment())
                .eq(HttpApiInventoryStateEntity::getSourceKind, HttpApiSourceKind.OPENAPI_SCAN.name()));
        assertNotNull(state);
        return state;
    }

    private HttpApiInventoryMemberEntity member(Long bindingId) {
        HttpApiInventoryMemberEntity member = members.selectOne(Wrappers.<HttpApiInventoryMemberEntity>lambdaQuery()
                .eq(HttpApiInventoryMemberEntity::getBindingId, bindingId));
        assertNotNull(member);
        return member;
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
