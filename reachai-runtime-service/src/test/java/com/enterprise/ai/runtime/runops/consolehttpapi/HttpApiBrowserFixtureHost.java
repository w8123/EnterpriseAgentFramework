package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAcceptanceMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiCatalogService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiContractCanonicalizer;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryMemberEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryMemberMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryStateEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryStateMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryStateService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceKind;
import com.enterprise.ai.capability.catalog.scan.CapabilityScannerClient;
import com.enterprise.ai.capability.catalog.scan.ControllerScanHttpApiIntakeService;
import com.enterprise.ai.capability.catalog.scan.OpenApiScanHttpApiIntakeService;
import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.capability.HttpApiFirstCallPolicy;
import com.enterprise.ai.control.capability.CapabilityReviewGateway;
import com.enterprise.ai.control.capability.HttpApiConsoleController;
import com.enterprise.ai.control.capability.RuntimeHttpApiConsoleGateway;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialCipher;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialMapper;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialRequest;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationMapper;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceQueryService;
import com.enterprise.ai.text.tooling.scanner.manifest.HttpApiOperation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.controller.ControllerAnnotationToolManifestScanner;
import com.enterprise.ai.text.tooling.scanner.openapi.OpenApiToolManifestScanner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Isolated BMAPI-3B1/3B2 browser bridge. Real source scanner, Capability H2 owner,
 * Control controller, Runtime H2 connection/vault/attempt/Run/Trace, and loopback HTTP
 * client are exercised. Gateway and login/network-service boundaries remain in-process
 * adapters, so this is not independently deployed multi-service HMAC evidence.
 */
