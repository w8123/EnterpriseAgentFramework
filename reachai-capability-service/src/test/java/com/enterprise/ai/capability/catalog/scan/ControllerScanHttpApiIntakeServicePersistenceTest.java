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
import com.enterprise.ai.capability.catalog.httpapi.HttpApiMappingConditionKind;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiMappingConditionOperator;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiOperationContract;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiParameterLocation;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiServiceScope;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingStatus;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceKind;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiMappingCondition;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiParameter;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiRequestBody;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiResponse;
import com.enterprise.ai.text.tooling.scanner.controller.ControllerAnnotationToolManifestScanner;
import com.enterprise.ai.text.tooling.scanner.manifest.HttpApiOperation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real H2/MyBatis coverage for the Controller scanner's independent HTTP source inventory. */
class ControllerScanHttpApiIntakeServicePersistenceTest {

    private final ObjectMapper json = new ObjectMapper();

    private JdbcTemplate jdbc;
    private HttpApiAssetMapper assets;
    private HttpApiSourceBindingMapper bindings;
    private HttpApiAssetService assetService;
    private ControllerScanHttpApiIntakeService intake;
    private TransactionTemplate tx;

    @BeforeEach
    void database() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:controller_http_api_intake_" + UUID.randomUUID()
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
        intake = new ControllerScanHttpApiIntakeService(assetService, bindings);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Test
    void declaresMandatoryObservationBoundary() throws Exception {
        Transactional annotation = ControllerScanHttpApiIntakeService.class
                .getMethod("observe", ControllerScanHttpApiIntakeService.Plan.class)
                .getAnnotation(Transactional.class);

        assertNotNull(annotation);
        assertEquals(Propagation.MANDATORY, annotation.propagation());
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, tx.getIsolationLevel());
    }

    @Test
    void completeInventoryRemovesOnlyControllerBindingsAndKeepsEquivalentStarterFact() {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        ControllerScanHttpApiIntakeService.Plan controller = intake.prepare(project,
                List.of(operation("controller:orders#get")), true);
        HttpApiServiceScope scope = controller.scope();
        HttpApiAssetService.ObserveRequest controllerRequest = controller.operations().get(0).request();

        tx.execute(status -> {
            assetService.observe(new HttpApiAssetService.ObserveRequest(scope, HttpApiSourceKind.STARTER_MVC,
                    "starter:orders#get", "OrdersController.java:42", "starter-r1", controllerRequest.contract()));
            intake.observe(controller);
            return null;
        });

        assertEquals(HttpApiSourceBindingStatus.EQUIVALENT.name(),
                binding(scope, HttpApiSourceKind.STARTER_MVC, "starter:orders#get").getStatus());
        assertEquals(HttpApiSourceBindingStatus.EQUIVALENT.name(),
                binding(scope, HttpApiSourceKind.CONTROLLER_SCAN, "controller:orders#get").getStatus());

        ControllerScanHttpApiIntakeService.Plan emptyComplete = intake.prepare(project, List.of(), true);
        ControllerScanHttpApiIntakeService.Summary summary = tx.execute(status -> intake.observe(emptyComplete));

        assertEquals(1, summary.removed());
        assertEquals(HttpApiSourceBindingStatus.REMOVED.name(),
                binding(scope, HttpApiSourceKind.CONTROLLER_SCAN, "controller:orders#get").getStatus());
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(scope, HttpApiSourceKind.STARTER_MVC, "starter:orders#get").getStatus());
        HttpApiAssetEntity asset = assets.selectOne(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .eq(HttpApiAssetEntity::getProjectId, project.getId()));
        assertEquals(HttpApiAssetStatus.DISCOVERED.name(), asset.getStatus());
        assertEquals(2L, bindings.selectCount(null), "removal retains the Controller source fact for explanation");
    }

    @Test
    void actualStarterAndStaticControllerOutputsRemainEquivalentThenDetectRealSchemaDrift(@TempDir Path tempDir)
            throws Exception {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        HttpApiOperation scanned = scanParitySource(tempDir, "long").httpApis().get(0);
        ReachHttpApiDescriptor starter = starterOperations(StarterParityController.class).get(0);
        ControllerScanHttpApiIntakeService.Plan controller = intake.prepare(project, List.of(scannerData(scanned)), true);
        HttpApiOperationContract starterContract = starterContract(starter, project.getContextPath());
        HttpApiOperationContract controllerContract = controller.operations().get(0).request().contract();
        HttpApiServiceScope scope = controller.scope();
        HttpApiContractCanonicalizer canonicalizer = new HttpApiContractCanonicalizer(json);

        assertEquals("integer", starter.getParameters().get(0).getSchema().get("type"));
        assertEquals("integer", scanned.parameters().get(0).schema().path("type").asText());
        java.util.Map<?, ?> starterRequestProperties = (java.util.Map<?, ?>) starter.getRequestBody().getSchema()
                .get("properties");
        java.util.Map<?, ?> starterQuantity = (java.util.Map<?, ?>) starterRequestProperties.get("quantity");
        assertEquals("integer", starterQuantity.get("type"));
        assertEquals("integer", scanned.requestBody().schema().path("properties").path("quantity").path("type").asText());
        assertEquals(canonicalizer.canonicalize(scope, starterContract).contractHash(),
                canonicalizer.canonicalize(scope, controllerContract).contractHash(),
                "the real framework and static scanner output must canonicalize equally");

        tx.execute(status -> {
            assetService.observe(new HttpApiAssetService.ObserveRequest(scope, HttpApiSourceKind.STARTER_MVC,
                    starter.getSourceKey(), starter.getSourceLocation(), "starter-parity-r1", starterContract));
            intake.observe(controller);
            return null;
        });
        assertEquals(HttpApiSourceBindingStatus.EQUIVALENT.name(),
                binding(scope, HttpApiSourceKind.STARTER_MVC, starter.getSourceKey()).getStatus());
        assertEquals(HttpApiSourceBindingStatus.EQUIVALENT.name(),
                binding(scope, HttpApiSourceKind.CONTROLLER_SCAN, scanned.sourceKey()).getStatus());

        HttpApiOperation drifted = scanParitySource(tempDir, "String").httpApis().get(0);
        assertEquals(scanned.sourceKey(), drifted.sourceKey(), "DTO field changes retain the same operation source identity");
        assertNotEquals(scanned.sourceRevision(), drifted.sourceRevision());
        ControllerScanHttpApiIntakeService.Plan driftPlan = intake.prepare(project, List.of(scannerData(drifted)), true);
        tx.execute(status -> intake.observe(driftPlan));

        HttpApiAssetEntity asset = assets.selectOne(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .eq(HttpApiAssetEntity::getProjectId, project.getId()));
        assertEquals(HttpApiAssetStatus.CONFLICT.name(), asset.getStatus(),
                "a genuinely different DTO schema must remain a contract conflict");
        assertEquals(HttpApiSourceBindingStatus.CONFLICT.name(),
                binding(scope, HttpApiSourceKind.STARTER_MVC, starter.getSourceKey()).getStatus());
        assertEquals(HttpApiSourceBindingStatus.CONFLICT.name(),
                binding(scope, HttpApiSourceKind.CONTROLLER_SCAN, drifted.sourceKey()).getStatus());
    }

    @Test
    void unsupportedControllerMappingProducesPartialInventoryAndCannotRemoveAnExistingBinding(@TempDir Path tempDir)
            throws Exception {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        ToolManifest initial = scanRemovalSafetySource(tempDir, "@GetMapping(\"/orders/{id}\")");
        HttpApiOperation existing = initial.httpApis().get(0);
        ControllerScanHttpApiIntakeService.Plan first = intake.prepare(project, List.of(scannerData(existing)), true);
        tx.execute(status -> intake.observe(first));

        ToolManifest unsupported = scanRemovalSafetySource(tempDir, "@CustomGetMapping(\"/orders/{id}\")");
        assertTrue(unsupported.httpApis().isEmpty());
        assertFalse(unsupported.httpApiInventoryComplete());
        ControllerScanHttpApiIntakeService.Plan partial = intake.prepare(project,
                unsupported.httpApis().stream().map(this::scannerData).toList(), unsupported.httpApiInventoryComplete());
        ControllerScanHttpApiIntakeService.Summary summary = tx.execute(status -> intake.observe(partial));

        assertEquals(0, summary.removed());
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(first.scope(), HttpApiSourceKind.CONTROLLER_SCAN, existing.sourceKey()).getStatus());
    }

    @Test
    void legacyAndPartialInventoriesNeverRemoveControllerBindings() {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        ControllerScanHttpApiIntakeService.Plan first = intake.prepare(project,
                List.of(operation("controller:orders#get")), true);
        tx.execute(status -> intake.observe(first));
        HttpApiServiceScope scope = first.scope();

        ControllerScanHttpApiIntakeService.Summary partial = tx.execute(status -> intake.observe(
                intake.prepare(project, List.of(), false)));
        ControllerScanHttpApiIntakeService.Summary legacy = tx.execute(status -> intake.observe(
                intake.prepare(project, null, null)));

        assertTrue(partial.supported());
        assertFalse(partial.inventoryComplete());
        assertEquals(0, partial.removed());
        assertFalse(legacy.supported());
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(scope, HttpApiSourceKind.CONTROLLER_SCAN, "controller:orders#get").getStatus());
    }

    @Test
    void savedProjectScopeWinsAndMalformedInventoryCannotPartiallyPersist() {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        ControllerScanHttpApiIntakeService.Plan plan = intake.prepare(project,
                List.of(operation("controller:orders#get")), true);

        assertEquals("/saved-context", plan.operations().get(0).request().contract().contextPath(),
                "scanner-returned contextPath is a parser fact, not authoritative service scope");
        tx.execute(status -> intake.observe(plan));
        HttpApiAssetEntity savedScopeAsset = assets.selectOne(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .eq(HttpApiAssetEntity::getProjectId, project.getId()));
        assertEquals("/saved-context/orders/{id}", savedScopeAsset.getRouteTemplate());

        ScanProjectEntity malformedProject = project(42L, "payments", "test", "/payments");
        CapabilityScannerClient.HttpApiData malformed = new CapabilityScannerClient.HttpApiData(
                "controller:payments#unsafe", "src/PaymentsController.java:25", "scan-r1", "GET", "/untrusted",
                "/payments/{id}", List.of(), List.of(),
                List.of(new CapabilityScannerClient.HttpApiMappingConditionData(
                        "HEADER", "Authorization", "EQUALS", "Bearer value-must-not-persist")),
                List.of(), null, List.of(), "UNKNOWN", List.of(), List.of(), "READ_ONLY");

        assertThrows(IllegalArgumentException.class,
                () -> intake.prepare(malformedProject, List.of(malformed), true));
        assertEquals(0L, assets.selectCount(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .eq(HttpApiAssetEntity::getProjectId, malformedProject.getId())));
        assertEquals(0L, bindings.selectCount(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getProjectId, malformedProject.getId())));
    }

    @Test
    void completeInventoryIsStrictlyScopedToItsSavedProjectAndEnvironment() {
        ScanProjectEntity firstProject = project(41L, "orders", "prod", "/orders");
        ScanProjectEntity otherProject = project(42L, "orders", "test", "/orders");
        ControllerScanHttpApiIntakeService.Plan first = intake.prepare(firstProject,
                List.of(operation("controller:orders#get")), true);
        ControllerScanHttpApiIntakeService.Plan other = intake.prepare(otherProject,
                List.of(operation("controller:orders#get")), true);
        tx.execute(status -> {
            intake.observe(first);
            intake.observe(other);
            return null;
        });

        tx.execute(status -> intake.observe(intake.prepare(otherProject, List.of(), true)));

        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(first.scope(), HttpApiSourceKind.CONTROLLER_SCAN, "controller:orders#get").getStatus());
        assertEquals(HttpApiSourceBindingStatus.REMOVED.name(),
                binding(other.scope(), HttpApiSourceKind.CONTROLLER_SCAN, "controller:orders#get").getStatus());
    }

    @Test
    void replayRefreshAndChangedRouteRetireOnlyThePriorControllerSourceBinding() {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        ControllerScanHttpApiIntakeService.Plan first = intake.prepare(project,
                List.of(operation("controller:orders#get", "controller-r1", "/orders/{id}")), true);
        tx.execute(status -> intake.observe(first));
        Long firstAssetId = binding(first.scope(), HttpApiSourceKind.CONTROLLER_SCAN, "controller:orders#get").getAssetId();

        ControllerScanHttpApiIntakeService.Plan replay = intake.prepare(project,
                List.of(operation("controller:orders#get", "controller-r2", "/orders/{id}")), true);
        tx.execute(status -> intake.observe(replay));
        HttpApiSourceBindingEntity refreshed = binding(first.scope(), HttpApiSourceKind.CONTROLLER_SCAN,
                "controller:orders#get");
        assertEquals(firstAssetId, refreshed.getAssetId());
        assertEquals("controller-r2", refreshed.getSourceRevision());
        assertEquals(1L, bindings.selectCount(null), "a repeated scan must update one source fact instead of duplicating it");

        ControllerScanHttpApiIntakeService.Plan routeChanged = intake.prepare(project,
                List.of(operation("controller:orders#get-v2", "controller-r3", "/orders-v2/{id}")), true);
        tx.execute(status -> intake.observe(routeChanged));

        assertEquals(HttpApiSourceBindingStatus.REMOVED.name(),
                binding(first.scope(), HttpApiSourceKind.CONTROLLER_SCAN, "controller:orders#get").getStatus());
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(first.scope(), HttpApiSourceKind.CONTROLLER_SCAN, "controller:orders#get-v2").getStatus());
        assertEquals(2L, assets.selectCount(null), "the prior asset is retained as source-missing evidence");
        assertEquals(HttpApiAssetStatus.SOURCE_MISSING.name(), assets.selectById(firstAssetId).getStatus());

        ControllerScanHttpApiIntakeService.Plan conditionChanged = intake.prepare(project,
                List.of(operation("controller:orders#get-v3", "controller-r4", "/orders-v2/{id}",
                        "READ_ONLY", "external")), true);
        tx.execute(status -> intake.observe(conditionChanged));

        assertEquals(HttpApiSourceBindingStatus.REMOVED.name(),
                binding(first.scope(), HttpApiSourceKind.CONTROLLER_SCAN, "controller:orders#get-v2").getStatus());
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(),
                binding(first.scope(), HttpApiSourceKind.CONTROLLER_SCAN, "controller:orders#get-v3").getStatus());
    }

    @Test
    void differingControllerContractConflictsWithStarterWithoutOverwritingEitherSourceFact() {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        ControllerScanHttpApiIntakeService.Plan equivalent = intake.prepare(project,
                List.of(operation("controller:orders#get", "controller-r1", "/orders/{id}")), true);
        HttpApiAssetService.ObserveRequest controllerRequest = equivalent.operations().get(0).request();
        tx.execute(status -> {
            assetService.observe(new HttpApiAssetService.ObserveRequest(equivalent.scope(), HttpApiSourceKind.STARTER_MVC,
                    "starter:orders#get", "OrdersController.java:42", "starter-r1", controllerRequest.contract()));
            intake.observe(equivalent);
            return null;
        });

        ControllerScanHttpApiIntakeService.Plan conflicting = intake.prepare(project,
                List.of(operation("controller:orders#get", "controller-r2", "/orders/{id}", "WRITE")), true);
        tx.execute(status -> intake.observe(conflicting));

        HttpApiAssetEntity asset = assets.selectOne(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .eq(HttpApiAssetEntity::getProjectId, project.getId()));
        assertEquals(HttpApiAssetStatus.CONFLICT.name(), asset.getStatus());
        assertEquals(HttpApiSourceBindingStatus.CONFLICT.name(),
                binding(equivalent.scope(), HttpApiSourceKind.STARTER_MVC, "starter:orders#get").getStatus());
        assertEquals(HttpApiSourceBindingStatus.CONFLICT.name(),
                binding(equivalent.scope(), HttpApiSourceKind.CONTROLLER_SCAN, "controller:orders#get").getStatus());
        assertEquals("starter-r1", binding(equivalent.scope(), HttpApiSourceKind.STARTER_MVC,
                "starter:orders#get").getSourceRevision());
        assertEquals("controller-r2", binding(equivalent.scope(), HttpApiSourceKind.CONTROLLER_SCAN,
                "controller:orders#get").getSourceRevision());
    }

    @Test
    void persistenceFailureRollsBackEarlierLegacyRowAndControllerObservationsInTheSameTransaction() {
        ScanProjectEntity project = project(41L, "orders", "prod", "/saved-context");
        ControllerScanHttpApiIntakeService.Plan plan = intake.prepare(project, List.of(
                operation("controller:orders#first", "controller-r1", "/orders/{id}"),
                operation("controller:orders#second", "controller-r1", "/orders/{id}/detail")), true);
        jdbc.execute("""
                ALTER TABLE capability_http_api_source_binding
                    ADD CONSTRAINT reject_second_controller_source
                    CHECK (source_key <> 'controller:orders#second')
                """);

        assertThrows(DataIntegrityViolationException.class, () -> tx.execute(status -> {
            // This is the legacy scan row that Catalog persists after prepare() and before observe().
            jdbc.update("""
                    INSERT INTO capability_scan_project_tool
                        (project_id, name, title, description, source, enabled)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, project.getId(), "orders_find", "Orders find", "legacy scan row", "scanner", false);
            intake.observe(plan);
            return null;
        }));

        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM capability_scan_project_tool", Long.class));
        assertEquals(0L, assets.selectCount(null));
        assertEquals(0L, bindings.selectCount(null));
    }

    private ScanProjectEntity project(Long id, String code, String environment, String contextPath) {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(id);
        project.setProjectCode(code);
        project.setEnvironment(environment);
        project.setContextPath(contextPath);
        return project;
    }

    private ToolManifest scanParitySource(Path tempDir, String quantityType) throws IOException {
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
                    %s quantity;
                    String reference;
                }

                class ParityResponse {
                    long id;
                    String state;
                }
                """.formatted(quantityType));
        return new ControllerAnnotationToolManifestScanner().scan(tempDir,
                new ProjectMetadata("orders", "http://localhost:9002", "/saved-context"));
    }

    private ToolManifest scanRemovalSafetySource(Path tempDir, String mapping) throws IOException {
        Files.writeString(tempDir.resolve("RemovalSafetyController.java"), """
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class RemovalSafetyController {
                    %s
                    String get(@PathVariable("id") long id) { return "ok"; }
                }
                """.formatted(mapping));
        return new ControllerAnnotationToolManifestScanner().scan(tempDir,
                new ProjectMetadata("orders", "http://localhost:9002", "/saved-context"));
    }

    @SuppressWarnings("unchecked")
    private List<ReachHttpApiDescriptor> starterOperations(Class<?> controllerType) throws Exception {
        Class<?> scannerType = Class.forName("com.enterprise.ai.reach.spring.ReachSpringMvcEndpointScanner");
        Method scanClass = scannerType.getDeclaredMethod("scanClass", Class.class);
        scanClass.setAccessible(true);
        return (List<ReachHttpApiDescriptor>) scanClass.invoke(null, controllerType);
    }

    private CapabilityScannerClient.HttpApiData scannerData(HttpApiOperation operation) {
        List<CapabilityScannerClient.HttpApiMappingConditionData> conditions = operation.mappingConditions().stream()
                .map(item -> new CapabilityScannerClient.HttpApiMappingConditionData(item.kind(), item.name(),
                        item.operator(), item.value()))
                .toList();
        List<CapabilityScannerClient.HttpApiParameterData> parameters = operation.parameters().stream()
                .map(item -> new CapabilityScannerClient.HttpApiParameterData(item.name(), item.location(), item.required(),
                        item.schema(), item.contentTypes()))
                .toList();
        CapabilityScannerClient.HttpApiRequestBodyData body = operation.requestBody() == null ? null
                : new CapabilityScannerClient.HttpApiRequestBodyData(operation.requestBody().location(),
                operation.requestBody().required(), operation.requestBody().schema(), operation.requestBody().contentTypes());
        List<CapabilityScannerClient.HttpApiResponseData> responses = operation.responses().stream()
                .map(item -> new CapabilityScannerClient.HttpApiResponseData(item.status(), item.schema(), item.contentTypes()))
                .toList();
        return new CapabilityScannerClient.HttpApiData(operation.sourceKey(), operation.sourceLocation(), operation.sourceRevision(),
                operation.httpMethod(), operation.contextPath(), operation.endpointPath(), operation.consumes(), operation.produces(),
                conditions, parameters, body, responses, operation.authenticationState(), operation.authenticationSchemes(),
                operation.requiredHeaderNames(), operation.sideEffect());
    }

    private HttpApiOperationContract starterContract(ReachHttpApiDescriptor operation, String contextPath) {
        List<HttpApiOperationContract.MappingCondition> conditions = new ArrayList<>();
        for (ReachHttpApiMappingCondition item : operation.getMappingConditions()) {
            conditions.add(new HttpApiOperationContract.MappingCondition(
                    HttpApiMappingConditionKind.valueOf(item.getKind()), item.getName(),
                    HttpApiMappingConditionOperator.valueOf(item.getOperator()), item.getValue()));
        }
        List<HttpApiOperationContract.Parameter> parameters = new ArrayList<>();
        for (ReachHttpApiParameter item : operation.getParameters()) {
            parameters.add(new HttpApiOperationContract.Parameter(item.getName(),
                    HttpApiParameterLocation.valueOf(item.getLocation()), item.isRequired(), json.valueToTree(item.getSchema()),
                    item.getContentTypes()));
        }
        ReachHttpApiRequestBody rawBody = operation.getRequestBody();
        HttpApiOperationContract.RequestBody body = rawBody == null ? null
                : new HttpApiOperationContract.RequestBody(rawBody.isRequired(), json.valueToTree(rawBody.getSchema()),
                rawBody.getContentTypes());
        List<HttpApiOperationContract.Response> responses = new ArrayList<>();
        for (ReachHttpApiResponse item : operation.getResponses()) {
            responses.add(new HttpApiOperationContract.Response(item.getStatus(), json.valueToTree(item.getSchema()),
                    item.getContentTypes()));
        }
        String authenticationState = operation.getAuthenticationState();
        return new HttpApiOperationContract(operation.getHttpMethod(), contextPath, operation.getEndpointPath(),
                new HttpApiOperationContract.MappingConditions(operation.getConsumes(), operation.getProduces(), conditions),
                parameters, body, responses,
                new HttpApiOperationContract.AuthenticationRequirement("REQUIRED".equals(authenticationState), authenticationState,
                        operation.getAuthenticationSchemes(), operation.getRequiredHeaderNames()), operation.getSideEffect());
    }

    private CapabilityScannerClient.HttpApiData operation(String sourceKey) {
        return operation(sourceKey, "controller-r1", "/orders/{id}");
    }

    private CapabilityScannerClient.HttpApiData operation(String sourceKey, String revision, String endpointPath) {
        return operation(sourceKey, revision, endpointPath, "READ_ONLY");
    }

    private CapabilityScannerClient.HttpApiData operation(String sourceKey,
                                                           String revision,
                                                           String endpointPath,
                                                           String sideEffect) {
        return operation(sourceKey, revision, endpointPath, sideEffect, "internal");
    }

    private CapabilityScannerClient.HttpApiData operation(String sourceKey,
                                                           String revision,
                                                           String endpointPath,
                                                           String sideEffect,
                                                           String mode) {
        return new CapabilityScannerClient.HttpApiData(
                sourceKey,
                "src/main/java/com/example/OrdersController.java:42",
                revision,
                "GET",
                "/untrusted-context",
                endpointPath,
                List.of("application/json"),
                List.of("application/json"),
                List.of(new CapabilityScannerClient.HttpApiMappingConditionData(
                        "HEADER", "X-Mode", "EQUALS", mode)),
                List.of(new CapabilityScannerClient.HttpApiParameterData("id", "PATH", true,
                        json.createObjectNode().put("type", "string"), List.of())),
                null,
                List.of(new CapabilityScannerClient.HttpApiResponseData("200",
                        json.createObjectNode().put("type", "object"), List.of("application/json"))),
                "UNKNOWN",
                List.of(),
                List.of(),
                sideEffect);
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

    @RestController
    @RequestMapping("/schema")
    static class StarterParityController {
        @PostMapping(value = "/orders/{id}", consumes = "application/json", produces = "application/json")
        ResponseEntity<ParityResponse> update(@PathVariable("id") long id, @RequestBody ParityRequest request) {
            return null;
        }
    }

    static class ParityRequest {
        long quantity;
        String reference;
    }

    static class ParityResponse {
        long id;
        String state;
    }
}
