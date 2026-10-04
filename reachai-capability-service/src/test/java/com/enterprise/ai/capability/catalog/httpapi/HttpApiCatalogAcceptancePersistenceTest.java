package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.capability.catalog.scan.CapabilityScannerClient;
import com.enterprise.ai.capability.catalog.scan.OpenApiScanHttpApiIntakeService;
import com.enterprise.ai.capability.catalog.scan.ControllerScanHttpApiIntakeService;
import com.enterprise.ai.text.tooling.scanner.controller.ControllerAnnotationToolManifestScanner;
import com.enterprise.ai.text.tooling.scanner.manifest.HttpApiOperation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.openapi.OpenApiToolManifestScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Real H2/MyBatis acceptance evidence and latest-source membership, without a live project database. */
class HttpApiCatalogAcceptancePersistenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private HttpApiAssetMapper assets;
    private HttpApiSourceBindingMapper bindings;
    private HttpApiInventoryStateMapper inventories;
    private HttpApiInventoryMemberMapper members;
    private HttpApiAcceptanceMapper acceptances;
    private ScanProjectMapper projects;
    private HttpApiCatalogService catalog;
    private HttpApiAssetService assetService;
    private TransactionTemplate tx;

    @BeforeEach
    void database() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:http_api_catalog_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        for (String table : List.of("capability_scan_project", "capability_http_api_asset",
                "capability_http_api_source_binding", "capability_http_api_inventory_state",
                "capability_http_api_inventory_member", "capability_http_api_acceptance")) createTable(table);
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(ScanProjectMapper.class, HttpApiAssetMapper.class,
                HttpApiSourceBindingMapper.class, HttpApiInventoryStateMapper.class,
                HttpApiInventoryMemberMapper.class, HttpApiAcceptanceMapper.class)) config.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(config);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        assets = session.getMapper(HttpApiAssetMapper.class);
        bindings = session.getMapper(HttpApiSourceBindingMapper.class);
        inventories = session.getMapper(HttpApiInventoryStateMapper.class);
        members = session.getMapper(HttpApiInventoryMemberMapper.class);
        acceptances = session.getMapper(HttpApiAcceptanceMapper.class);
        projects = session.getMapper(ScanProjectMapper.class);
        assetService = new HttpApiAssetService(assets, bindings, new HttpApiContractCanonicalizer(json));
        catalog = new HttpApiCatalogService(assets, bindings, inventories, members, acceptances, projects, json);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.update("INSERT INTO capability_scan_project (id, name, project_code, environment, base_url, scan_path, scan_type, status) "
                + "VALUES (41, 'orders', 'orders', 'dev', 'https://unused.invalid', '.', 'openapi', 'scanned')");
    }

    @Test
    void currentOperationAcceptsExactCanonicalBytesOnceAndPartialScanBlocksOldBinding() throws Exception {
        HttpApiAssetService.Observation observed = observe(contract("READ_ONLY"), "scan-r1");
        long id = observed.asset().getId();
        confirm(observed.binding().getId(), "inventory-r1", true, null);
        HttpApiCatalogService.ApiDetail before = catalog.detail(id);
        assertEquals("DISCOVERED", before.summary().sourceStatus());
        assertTrue(before.summary().sourceConfirmed());

        HttpApiCatalogService.ApiDetail accepted = tx.execute(status -> catalog.accept(id,
                before.summary().sourceSetRevision(), "actor-1"));
        assertEquals("ACCEPTED", accepted.summary().sourceStatus());
        assertEquals(observed.binding().getSourceContractHash(), accepted.summary().acceptedContractHash());
        assertEquals(observed.binding().getSourceContractJson(), assets.selectById(id).getAcceptedContractJson(),
                "the accepted hash must continue to describe the exact persisted canonical bytes");
        tx.execute(status -> catalog.accept(id, before.summary().sourceSetRevision(), "actor-1"));
        assertEquals(1, count("capability_http_api_acceptance"), "same source revision is idempotent");
        assertEquals("actor-1", jdbc.queryForObject("SELECT accepted_by FROM capability_http_api_acceptance",
                String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_acceptance "
                + "WHERE before_contract_hash IS NOT NULL", Integer.class));

        // The retained binding is not a newly confirmed operation when a later partial inventory omits it.
        inventories.selectList(null).forEach(state -> {
            state.setInventoryToken("inventory-r2"); state.setComplete(false);
            state.setReason("本次 OpenAPI 清单不完整"); inventories.updateById(state);
        });
        HttpApiCatalogService.ApiDetail partial = catalog.detail(id);
        assertEquals("SOURCE_UNCONFIRMED", partial.summary().sourceStatus());
        assertFalse(partial.summary().sourceConfirmed());
        assertFalse(partial.sources().get(0).confirmedInLatestInventory());
        assertThrows(HttpApiCatalogService.Conflict.class,
                () -> tx.execute(status -> catalog.accept(id, partial.summary().sourceSetRevision(), "actor-2")));
        assertEquals(1, count("capability_http_api_acceptance"));
        assertEquals(observed.binding().getSourceContractHash(), assets.selectById(id).getAcceptedContractHash());
    }

    @Test
    void staleRevisionAndContractDriftNeverOverwriteAcceptedSnapshot() throws Exception {
        HttpApiAssetService.Observation initial = observe(contract("READ_ONLY"), "scan-r1");
        long id = initial.asset().getId();
        confirm(initial.binding().getId(), "inventory-r1", true, null);
        String revision = catalog.detail(id).summary().sourceSetRevision();
        tx.execute(status -> catalog.accept(id, revision, "actor-1"));
        String acceptedJson = assets.selectById(id).getAcceptedContractJson();

        HttpApiAssetService.Observation drift = observe(contract("WRITE"), "scan-r2");
        assertEquals(id, drift.asset().getId());
        confirm(drift.binding().getId(), "inventory-r2", true, null);
        HttpApiCatalogService.ApiDetail current = catalog.detail(id);
        assertEquals("CONTRACT_DRIFT", current.summary().sourceStatus());
        assertThrows(HttpApiCatalogService.Conflict.class,
                () -> tx.execute(status -> catalog.accept(id, revision, "actor-2")));
        assertEquals(acceptedJson, assets.selectById(id).getAcceptedContractJson());
        assertNotEquals(current.summary().candidateContractHash(), current.summary().acceptedContractHash());
        assertEquals(1, count("capability_http_api_acceptance"));
    }

    @Test
    void actualOpenApiScannerPersistsCandidateThatCanBeReadAndAccepted(@TempDir Path tempDir) throws Exception {
        Path spec = tempDir.resolve("orders.yaml");
        Files.writeString(spec, """
                openapi: 3.0.3
                info: { title: Orders, version: '1' }
                components:
                  securitySchemes:
                    OrderKey: { type: apiKey, in: header, name: X-API-Key }
                paths:
                  /orders/{orderId}:
                    get:
                      security: [{ OrderKey: [] }]
                      parameters:
                        - name: orderId
                          in: path
                          required: true
                          schema: { type: string }
                        - name: expanded
                          in: query
                          required: false
                          schema: { type: boolean }
                      responses:
                        '200':
                          content:
                            application/json:
                              schema: { type: object, properties: { orderId: { type: string } } }
                """);
        var scan = new OpenApiToolManifestScanner().scan(tempDir, spec,
                new ProjectMetadata("orders", "https://unused.invalid", ""), null, null);
        assertTrue(scan.httpApiInventoryComplete());
        assertEquals(1, scan.httpApis().size());
        HttpApiOperation operation = scan.httpApis().get(0);
        OpenApiScanHttpApiIntakeService intake = new OpenApiScanHttpApiIntakeService(assetService, bindings,
                new HttpApiInventoryStateService(inventories, members));
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(41L); project.setProjectCode("orders"); project.setEnvironment("dev");
        project.setContextPath("");
        var plan = intake.prepare(project, List.of(scannerData(operation)), true);
        tx.execute(status -> intake.observe(plan));
        HttpApiAssetEntity asset = assets.selectList(null).get(0);
        HttpApiSourceBindingEntity binding = bindings.selectList(null).get(0);
        assertEquals(1, count("capability_http_api_inventory_state"));
        assertEquals(1, count("capability_http_api_inventory_member"));
        assertEquals(inventories.selectList(null).get(0).getInventoryToken(),
                members.selectList(null).get(0).getInventoryToken());

        HttpApiCatalogService.ApiDetail detail = catalog.detail(asset.getId());
        assertEquals("GET", detail.summary().httpMethod());
        assertEquals("/orders/{orderId}", detail.summary().routeTemplate());
        assertTrue(detail.summary().sourceConfirmed());
        assertEquals("DISCOVERED", detail.summary().sourceStatus());
        assertEquals("REQUIRED", detail.contract().path("authentication").path("state").asText());
        HttpApiCatalogService.ApiDetail accepted = tx.execute(status -> catalog.accept(
                asset.getId(), detail.summary().sourceSetRevision(), "actor-1"));
        assertEquals("ACCEPTED", accepted.summary().sourceStatus());
        assertEquals(binding.getSourceContractJson(), assets.selectById(asset.getId()).getAcceptedContractJson());
    }

    @Test
    void normalControllerAndOpenApiGetShareOneAssetAndExposeBothConflictContracts(@TempDir Path root) throws Exception {
        ScanProjectEntity project = projects.selectById(41L);
        Path controller = controllerSource(root, false);
        observeController(project, controller);
        Path spec = openApiSource(root, "string", false);
        observeOpenApi(project, root, spec);
        var asset = assets.selectList(null).get(0);
        var equivalent = catalog.detail(asset.getId());
        assertEquals(1, count("capability_http_api_asset"));
        assertEquals(2, count("capability_http_api_source_binding"));
        assertEquals(2, count("capability_http_api_inventory_state"));
        assertEquals(2, count("capability_http_api_inventory_member"));
        assertTrue(equivalent.summary().sourceConfirmed());
        assertEquals(2, equivalent.summary().activeSourceCount());
        assertTrue(equivalent.sources().stream().allMatch(s -> "EQUIVALENT".equals(s.status())));
        assertEquals(1, equivalent.sources().stream().map(HttpApiCatalogService.SourceView::sourceContractHash).distinct().count());
        assertEquals(1, catalog.list(41L, "dev", null, "GET", null, 1, 20).total());
        String stableName = equivalent.summary().qualifiedName();
        tx.execute(status -> catalog.accept(asset.getId(), equivalent.summary().sourceSetRevision(), "actor-1"));
        String acceptedBytes = assets.selectById(asset.getId()).getAcceptedContractJson();
        String acceptedHash = assets.selectById(asset.getId()).getAcceptedContractHash();

        // A real rescan with prose/property-order changes is not a third binding or a conflict.
        openApiSource(root, "string", true);
        observeOpenApi(project, root, spec);
        observeController(project, controller);
        assertEquals(2, count("capability_http_api_source_binding"));
        assertEquals("ACCEPTED", catalog.detail(asset.getId()).summary().sourceStatus());

        openApiSource(root, "integer", true);
        observeOpenApi(project, root, spec);
        var conflict = catalog.detail(asset.getId());
        assertEquals("CONFLICT", conflict.summary().sourceStatus());
        assertFalse(conflict.summary().sourceConfirmed());
        assertNull(conflict.contract(), "no arbitrary source becomes the current aggregate contract");
        assertEquals(stableName, conflict.summary().qualifiedName());
        assertEquals(acceptedBytes, assets.selectById(asset.getId()).getAcceptedContractJson());
        assertEquals(acceptedHash, assets.selectById(asset.getId()).getAcceptedContractHash());
        assertThrows(HttpApiCatalogService.Conflict.class, () -> tx.execute(status -> catalog.accept(
                asset.getId(), conflict.summary().sourceSetRevision(), "actor-2")));
        var wire = json.copy().findAndRegisterModules().valueToTree(conflict);
        for (String timestamp : List.of("observedAt", "latestInventoryAt", "confirmedAt")) {
            assertTrue(wire.at("/sources/0/" + timestamp).isTextual(),
                    "source update time must have a stable ISO string wire format: " + timestamp);
        }
        assertTrue(wire.at("/summary/acceptedAt").isTextual());
        assertEquals("string", wire.at("/sources/0/contract/responses/0/schema/properties/state/type").asText(),
                "detail must expose the Controller's canonical fact, not only its hash");
        assertEquals("integer", wire.at("/sources/1/contract/responses/0/schema/properties/state/type").asText());
        assertEquals(1, count("capability_http_api_acceptance"));

        // Unknown coverage after a known conflict cannot silently discard the conflicting fact.
        String unknownSource = Files.readString(spec).replace("get:", "get:\n      security: [{UnavailableScheme: []}]");
        Files.writeString(spec, unknownSource);
        observeOpenApi(project, root, spec);
        var unknown = catalog.detail(asset.getId());
        assertEquals("CONFLICT", unknown.summary().sourceStatus());
        assertFalse(unknown.summary().sourceConfirmed());
        assertEquals(2, unknown.summary().activeSourceCount());
        assertTrue(unknown.sources().stream().anyMatch(source -> "OPENAPI_SCAN".equals(source.sourceKind())
                && !source.inventoryComplete() && !source.confirmedInLatestInventory()));
        assertEquals("integer", unknown.sources().get(1).contract().at("/responses/0/schema/properties/state/type").asText());
        assertEquals(acceptedBytes, assets.selectById(asset.getId()).getAcceptedContractJson());
        assertEquals(acceptedHash, unknown.summary().acceptedContractHash());
    }

    @Test
    void normalPartialUnknownAndCompleteScansDoNotDeleteTheOtherSource(@TempDir Path root) throws Exception {
        ScanProjectEntity project = projects.selectById(41L);
        Path controller = controllerSource(root, false);
        Path spec = openApiSource(root, "string", false);
        observeController(project, controller);
        observeOpenApi(project, root, spec);
        long id = assets.selectList(null).get(0).getId();
        var owner = catalog.detail(id);
        tx.execute(status -> catalog.accept(id, owner.summary().sourceSetRevision(), "actor-1"));
        String accepted = assets.selectById(id).getAcceptedContractHash();

        // Unsupported authentication produces a genuine partial inventory, not a fabricated flag.
        String original = Files.readString(spec);
        Files.writeString(spec, original.replace("get:", "get:\n      security: [{UnavailableScheme: []}]"));
        observeOpenApi(project, root, spec);
        assertEquals("SOURCE_UNCONFIRMED", catalog.detail(id).summary().sourceStatus());
        assertEquals(2, catalog.detail(id).summary().activeSourceCount());
        assertTrue(bindings.selectList(null).stream().noneMatch(b -> "REMOVED".equals(b.getStatus())));

        // An opaque external annotation must not fabricate a complete empty Controller scan.
        controllerSource(root, true);
        observeController(project, controller);
        assertEquals(2, catalog.detail(id).summary().activeSourceCount());
        assertEquals("SOURCE_UNCONFIRMED", catalog.detail(id).summary().sourceStatus());

        Files.writeString(spec, "openapi: 3.0.3\ninfo: {title: Orders, version: '2'}\npaths: {}\n");
        observeOpenApi(project, root, spec);
        assertEquals(1, catalog.detail(id).summary().activeSourceCount());
        assertEquals("SOURCE_UNCONFIRMED", catalog.detail(id).summary().sourceStatus());
        controllerSource(root, false);
        observeController(project, controller);
        var remaining = catalog.detail(id);
        assertEquals("ACCEPTED", remaining.summary().sourceStatus());
        assertEquals(accepted, remaining.summary().acceptedContractHash());
        assertEquals(List.of("CONTROLLER_SCAN"), remaining.summary().sourceKinds());
        assertEquals(1, remaining.sources().stream().filter(s -> "REMOVED".equals(s.status())).count());

        Files.writeString(controller, "class NoRoutes { }\n");
        observeController(project, controller);
        assertEquals("SOURCE_MISSING", catalog.detail(id).summary().sourceStatus());
        assertEquals(accepted, catalog.detail(id).summary().acceptedContractHash());
    }

    @Test
    void registeredServiceAndEnvironmentScopesDoNotMergeTheSameGet(@TempDir Path root) throws Exception {
        Path controller = controllerSource(root, false);
        Path spec = openApiSource(root, "string", false);
        ScanProjectEntity orders = projects.selectById(41L);
        observeController(orders, controller);
        observeOpenApi(orders, root, spec);
        jdbc.update("INSERT INTO capability_scan_project (id,name,project_code,environment,base_url,scan_path,scan_type,status) "
                + "VALUES (42,'Payments','payments','dev','https://unused.invalid','.','controller','scanned')");
        ScanProjectEntity payments = projects.selectById(42L);
        observeController(payments, controller);
        observeOpenApi(payments, root, spec);
        assertEquals(2, count("capability_http_api_asset"));
        assertNotEquals(catalog.list(41L, "dev", null, null, null, 1, 20).records().get(0).qualifiedName(),
                catalog.list(42L, "dev", null, null, null, 1, 20).records().get(0).qualifiedName());
        payments.setEnvironment("test"); projects.updateById(payments);
        observeController(projects.selectById(42L), controller);
        assertEquals(3, count("capability_http_api_asset"));
        assertEquals(1, catalog.list(42L, "test", null, null, null, 1, 20).total());
        assertEquals(2, catalog.detail(assets.selectList(null).get(0).getId()).summary().activeSourceCount());
    }

    private Path controllerSource(Path root, boolean opaque) throws Exception {
        Path source = root.resolve("OrdersController.java");
        Files.writeString(source, """
                import org.springframework.web.bind.annotation.*;
                import external.routes.ReadRoute;
                @RestController
                @RequestMapping("/orders")
                class OrdersController {
                    %s
                    OrderView find(@PathVariable("orderId") String orderId,
                                   @RequestParam(value="detailLevel", required=false) String detailLevel) { return null; }
                }
                class OrderView { String orderId; String detailLevel; String state; }
                """.formatted(opaque ? "@ReadRoute" : "@GetMapping(path=\"/{orderId}\", produces=\"application/json\")"));
        return source;
    }

    private Path openApiSource(Path root, String stateType, boolean reorder) throws Exception {
        Path spec = root.resolve("orders.yaml");
        String fields = reorder ? "state: {type: " + stateType + "}, detailLevel: {type: string}, orderId: {type: string}"
                : "orderId: {type: string}, detailLevel: {type: string}, state: {type: " + stateType + "}";
        Files.writeString(spec, """
                openapi: 3.0.3
                info: {title: Orders, version: '1'}
                paths:
                  /orders/{orderId}:
                    get:
                      description: %s
                      parameters:
                        - {name: orderId, in: path, required: true, schema: {type: string}}
                        - {name: detailLevel, in: query, required: false, schema: {type: string}}
                      responses:
                        default:
                          content:
                            application/json:
                              schema: {type: object, properties: {%s}}
                """.formatted(reorder ? "Revised prose only" : "Original prose", fields));
        return spec;
    }

    private void observeController(ScanProjectEntity project, Path source) {
        var manifest = new ControllerAnnotationToolManifestScanner().scan(source,
                new ProjectMetadata(project.getProjectCode(), project.getBaseUrl(), ""));
        var intake = new ControllerScanHttpApiIntakeService(assetService, bindings,
                new HttpApiInventoryStateService(inventories, members));
        tx.execute(status -> intake.observe(intake.prepare(project, manifest.httpApis().stream()
                .map(this::scannerData).toList(), manifest.httpApiInventoryComplete())));
    }

    private void observeOpenApi(ScanProjectEntity project, Path root, Path spec) {
        var manifest = new OpenApiToolManifestScanner().scan(root, spec,
                new ProjectMetadata(project.getProjectCode(), project.getBaseUrl(), ""), null, null);
        var intake = new OpenApiScanHttpApiIntakeService(assetService, bindings,
                new HttpApiInventoryStateService(inventories, members));
        tx.execute(status -> intake.observe(intake.prepare(project, manifest.httpApis().stream()
                .map(this::scannerData).toList(), manifest.httpApiInventoryComplete())));
    }

    @Test
    void catalogPagesInDatabaseAndCountsCurrentInventoryStatusesExactly() throws Exception {
        List<Long> bindingIds = new ArrayList<>();
        List<Long> assetIds = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            int item = index;
            String route = "/orders/item-" + String.format("%02d", item) + "/{orderId}";
            HttpApiOperationContract operation = contract("READ_ONLY", route);
            HttpApiAssetService.Observation observed = tx.execute(status -> assetService.observe(
                    new HttpApiAssetService.ObserveRequest(new HttpApiServiceScope(41L, "orders", "dev"),
                            HttpApiSourceKind.OPENAPI_SCAN, "openapi:item-" + item,
                            "orders.yaml#/paths/item-" + item, "scan-r1", operation)));
            bindingIds.add(observed.binding().getId());
            assetIds.add(observed.asset().getId());
        }
        tx.execute(status -> {
            new HttpApiInventoryStateService(inventories, members).record(
                    new HttpApiServiceScope(41L, "orders", "dev"), HttpApiSourceKind.OPENAPI_SCAN,
                    true, true, null, bindingIds);
            return null;
        });
        tx.execute(status -> catalog.accept(assetIds.get(0),
                catalog.detail(assetIds.get(0)).summary().sourceSetRevision(), "actor-1"));
        HttpApiInventoryMemberEntity oldMember = members.selectList(null).stream()
                .filter(member -> member.getBindingId().equals(bindingIds.get(1))).findFirst().orElseThrow();
        oldMember.setInventoryToken("previous-inventory-token");
        members.updateById(oldMember);

        HttpApiAssetMapper countedAssets = spy(assets);
        HttpApiSourceBindingMapper countedBindings = spy(bindings);
        HttpApiCatalogService counted = new HttpApiCatalogService(countedAssets, countedBindings,
                inventories, members, acceptances, projects, json);
        HttpApiCatalogService.ApiPage second = counted.list(41L, "dev", null, "GET", null, 2, 5);
        assertEquals(25, second.total());
        assertEquals(5, second.pages());
        assertEquals(5, second.records().size());
        verify(countedAssets, times(1)).selectCount(any());
        verify(countedAssets, times(1)).selectList(argThat(wrapper ->
                wrapper.getSqlSegment().contains("LIMIT 5 OFFSET 5")));
        verify(countedBindings, times(5)).selectList(any());

        assertEquals(23, catalog.list(41L, "dev", null, "GET", "DISCOVERED", 1, 5).total());
        assertEquals(1, catalog.list(41L, "dev", null, "GET", "ACCEPTED", 1, 5).total());
        HttpApiCatalogService.ApiPage unconfirmed = catalog.list(41L, "dev", null, "GET",
                "SOURCE_UNCONFIRMED", 1, 5);
        assertEquals(1, unconfirmed.total());
        assertEquals(assetIds.get(1), unconfirmed.records().get(0).id());
        assertEquals(10, catalog.list(41L, "dev", "item-1", "GET", "DISCOVERED", 1, 5).total());
        assertEquals(0, catalog.list(41L, "dev", "item-1%", "GET", null, 1, 5).total(),
                "keyword wildcards are literal text, not a broader SQL pattern");
        assertEquals(0, catalog.list(41L, "dev", null, "POST", null, 1, 5).total());
    }

    private CapabilityScannerClient.HttpApiData scannerData(HttpApiOperation operation) {
        return new CapabilityScannerClient.HttpApiData(operation.sourceKey(), operation.sourceLocation(),
                operation.sourceRevision(), operation.httpMethod(), operation.contextPath(), operation.endpointPath(),
                operation.consumes(), operation.produces(), operation.mappingConditions().stream().map(item ->
                new CapabilityScannerClient.HttpApiMappingConditionData(item.kind(), item.name(),
                        item.operator(), item.value())).toList(), operation.parameters().stream().map(item ->
                new CapabilityScannerClient.HttpApiParameterData(item.name(), item.location(), item.required(),
                        item.schema(), item.contentTypes())).toList(), operation.requestBody() == null ? null
                : new CapabilityScannerClient.HttpApiRequestBodyData(operation.requestBody().location(),
                        operation.requestBody().required(), operation.requestBody().schema(),
                        operation.requestBody().contentTypes()), operation.responses().stream().map(item ->
                new CapabilityScannerClient.HttpApiResponseData(item.status(), item.schema(),
                        item.contentTypes())).toList(), operation.authenticationState(),
                operation.authenticationSchemes(), operation.requiredHeaderNames(), operation.sideEffect());
    }

    private HttpApiAssetService.Observation observe(HttpApiOperationContract contract, String revision) {
        return tx.execute(status -> assetService.observe(new HttpApiAssetService.ObserveRequest(
                new HttpApiServiceScope(41L, "orders", "dev"), HttpApiSourceKind.OPENAPI_SCAN,
                "openapi:orders.yaml:get", "contracts/orders.yaml#/paths/~1orders/get", revision, contract)));
    }

    private void confirm(long bindingId, String token, boolean complete, String reason) {
        HttpApiInventoryStateEntity state = inventories.selectList(null).stream().findFirst().orElse(null);
        if (state == null) {
            state = new HttpApiInventoryStateEntity(); state.setProjectId(41L);
            state.setProjectCode("orders"); state.setEnvironment("dev");
            state.setSourceKind(HttpApiSourceKind.OPENAPI_SCAN.name());
        }
        state.setInventoryToken(token); state.setSupported(true); state.setComplete(complete);
        state.setReason(reason); state.setObservedAt(LocalDateTime.now());
        if (state.getId() == null) inventories.insert(state); else inventories.updateById(state);
        HttpApiInventoryMemberEntity member = members.selectList(null).stream().findFirst().orElse(null);
        if (member == null) { member = new HttpApiInventoryMemberEntity(); member.setBindingId(bindingId); }
        member.setInventoryToken(token); member.setObservedAt(LocalDateTime.now());
        if (member.getId() == null) members.insert(member); else members.updateById(member);
    }

    private HttpApiOperationContract contract(String sideEffect) throws Exception {
        return contract(sideEffect, "/orders/{orderId}");
    }

    private HttpApiOperationContract contract(String sideEffect, String route) throws Exception {
        return new HttpApiOperationContract("GET", "", route,
                new HttpApiOperationContract.MappingConditions(List.of(), List.of("application/json"), List.of()),
                List.of(new HttpApiOperationContract.Parameter("orderId", HttpApiParameterLocation.PATH, true,
                        json.readTree("{\"type\":\"string\"}"), List.of()),
                        new HttpApiOperationContract.Parameter("expanded", HttpApiParameterLocation.QUERY, false,
                                json.readTree("{\"type\":\"boolean\"}"), List.of())), null,
                List.of(new HttpApiOperationContract.Response("200", json.readTree("{\"type\":\"object\"}"),
                        List.of("application/json"))),
                new HttpApiOperationContract.AuthenticationRequirement(true, "REQUIRED",
                        List.of("api_key:header:X-API-Key"), List.of("X-API-Key")), sideEffect);
    }

    private long count(String table) {
        if (!table.matches("[a-z_]+")) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void createTable(String table) throws Exception {
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        var match = Pattern.compile("(?is)CREATE TABLE(?: IF NOT EXISTS)? `?" + table + "`?\\s*\\(.*?;")
                .matcher(baseline);
        assertTrue(match.find(), "baseline missing " + table);
        jdbc.execute(match.group().replaceAll("(?is)\\) ENGINE=.*?;", ");")
                .replaceAll("(?i)CHARACTER SET \\w+", "")
                .replaceAll("(?i)COLLATE \\w+", "")
                .replaceAll("(?i)(KEY\\s+)`([^`]+)`", "$1`" + table + "_$2`")
                .replaceAll("(?i)\\bJSON\\b", "LONGTEXT"));
        var additions = Pattern.compile("CALL add_col_if_absent\\('" + table
                + "', '([^']+)', '((?:''|[^'])*)'\\);").matcher(baseline);
        while (additions.find()) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS `" + additions.group(1) + "` "
                    + additions.group(2).replace("''", "'").replaceAll("(?i) AFTER `[^`]+`", ""));
        }
    }
}