public final class HttpApiBrowserFixtureHost {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final long PROJECT_ID = 41L;
    private static final String PROJECT_CODE = "orders";
    private static final String SECRET = "isolated-browser-key";
    private HttpApiBrowserFixtureHost() { }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 19623;
        boolean controllerSource = args.length > 1 && "controller".equalsIgnoreCase(args[1]);
        Fixture fixture = new Fixture(port, controllerSource);
        Runtime.getRuntime().addShutdownHook(new Thread(fixture::close, "bmapi-3b1-fixture-close"));
        fixture.start();
        System.out.println("BMAPI_FIXTURE_READY port=" + port + " source="
                + (controllerSource ? "CONTROLLER_SCAN" : "OPENAPI_SCAN") + " apiId=" + fixture.apiId);
        Thread.currentThread().join();
    }

    private static final class Fixture implements AutoCloseable {
        private final RuntimeQueryTestDatabase db;
        private final HttpApiCatalogService catalog;
        private final HttpApiAssetService assetService;
        private final HttpApiSourceBindingMapper sourceBindings;
        private final RuntimeWorkflowCredentialService credentials;
        private final HttpApiConsoleController control;
        private final RuntimeHttpApiInvocationService invocations;
        private final RuntimeRunOpsQueryService runOps;
        private final HttpServer upstream;
        private final HttpServer publicServer;
        private final boolean controllerSource;
        private final ControllerOrderFixtureController sourceController;
        private final MockMvc sourceMvc;
        private final MockHttpServletRequest request = new MockHttpServletRequest();
        private final TransactionTemplate tx;
        private final AtomicInteger upstreamCount = new AtomicInteger();
        private final AtomicInteger publicPostCount = new AtomicInteger();
        private final AtomicBoolean lastCredentialMatched = new AtomicBoolean();
        private final AtomicReference<String> identity = new AtomicReference<>("fixture");
        private volatile String lastRoute = "";
        private volatile String mode = "success";
        private final long apiId;
        private String sourceKey;
        private String sourceLocation;
        private String sourceRevision;

        Fixture(int port, boolean controllerSource) throws Exception {
            this.controllerSource = controllerSource;
            sourceController = controllerSource ? new ControllerOrderFixtureController() : null;
            sourceMvc = controllerSource ? MockMvcBuilders.standaloneSetup(sourceController).build() : null;
            db = new RuntimeQueryTestDatabase(List.of("capability_scan_project", "capability_http_api_asset",
                    "capability_http_api_source_binding", "capability_http_api_inventory_state",
                    "capability_http_api_inventory_member", "capability_http_api_acceptance",
                    "runtime_agent_workflow_credential", "runtime_http_api_connection", "runtime_run",
                    "runtime_trace_span", "runtime_tool_call_log", "runtime_guard_decision_log",
                    "runtime_console_capability_invocation"),
                    ScanProjectMapper.class, HttpApiAssetMapper.class, HttpApiSourceBindingMapper.class,
                    HttpApiInventoryStateMapper.class, HttpApiInventoryMemberMapper.class,
                    HttpApiAcceptanceMapper.class, RuntimeWorkflowCredentialMapper.class,
                    RuntimeHttpApiConnectionMapper.class, RuntimeRunMapper.class,
                    RuntimeTraceSpanMapper.class, RuntimeToolCallLogMapper.class,
                    RuntimeGuardDecisionLogMapper.class, ConsoleCapabilityInvocationMapper.class);
            tx = new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()));
            db.jdbc().update("INSERT INTO capability_scan_project "
                    + "(id,name,project_code,environment,base_url,scan_path,scan_type,status) "
                    + "VALUES (41,'Orders','orders','dev','https://not-an-execution-origin.invalid','.', ?, 'scanned')",
                    controllerSource ? "controller" : "openapi");
            HttpApiAssetMapper assets = db.mapper(HttpApiAssetMapper.class);
            HttpApiSourceBindingMapper bindings = db.mapper(HttpApiSourceBindingMapper.class);
            assetService = new HttpApiAssetService(assets, bindings,
                    new HttpApiContractCanonicalizer(JSON));
            sourceBindings = bindings;
            catalog = new HttpApiCatalogService(assets, bindings, db.mapper(HttpApiInventoryStateMapper.class),
                    db.mapper(HttpApiInventoryMemberMapper.class), db.mapper(HttpApiAcceptanceMapper.class),
                    db.mapper(ScanProjectMapper.class), JSON);
            apiId = controllerSource ? importController(assetService, bindings)
                    : importOpenApi(assetService, bindings);
            credentials = new RuntimeWorkflowCredentialService(db.mapper(RuntimeWorkflowCredentialMapper.class),
                    new RuntimeWorkflowCredentialCipher("isolated-bmapi-3b1-browser-secret"), JSON);
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.createContext("/orders/", this::handleUpstream);
            upstream.start();

            RuntimeCapabilityCatalogClient owner = mock(RuntimeCapabilityCatalogClient.class);
            when(owner.getHttpApiExecutionContext(eq(apiId), eq(PROJECT_CODE))).thenAnswer(call -> executionContext());
            WorkflowHttpEgressPolicy egress = WorkflowHttpEgressPolicy.permissiveForTests();
            RuntimeHttpApiConnectionService connections = new RuntimeHttpApiConnectionService(
                    db.mapper(RuntimeHttpApiConnectionMapper.class), owner, credentials, egress);
            invocations = new RuntimeHttpApiInvocationService(db.mapper(ConsoleCapabilityInvocationMapper.class),
                    db.mapper(RuntimeRunMapper.class),
                    new RuntimeTraceRootService(db.mapper(RuntimeTraceSpanMapper.class), JSON),
                    connections, new WorkflowHttpClient(JSON, egress, credentials), JSON,
                    new DataSourceTransactionManager(db.jdbc().getDataSource()));
            runOps = new RuntimeRunOpsQueryService(db.mapper(RuntimeRunMapper.class),
                    new RuntimeTraceQueryService(db.mapper(RuntimeToolCallLogMapper.class),
                            db.mapper(RuntimeTraceSpanMapper.class), JSON),
                    db.mapper(RuntimeGuardDecisionLogMapper.class), JSON);
            control = controller(connections, invocations);
            publicServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            publicServer.createContext("/api", this::handlePublic);
        }

        private long importOpenApi(HttpApiAssetService assetService, HttpApiSourceBindingMapper bindings) {
            Path root = Path.of("src/test/resources/bmapi3b1");
            var manifest = new OpenApiToolManifestScanner().scan(root, root.resolve("orders.yaml"),
                    new ProjectMetadata(PROJECT_CODE, "https://not-an-execution-origin.invalid", ""), null, null);
            if (!manifest.httpApiInventoryComplete() || manifest.httpApis().size() != 1) {
                throw new IllegalStateException("OpenAPI fixture must yield exactly one complete API");
            }
            HttpApiOperation operation = manifest.httpApis().get(0);
            recordSource(operation);
            ScanProjectEntity project = new ScanProjectEntity();
            project.setId(PROJECT_ID); project.setProjectCode(PROJECT_CODE);
            project.setEnvironment("dev"); project.setContextPath("");
            var intake = new OpenApiScanHttpApiIntakeService(assetService, bindings,
                    new HttpApiInventoryStateService(db.mapper(HttpApiInventoryStateMapper.class),
                            db.mapper(HttpApiInventoryMemberMapper.class)));
            var plan = intake.prepare(project, List.of(scannerData(operation)), true);
            var observed = tx.execute(status -> intake.observe(plan));
            if (observed == null || !observed.supported() || !observed.inventoryComplete()
                    || observed.observed() != 1) {
                throw new IllegalStateException("OpenAPI fixture did not observe a complete inventory");
            }
            HttpApiAssetEntity asset = db.mapper(HttpApiAssetMapper.class).selectList(null).get(0);
            HttpApiSourceBindingEntity binding = bindings.selectList(null).get(0);
            HttpApiInventoryStateEntity state = db.mapper(HttpApiInventoryStateMapper.class).selectList(null).get(0);
            HttpApiInventoryMemberEntity member = db.mapper(HttpApiInventoryMemberMapper.class).selectList(null).get(0);
            if (!Boolean.TRUE.equals(state.getSupported()) || !Boolean.TRUE.equals(state.getComplete())
                    || !Objects.equals(state.getInventoryToken(),
                    member.getInventoryToken()) || !Objects.equals(member.getBindingId(), binding.getId())) {
                throw new IllegalStateException("OpenAPI fixture inventory ledger was not persisted by observe");
            }
            return asset.getId();
        }

        private long importController(HttpApiAssetService assetService, HttpApiSourceBindingMapper bindings) {
            HttpApiOperation operation = scanController();
            recordSource(operation);
            ScanProjectEntity project = new ScanProjectEntity();
            project.setId(PROJECT_ID); project.setProjectCode(PROJECT_CODE);
            project.setEnvironment("dev"); project.setContextPath("");
            var intake = new ControllerScanHttpApiIntakeService(assetService, bindings,
                    new HttpApiInventoryStateService(db.mapper(HttpApiInventoryStateMapper.class),
                            db.mapper(HttpApiInventoryMemberMapper.class)));
            var plan = intake.prepare(project, List.of(scannerData(operation)), true);
            var observed = tx.execute(status -> intake.observe(plan));
            if (observed == null || !observed.supported() || !observed.inventoryComplete()
                    || observed.observed() != 1) {
                throw new IllegalStateException("Controller fixture did not observe a complete inventory");
            }
            HttpApiAssetEntity asset = db.mapper(HttpApiAssetMapper.class).selectList(null).get(0);
            HttpApiSourceBindingEntity binding = bindings.selectList(null).get(0);
            HttpApiInventoryStateEntity state = db.mapper(HttpApiInventoryStateMapper.class).selectList(null).get(0);
            HttpApiInventoryMemberEntity member = db.mapper(HttpApiInventoryMemberMapper.class).selectList(null).get(0);
            if (!Boolean.TRUE.equals(state.getSupported()) || !Boolean.TRUE.equals(state.getComplete())
                    || !Objects.equals(state.getInventoryToken(), member.getInventoryToken())
                    || !Objects.equals(member.getBindingId(), binding.getId())) {
                throw new IllegalStateException("Controller fixture inventory ledger was not persisted by observe");
            }
            return asset.getId();
        }

        private HttpApiOperation scanController() {
            Path source = Path.of("src/test/java/com/enterprise/ai/runtime/runops/consolehttpapi/"
                    + "ControllerOrderFixtureController.java");
            var manifest = new ControllerAnnotationToolManifestScanner().scan(source,
                    new ProjectMetadata(PROJECT_CODE, "https://not-an-execution-origin.invalid", ""));
            if (!manifest.httpApiInventoryComplete() || manifest.httpApis().size() != 1) {
                throw new IllegalStateException("Controller fixture must yield exactly one complete API");
            }
            return manifest.httpApis().get(0);
        }

        private void recordSource(HttpApiOperation operation) {
            sourceKey = operation.sourceKey();
            sourceLocation = operation.sourceLocation();
            sourceRevision = operation.sourceRevision();
        }

        private HttpApiConsoleContracts.ExecutionContext executionContext() {
            var detail = catalog.detail(apiId);
            var summary = detail.summary();
            String unsupported = HttpApiFirstCallPolicy.unsupportedReason(detail.acceptedContract());
            return new HttpApiConsoleContracts.ExecutionContext(1, apiId, summary.qualifiedName(),
                    summary.projectId(), summary.projectCode(), summary.environment(),
                    summary.httpMethod(), summary.routeTemplate(), summary.sourceConfirmed(),
                    summary.sourceStatus(), summary.sourceReason(), summary.candidateContractHash(),
                    summary.acceptedContractHash(), summary.sourceSetRevision(), detail.acceptedContract(),
                    unsupported == null, unsupported);
        }

        private HttpApiConsoleController controller(RuntimeHttpApiConnectionService connections,
                                                    RuntimeHttpApiInvocationService invocations) {
            CapabilityReviewGateway capability = mock(CapabilityReviewGateway.class);
            when(capability.getProjectById(eq(PROJECT_ID), anyString())).thenReturn(ResponseEntity.ok(
                    Map.of("projectId", PROJECT_ID, "projectCode", PROJECT_CODE)));
            when(capability.listHttpApis(eq(PROJECT_ID), any(), any(), any(), any(), anyInt(), anyInt(), anyString()))
                    .thenAnswer(call -> ResponseEntity.ok((Object) catalog.list(PROJECT_ID, call.getArgument(1),
                            call.getArgument(2), call.getArgument(3), call.getArgument(4),
                            call.getArgument(5), call.getArgument(6))));
            when(capability.getHttpApi(eq(apiId), anyString())).thenAnswer(call -> detailResponse());
            when(capability.acceptHttpApi(eq(apiId), anyString(), anyString())).thenAnswer(call -> {
                var detail = tx.execute(status -> catalog.accept(apiId, call.getArgument(1), call.getArgument(2)));
                return ResponseEntity.ok((Object) wire(detail));
            });
            RuntimeHttpApiConsoleGateway runtime = mock(RuntimeHttpApiConsoleGateway.class);
            RuntimeHttpApiCatalogStateService catalogStates = new RuntimeHttpApiCatalogStateService(
                    db.mapper(RuntimeHttpApiConnectionMapper.class),
                    db.mapper(ConsoleCapabilityInvocationMapper.class));
            when(runtime.catalogStates(any(), anyString())).thenAnswer(call ->
                    ResponseEntity.ok((Object) catalogStates.read(call.getArgument(0), call.getArgument(1))));
            when(runtime.readConnection(any(), anyString())).thenAnswer(call ->
                    ResponseEntity.ok((Object) connections.read(call.getArgument(0))));
            when(runtime.saveConnection(any(), anyString())).thenAnswer(call -> {
                try { return ResponseEntity.ok((Object) connections.save(call.getArgument(0), call.getArgument(1))); }
                catch (RuntimeHttpApiConnectionService.Conflict conflict) {
                    return ResponseEntity.status(409).body(Map.of("code", conflict.code()));
                } catch (IllegalArgumentException invalid) {
                    return ResponseEntity.badRequest().body(Map.of("code", "HTTP_API_CONNECTION_INVALID"));
                }
            });
            when(runtime.invoke(any(), anyString())).thenAnswer(call -> {
                try { return ResponseEntity.ok((Object) invocations.invoke(call.getArgument(0))); }
                catch (RuntimeHttpApiInvocationService.Conflict conflict) {
                    return ResponseEntity.status(409).body(Map.of("code", conflict.code()));
                } catch (RuntimeHttpApiConnectionService.Conflict conflict) {
                    return ResponseEntity.status(409).body(Map.of("code", conflict.code()));
                } catch (IllegalArgumentException invalid) {
                    return ResponseEntity.badRequest().body(Map.of("code", "HTTP_API_INVOCATION_INVALID"));
                }
            });
            when(runtime.getInvocation(anyString(), eq(PROJECT_CODE), anyString())).thenAnswer(call -> {
                var result = invocations.get(call.getArgument(0), call.getArgument(2));
                return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok((Object) result);
            });
            PlatformRequestAuthorization auth = mock(PlatformRequestAuthorization.class);
            when(auth.requirePermission(any(), anyString())).thenAnswer(call -> {
                PlatformAuthenticatedSession session = actorSession();
                if (!session.permissions().contains(call.getArgument(1))) {
                    throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
                }
                return session;
            });
            when(auth.requireResourcePermission(any(), anyString(), eq("PROJECT"),
                    nullable(String.class), anyString())).thenAnswer(call -> {
                PlatformAuthenticatedSession session = actorSession();
                if (!session.hasResourcePermission(call.getArgument(1), "PROJECT", call.getArgument(3),
                        call.getArgument(4))) {
                    throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
                }
                return session;
            });
            ControlToolAclDecisionService acl = mock(ControlToolAclDecisionService.class);
            when(acl.decide(any(), eq(PROJECT_ID), eq(PROJECT_CODE), eq("TOOL"), anyString()))
                    .thenAnswer(call -> "no-acl".equals(identity.get())
                            ? "DENY" : ControlToolAclDecisionService.DECISION_ALLOW);
            return new HttpApiConsoleController(capability, runtime, auth, acl, JSON);
        }

        private ResponseEntity<Object> detailResponse() {
            var detail = catalog.detail(apiId);
            return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok((Object) wire(detail));
        }

        private Map<String, Object> wire(Object value) {
            return JSON.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
        }

        private void handlePublic(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                if ("GET".equals(method) && "/api/platform/auth/me".equals(path)) {
                    write(exchange, 200, sessionView()); return;
                }
                if ("GET".equals(method) && "/api/scan-projects".equals(path)) {
                    write(exchange, 200, hasProjectRead() ? List.of(projectView()) : List.of()); return;
                }
                String scanBase = "/api/scan-projects/" + PROJECT_ID;
                if (path.equals(scanBase) || path.startsWith(scanBase + "/")) {
                    if (!hasProjectRead()) {
                        write(exchange, 403, Map.of("code", "FIXTURE_PROJECT_DENIED")); return;
                    }
                    if ("GET".equals(method) && path.equals(scanBase)) {
                        write(exchange, 200, projectView()); return;
                    }
                    if ("GET".equals(method) && (path.equals(scanBase + "/tools")
                            || path.equals(scanBase + "/modules")
                            || path.equals(scanBase + "/semantic-docs"))) {
                        write(exchange, 200, List.of()); return;
                    }
                    if ("GET".equals(method) && (path.equals(scanBase + "/semantic/status")
                            || path.equals(scanBase + "/sensitive-data/status"))) {
                        write(exchange, 200, null); return;
                    }
                    if ("POST".equals(method) && controllerSource && path.equals(scanBase + "/rescan")) {
                        write(exchange, 200, rescanController()); return;
                    }
                }
                if ("POST".equals(method) && controllerSource && path.startsWith("/api/fixture/source/")) {
                    write(exchange, 200, changeControllerSource(path.substring("/api/fixture/source/".length())));
                    return;
                }
                if ("GET".equals(method) && "/api/apis".equals(path)) {
                    Map<String, String> query = query(exchange);
                    writeController(exchange, control.list(request, PROJECT_ID, query.get("environment"),
                            query.get("keyword"), query.get("method"), query.get("sourceStatus"),
                            integer(query.get("current"), 1), integer(query.get("size"), 20))); return;
                }
                String base = "/api/apis/" + apiId;
                if ("GET".equals(method) && base.equals(path)) {
                    writeController(exchange, control.detail(request, apiId)); return;
                }
                if ("POST".equals(method) && (base + "/accept").equals(path)) {
                    writeController(exchange, control.accept(request, apiId, body(exchange))); return;
                }
                if ("GET".equals(method) && (base + "/connection").equals(path)) {
                    writeController(exchange, control.connection(request, apiId)); return;
                }
                if ("PUT".equals(method) && (base + "/connection").equals(path)) {
                    writeController(exchange, control.saveConnection(request, apiId, body(exchange))); return;
                }
                if ("POST".equals(method) && (base + "/invocations").equals(path)) {
                    publicPostCount.incrementAndGet();
                    ResponseEntity<Object> response = control.invoke(request, apiId, body(exchange));
                    if ("lost-response".equals(mode)) { exchange.close(); return; }
                    writeController(exchange, response); return;
                }
                if ("GET".equals(method) && path.startsWith("/api/api-invocations/")) {
                    String id = path.substring("/api/api-invocations/".length());
                    writeController(exchange, control.getInvocation(request, id, query(exchange).get("projectCode")));
                    return;
                }
                if ("GET".equals(method) && path.startsWith("/api/runops/traces/")) {
                    PlatformAuthenticatedSession session = actorSession();
                    if (!session.permissions().contains(PlatformPermissions.RUNOPS_READ)) {
                        write(exchange, 403, Map.of("code", "RUNOPS_READ_DENIED")); return;
                    }
                    String traceId = path.substring("/api/runops/traces/".length());
                    try {
                        var detail = runOps.detail(traceId);
                        if (!session.hasResourcePermission(PlatformPermissions.RUNOPS_READ,
                                "PROJECT", null, detail.summary().projectCode())) {
                            write(exchange, 403, Map.of("code", "RUNOPS_PROJECT_DENIED")); return;
                        }
                        write(exchange, 200, detail); return;
                    } catch (IllegalArgumentException missing) {
                        write(exchange, 404, Map.of("code", "RUNOPS_NOT_FOUND")); return;
                    }
                }
                if ("GET".equals(method) && "/api/workflows/credentials".equals(path)) {
                    write(exchange, 200, credentials.list(PROJECT_ID, PROJECT_CODE)); return;
                }
                if ("POST".equals(method) && "/api/workflows/credentials".equals(path)) {
                    RuntimeWorkflowCredentialRequest input = JSON.convertValue(body(exchange),
                            RuntimeWorkflowCredentialRequest.class);
                    if (!Objects.equals(input.projectId(), PROJECT_ID) || !PROJECT_CODE.equals(input.projectCode())
                            || !"PROJECT".equals(input.scope())) {
                        write(exchange, 403, Map.of("code", "FIXTURE_PROJECT_DENIED")); return;
                    }
                    write(exchange, 200, credentials.create(input)); return;
                }
                if ("GET".equals(method) && "/api/fixture/evidence".equals(path)) {
                    write(exchange, 200, evidence()); return;
                }
                if ("POST".equals(method) && path.startsWith("/api/fixture/mode/")) {
                    mode = path.substring("/api/fixture/mode/".length());
                    write(exchange, 200, Map.of("mode", mode)); return;
                }
                if ("POST".equals(method) && path.startsWith("/api/fixture/identity/")) {
                    String next = path.substring("/api/fixture/identity/".length());
                    if (!List.of("fixture", "outsider", "no-runops", "no-acl").contains(next)) {
                        write(exchange, 400, Map.of("code", "FIXTURE_IDENTITY_INVALID")); return;
                    }
                    identity.set(next);
                    write(exchange, 200, sessionView()); return;
                }
                write(exchange, 404, Map.of("code", "FIXTURE_NOT_FOUND"));
            } catch (ResponseStatusException denied) {
                write(exchange, denied.getStatusCode().value(), Map.of("code", "FIXTURE_PERMISSION_DENIED"));
            } catch (Exception failure) {
                write(exchange, 500, Map.of("code", "FIXTURE_ERROR", "type", failure.getClass().getSimpleName()));
            }
        }

        private Map<String, Object> evidence() {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("apiId", apiId); facts.put("sourceKind", controllerSource
                    ? HttpApiSourceKind.CONTROLLER_SCAN.name() : HttpApiSourceKind.OPENAPI_SCAN.name());
            facts.put("sourceKey", sourceKey); facts.put("sourceLocation", sourceLocation);
            facts.put("sourceRevision", sourceRevision);
            facts.put("upstreamOrigin", "http://127.0.0.1:" + upstream.getAddress().getPort());
            facts.put("inventoryStateCount", db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM capability_http_api_inventory_state", Integer.class));
            facts.put("inventoryMemberCount", db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM capability_http_api_inventory_member", Integer.class));
            HttpApiInventoryStateEntity state = db.mapper(HttpApiInventoryStateMapper.class).selectList(null).get(0);
            HttpApiInventoryMemberEntity member = db.mapper(HttpApiInventoryMemberMapper.class).selectList(null).get(0);
            facts.put("inventoryTokenMatched", Objects.equals(state.getInventoryToken(), member.getInventoryToken()));
            var summary = catalog.detail(apiId).summary();
            facts.put("sourceConfirmed", summary.sourceConfirmed());
            facts.put("sourceStatus", summary.sourceStatus());
            facts.put("candidateContractHash", summary.candidateContractHash());
            facts.put("acceptedContractHash", summary.acceptedContractHash());
            facts.put("sourceSetRevision", summary.sourceSetRevision());
            facts.put("acceptedCount", db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM capability_http_api_acceptance", Integer.class));
            facts.put("credentialCount", db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM runtime_agent_workflow_credential", Integer.class));
            facts.put("connectionCount", db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM runtime_http_api_connection", Integer.class));
            facts.put("invocationCount", db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM runtime_console_capability_invocation WHERE target_type='HTTP_API'", Integer.class));
            facts.put("runCount", db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM runtime_run WHERE run_type='CONSOLE_HTTP_API'", Integer.class));
            facts.put("traceCount", db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM runtime_trace_span WHERE runtime_type='HTTP_API'", Integer.class));
            facts.put("upstreamCount", upstreamCount.get()); facts.put("publicPostCount", publicPostCount.get());
            facts.put("lastRoute", lastRoute); facts.put("credentialMatched", lastCredentialMatched.get());
            facts.put("controllerMethodCalls", sourceController == null ? 0 : sourceController.methodCalls());
            facts.put("mode", mode);
            facts.put("identity", identity.get());
            return facts;
        }

        private void handleUpstream(HttpExchange exchange) throws IOException {
            upstreamCount.incrementAndGet();
            lastRoute = exchange.getRequestURI().toString();
            lastCredentialMatched.set(controllerSource
                    ? exchange.getRequestHeaders().getFirst("X-API-Key") == null
                            && exchange.getRequestHeaders().getFirst("Authorization") == null
                    : SECRET.equals(exchange.getRequestHeaders().getFirst("X-API-Key")));
            if ("close".equals(mode)) { exchange.close(); return; }
            int status = "http401".equals(mode) ? 401 : 200;
            if (controllerSource && status == 200) {
                try {
                    var response = sourceMvc.perform(MockMvcRequestBuilders
                            .get(exchange.getRequestURI().toString())).andReturn().getResponse();
                    byte[] responseBody = response.getContentAsByteArray();
                    exchange.getResponseHeaders().set("Content-Type", response.getContentType() == null
                            ? "application/json" : response.getContentType());
                    exchange.sendResponseHeaders(response.getStatus(), responseBody.length);
                    try (var output = exchange.getResponseBody()) { output.write(responseBody); }
                } catch (Exception failure) {
                    write(exchange, 500, Map.of("code", "FIXTURE_MVC_DISPATCH_FAILED",
                            "type", failure.getClass().getSimpleName()));
                }
                return;
            }
            String orderId = URLDecoder.decode(exchange.getRequestURI().getRawPath()
                    .substring("/orders/".length()), StandardCharsets.UTF_8);
            byte[] body = JSON.writeValueAsBytes(status == 401
                    ? Map.of("error", "unauthorized")
                    : Map.of("orderId", orderId, "state", "PAID", "apiKey", SECRET));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        }

        private Map<String, Object> body(HttpExchange exchange) throws IOException {
            return JSON.readValue(exchange.getRequestBody().readAllBytes(), new TypeReference<>() { });
        }

        private Map<String, String> query(HttpExchange exchange) {
            Map<String, String> result = new LinkedHashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw == null) return result;
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            return result;
        }

        private int integer(String value, int fallback) {
            try { return value == null ? fallback : Integer.parseInt(value); }
            catch (NumberFormatException invalid) { return fallback; }
        }

        private Map<String, Object> sessionView() {
            PlatformAuthenticatedSession session = actorSession();
            List<Map<String, String>> grants = session.permissionGrants().stream().map(grant -> Map.of(
                    "permissionCode", grant.permissionCode(), "scopeType", grant.scopeType(),
                    "scopeValue", grant.scopeValue())).toList();
            return Map.of("sessionId", session.sessionId(), "expiresAt", "2030-01-01T00:00:00",
                    "principal", Map.of("userId", session.user().getId(), "username", session.user().getUsername(),
                            "displayName", session.user().getDisplayName(),
                            "permissions", session.permissions(), "permissionGrants", grants));
        }

        private PlatformAuthenticatedSession actorSession() {
            String selected = identity.get();
            boolean outsider = "outsider".equals(selected);
            boolean noRunOps = "no-runops".equals(selected);
            List<String> permissions = outsider ? List.of(PlatformPermissions.PLATFORM_READ) : noRunOps
                    ? List.of(PlatformPermissions.PLATFORM_READ, PlatformPermissions.PLATFORM_WRITE,
                            PlatformPermissions.CAPABILITY_INVOKE, PlatformPermissions.WORKFLOW_CREDENTIAL_MANAGE)
                    : List.of(PlatformPermissions.PLATFORM_READ, PlatformPermissions.PLATFORM_WRITE,
                            PlatformPermissions.CAPABILITY_INVOKE, PlatformPermissions.WORKFLOW_CREDENTIAL_MANAGE,
                            PlatformPermissions.RUNOPS_READ);
            List<PlatformPermissionGrant> grants = outsider ? List.of() : permissions.stream()
                    .map(permission -> new PlatformPermissionGrant(permission, "PROJECT", PROJECT_CODE)).toList();
            long userId = outsider ? 43L : noRunOps ? 44L : 42L;
            return new PlatformAuthenticatedSession(new PlatformPrincipal(userId, selected, selected),
                    "fixture-session-" + selected, LocalDateTime.now().plusHours(1), List.of("OPERATOR"),
                    permissions, grants);
        }

        private boolean hasProjectRead() {
            return actorSession().hasResourcePermission(PlatformPermissions.PLATFORM_READ,
                    "PROJECT", null, PROJECT_CODE);
        }

        private Map<String, Object> rescanController() {
            HttpApiOperation operation = scanController();
            var summary = observeController(List.of(scannerData(operation)), true);
            recordSource(operation);
            return observationView(summary);
        }

        /** Fixture-only negative states; the success path always scans the unchanged Java source. */
        private Map<String, Object> changeControllerSource(String state) {
            HttpApiOperation operation = scanController();
            CapabilityScannerClient.HttpApiData data = scannerData(operation);
            if ("partial".equals(state)) {
                return observationView(observeController(List.of(), false));
            }
            if ("drift".equals(state)) {
                var response = data.responses().get(0);
                ObjectNode schema = response.schema().deepCopy();
                ((ObjectNode) schema.path("properties")).putObject("newVersion").put("type", "string");
                data = new CapabilityScannerClient.HttpApiData(data.sourceKey(), data.sourceLocation(),
                        data.sourceRevision() + "-synthetic-drift", data.httpMethod(), data.contextPath(),
                        data.endpointPath(), data.consumes(), data.produces(), data.mappingConditions(),
                        data.parameters(), data.requestBody(), List.of(new CapabilityScannerClient.HttpApiResponseData(
                                response.status(), schema, response.contentTypes())), data.authenticationState(),
                        data.authenticationSchemes(), data.requiredHeaderNames(), data.sideEffect());
                return observationView(observeController(List.of(data), true));
            }
            if ("restore".equals(state)) {
                return rescanController();
            }
            throw new IllegalArgumentException("Unknown fixture source state");
        }

        private ControllerScanHttpApiIntakeService.Summary observeController(
                List<CapabilityScannerClient.HttpApiData> data, boolean complete) {
            ScanProjectEntity project = new ScanProjectEntity();
            project.setId(PROJECT_ID); project.setProjectCode(PROJECT_CODE);
            project.setEnvironment("dev"); project.setContextPath("");
            var intake = new ControllerScanHttpApiIntakeService(assetService, sourceBindings,
                    new HttpApiInventoryStateService(db.mapper(HttpApiInventoryStateMapper.class),
                            db.mapper(HttpApiInventoryMemberMapper.class)));
            return tx.execute(status -> intake.observe(intake.prepare(project, data, complete)));
        }

        private Map<String, Object> observationView(ControllerScanHttpApiIntakeService.Summary summary) {
            return Map.of("supported", summary != null && summary.supported(),
                    "inventoryComplete", summary != null && summary.inventoryComplete(),
                    "observed", summary == null ? 0 : summary.observed(),
                    "removed", summary == null ? 0 : summary.removed());
        }

        private Map<String, Object> projectView() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", PROJECT_ID); view.put("name", "订单项目"); view.put("projectCode", PROJECT_CODE);
            view.put("projectKind", "SCAN"); view.put("environment", "dev"); view.put("owner", "fixture");
            view.put("visibility", "PROJECT"); view.put("baseUrl", "https://not-an-execution-origin.invalid");
            view.put("contextPath", ""); view.put("scanPath", controllerSource
                    ? "ControllerOrderFixtureController.java" : "orders.yaml");
            view.put("scanType", controllerSource ? "controller" : "openapi");
            view.put("toolCount", 1); view.put("status", "scanned");
            return view;
        }

        private void writeController(HttpExchange exchange, ResponseEntity<Object> response) throws IOException {
            write(exchange, response.getStatusCode().value(), response.getBody());
        }

        private void write(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
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

        void start() { publicServer.start(); }
        @Override public void close() { publicServer.stop(0); upstream.stop(0); db.close(); }
    }
}
