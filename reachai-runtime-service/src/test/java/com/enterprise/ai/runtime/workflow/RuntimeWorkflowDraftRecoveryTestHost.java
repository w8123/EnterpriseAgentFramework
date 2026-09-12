package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelCatalogClient;
import com.enterprise.ai.runtime.internal.RuntimePageWorkbenchInternalController;
import com.enterprise.ai.runtime.internal.RuntimePageWorkbenchPublishedQueryService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceQueryService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.common.testing.ClonedDevelopmentMysqlDatabase;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import com.enterprise.ai.runtime.workflow.layout.RuntimeWorkflowCanvasLayoutService;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.mockito.Mockito.mock;

/** A separate Runtime JVM with real MVC, owning writers and isolated H2 or opt-in development MySQL. */
public final class RuntimeWorkflowDraftRecoveryTestHost {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("One owned readiness file is required");
        Path ready = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(ready.getParent()) || Files.exists(ready)) throw new IllegalArgumentException("Readiness file must be new");
        ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();
        json.findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var fault = new AtomicReference<String>();
        var tables = List.of("runtime_workflow", "runtime_workflow_version",
                "runtime_workflow_draft_submission", "runtime_workflow_resource_binding", "runtime_run",
                "runtime_guard_decision_log", "runtime_trace_span", "runtime_tool_call_log", "runtime_workflow_capability_reference");
        Class<?>[] mappers = {
                RuntimeWorkflowDefinitionMapper.class, RuntimeWorkflowVersionMapper.class, RuntimeWorkflowDraftSubmissionMapper.class,
                RuntimeWorkflowResourceBindingMapper.class, RuntimeWorkflowReferenceMapper.class,
                RuntimeRunMapper.class, RuntimeGuardDecisionLogMapper.class, RuntimeTraceSpanMapper.class, RuntimeToolCallLogMapper.class};
        try (var mysql = Boolean.getBoolean("reachai.mysql.workflowRecoveryVerification")
                ? new ClonedDevelopmentMysqlDatabase("reachai.mysql.workflowRecoveryVerification", "audit_draft_http_runtime", "runtime_", tables) : null;
             var db = mysql == null ? new RuntimeQueryTestDatabase(tables.subList(0, tables.size() - 1), mappers)
                     : new RuntimeQueryTestDatabase(mysql, mappers);
             var context = new AnnotationConfigServletWebServerApplicationContext()) {
            if (mysql == null) {
                // H2 has no prefix-index syntax; MySQL keeps the actual cloned index without this translation.
                var references = Pattern.compile("(?ism)CREATE TABLE IF NOT EXISTS `runtime_workflow_capability_reference`"
                        + "\\s*\\(.*?^\\)\\s*ENGINE=[^\\r\\n]*;").matcher(Files.readString(Path.of("../sql/initV2.sql")));
                if (!references.find()) throw new IllegalStateException("Reference table missing from baseline");
                db.jdbc().execute(references.group().replaceAll("(?im)^\\)\\s*ENGINE=[^\\r\\n]*;", ");")
                        .replaceAll("(?i)COLLATE \\w+", "").replace("`reference_key`(191)", "`reference_key`"));
            }
            context.register(WebConfiguration.class);
            context.registerBean(ObjectMapper.class, () -> json);
            context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(db.jdbc().getDataSource()));
            context.registerBean(TomcatServletWebServerFactory.class, () -> {
                var factory = new TomcatServletWebServerFactory(0);
                try { factory.setAddress(InetAddress.getByName("127.0.0.1")); }
                catch (Exception invalid) { throw new IllegalStateException(invalid); }
                return factory;
            });
            context.registerBean("dispatcherServlet", DispatcherServlet.class, () -> new DispatcherServlet(context));
            context.registerBean(ServletRegistrationBean.class, () -> {
                var registration = new ServletRegistrationBean<>(context.getBean(DispatcherServlet.class), "/");
                registration.setLoadOnStartup(1);
                return registration;
            });
            context.registerBean(FilterRegistrationBean.class, () -> {
                var registration = new FilterRegistrationBean<>(new ResponseFaultFilter(fault, json));
                registration.addUrlPatterns("/*");
                return registration;
            });
            context.registerBean(RuntimeWorkflowResourceBindingService.class,
                    () -> new RuntimeWorkflowResourceBindingService(db.mapper(RuntimeWorkflowResourceBindingMapper.class)));
            context.registerBean(RuntimeWorkflowReferenceIndex.class, () -> new RuntimeWorkflowReferenceIndex(
                    db.mapper(RuntimeWorkflowReferenceMapper.class), db.mapper(RuntimeWorkflowDefinitionMapper.class),
                    db.mapper(RuntimeWorkflowVersionMapper.class), json));
            context.registerBean(RuntimeWorkflowDefinitionService.class, () -> new RuntimeWorkflowDefinitionService(
                    db.mapper(RuntimeWorkflowDefinitionMapper.class), db.mapper(RuntimeWorkflowVersionMapper.class),
                    mock(RuntimeWorkflowDeletionReferences.class), new RuntimeWorkflowDocumentCanonicalizer(json),
                    context.getBean(RuntimeWorkflowResourceBindingService.class), context.getBean(RuntimeWorkflowReferenceIndex.class)));
            context.registerBean(RuntimeWorkflowDraftSubmissionService.class, () -> new RuntimeWorkflowDraftSubmissionService(
                    db.mapper(RuntimeWorkflowDraftSubmissionMapper.class), context.getBean(RuntimeWorkflowDefinitionService.class), json));
            context.registerBean(RuntimeWorkflowReleaseValidationService.class,
                    () -> new RuntimeWorkflowReleaseValidationService(mock(RuntimeControlCatalogClient.class), json,
                            new RuntimeWorkflowNodeCapabilityRegistry(), context.getBean(RuntimeWorkflowResourceBindingService.class)));
            context.registerBean(RuntimeWorkflowVersionService.class, () -> new RuntimeWorkflowVersionService(
                    db.mapper(RuntimeWorkflowVersionMapper.class), context.getBean(RuntimeWorkflowDefinitionService.class),
                    context.getBean(RuntimeWorkflowReleaseValidationService.class), json,
                    mock(RuntimeCapabilityContractPins.class), mock(RuntimeWorkflowReleaseEventMapper.class),
                    context.getBean(RuntimeWorkflowReferenceIndex.class)));
            context.registerBean(RuntimeTraceQueryService.class, () -> new RuntimeTraceQueryService(
                    db.mapper(RuntimeToolCallLogMapper.class), db.mapper(RuntimeTraceSpanMapper.class), json));
            context.registerBean(RuntimeRunOpsQueryService.class, () -> new RuntimeRunOpsQueryService(
                    db.mapper(RuntimeRunMapper.class), context.getBean(RuntimeTraceQueryService.class),
                    db.mapper(RuntimeGuardDecisionLogMapper.class), json));
            context.registerBean(RuntimeWorkflowAiCodingService.class, () -> new RuntimeWorkflowAiCodingService(
                    context.getBean(RuntimeWorkflowDefinitionService.class), context.getBean(RuntimeWorkflowReleaseValidationService.class),
                    mock(RuntimeWorkflowDebugService.class),
                    context.getBean(RuntimeWorkflowVersionService.class), context.getBean(RuntimeRunOpsQueryService.class), json,
                    new RuntimeWorkflowCanvasLayoutService(json), mock(RuntimeWorkflowGraphMutationService.class),
                    mock(RuntimeModelCatalogClient.class), mock(RuntimeCapabilityCatalogClient.class),
                    new RuntimeWorkflowDocumentCanonicalizer(json), context.getBean(RuntimeWorkflowResourceBindingService.class),
                    mock(RuntimeControlCatalogClient.class)));
            context.registerBean(RuntimePageWorkbenchWorkflowDeliveryService.class, () -> new RuntimePageWorkbenchWorkflowDeliveryService(
                    context.getBean(RuntimeWorkflowAiCodingService.class), context.getBean(RuntimeWorkflowDefinitionService.class),
                    context.getBean(RuntimeWorkflowResourceBindingService.class), mock(RuntimeWorkflowVersionService.class),
                    mock(RuntimePageAssistantWorkflowAttachmentService.class), json, context.getBean(RuntimeWorkflowDraftSubmissionService.class)));
            context.registerBean(RuntimePageWorkbenchInternalController.class, () -> new RuntimePageWorkbenchInternalController(
                    mock(RuntimePageWorkbenchPublishedQueryService.class), mock(RuntimePageWorkbenchReleaseReadinessService.class),
                    mock(RuntimePageWorkbenchExecutionReadinessService.class), context.getBean(RuntimePageWorkbenchWorkflowDeliveryService.class),
                    mock(RuntimeWorkflowNodeCapabilityRegistry.class)));
            context.registerBean(ProbeController.class, () -> new ProbeController(db, fault, context.getBean(RuntimeWorkflowDefinitionService.class)));
            RuntimeTraceDraftRecoveryFixture.configure(context, json);
            context.refresh();
            RuntimeTraceDraftRecoveryFixture.seed(context, db, json);
            Files.writeString(ready, json.writeValueAsString(Map.of("pid", ProcessHandle.current().pid(),
                    "url", "http://127.0.0.1:" + context.getWebServer().getPort(), "database", mysql == null ? "H2" : "MYSQL")), StandardCharsets.UTF_8);
            System.out.println("RUNTIME_DRAFT_HOST_READY independentJvm=true realMvc=true realWorkflowWrites=true");
            while (System.in.read() != -1) { /* The owning test closes stdin to stop this host. */ }
        }
        System.out.println("RUNTIME_DRAFT_HOST_STOPPED");
    }

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement
    public static class WebConfiguration implements WebMvcConfigurer {
        private final ObjectMapper json;
        public WebConfiguration(ObjectMapper json) { this.json = json; }
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new MappingJackson2HttpMessageConverter(json));
        }
    }

    @RestController
    public static class ProbeController {
        private final RuntimeQueryTestDatabase db;
        private final AtomicReference<String> fault;
        private final RuntimeWorkflowDefinitionService workflows;
        ProbeController(RuntimeQueryTestDatabase db, AtomicReference<String> fault, RuntimeWorkflowDefinitionService workflows) {
            this.db = db; this.fault = fault; this.workflows = workflows;
        }
        @PostMapping("/probe/fault/{mode}")
        public Map<String, Object> fault(@PathVariable String mode) {
            if (!Set.of("EMPTY", "TRUNCATE", "MALFORMED", "MISSING_ID", "WRONG_TASK", "BEFORE",
                    "TRACE_CREATE", "TRACE_DETAIL", "TRACE_VERSIONS", "TRACE_PREFLIGHT", "TRACE_VALIDATION",
                    "TRACE_READBACK", "TRACE_READBACK_ID").contains(mode)) {
                throw new IllegalArgumentException("Unknown response fault");
            }
            fault.set(mode);
            return Map.of("armed", true);
        }
        @GetMapping("/probe/state/{taskId}")
        public Map<String, Object> state(@PathVariable String taskId) {
            var ids = db.jdbc().queryForList("SELECT DISTINCT workflow_id FROM runtime_workflow_draft_submission WHERE task_id=?", String.class, taskId);
            String id = ids.isEmpty() ? "absent" : ids.get(0);
            return Map.of("workflows", db.jdbc().queryForList("SELECT id,name,updated_at,graph_spec_json FROM runtime_workflow WHERE id=?", id),
                    "receipts", db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_draft_submission WHERE task_id=?", Integer.class, taskId),
                    "bindings", db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_resource_binding WHERE workflow_id=?", Integer.class, id),
                    "references", db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_capability_reference WHERE workflow_id=?", Integer.class, id));
        }
        @PostMapping("/probe/edit/{id}")
        public Map<String, Object> edit(@PathVariable String id, @RequestBody Map<String, String> body) {
            var current = workflows.findById(id).orElseThrow();
            var update = new RuntimeWorkflowDefinitionEntity(); update.setName(body.get("name"));
            var changed = workflows.update(id, update, current.getUpdatedAt().toString());
            return Map.of("id", id, "name", changed.getName(), "revision", changed.getUpdatedAt());
        }
    }

    private static final class ResponseFaultFilter extends OncePerRequestFilter {
        private final AtomicReference<String> next;
        private final ObjectMapper json;
        ResponseFaultFilter(AtomicReference<String> next, ObjectMapper json) { this.next = next; this.json = json; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String uri = request.getRequestURI();
            String armed = next.get();
            boolean traceFault = armed != null && armed.startsWith("TRACE_");
            boolean matches = traceFault ? switch (armed) {
                case "TRACE_CREATE" -> uri.equals("/internal/runtime/runops/workflow-candidates/drafts");
                case "TRACE_DETAIL" -> uri.equals("/api/runops/traces/trace-1");
                case "TRACE_VERSIONS" -> uri.equals("/api/workflows/wf-source/versions");
                case "TRACE_PREFLIGHT" -> uri.equals("/api/workflows/runtime-validation");
                case "TRACE_VALIDATION" -> uri.matches("/api/workflows/[^/]+/ai-coding/validate");
                case "TRACE_READBACK", "TRACE_READBACK_ID" -> uri.matches("/api/workflows/[a-f0-9]{32}");
                default -> false;
            } : uri.endsWith("/workflow-drafts");
            if (!matches) { chain.doFilter(request,response); return; }
            String mode = next.getAndSet(null);
            if (mode == null) { chain.doFilter(request,response); return; }
            if (mode.equals("BEFORE")) { response.sendError(503,"Controlled failure before Runtime application"); return; }
            var cached = new ContentCachingResponseWrapper(response);
            chain.doFilter(request,cached);
            if (cached.getStatus() < 200 || cached.getStatus() >= 300) { cached.copyBodyToResponse(); return; }
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Fault must occur after commit");
            byte[] original = cached.getContentAsByteArray();
            byte[] altered = original;
            if (mode.equals("EMPTY")) altered = new byte[0];
            if (traceFault) altered = mode.equals("TRACE_PREFLIGHT")
                    ? "{\"errors\":[],\"warnings\":[]}".getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (mode.equals("TRACE_READBACK_ID")) {
                ObjectNode value = (ObjectNode) json.readTree(original); value.put("id", "another-workflow");
                altered = json.writeValueAsBytes(value);
            }
            if (mode.equals("MALFORMED")) altered = "{\"workflow\":".getBytes(StandardCharsets.UTF_8);
            if (mode.equals("MISSING_ID") || mode.equals("WRONG_TASK")) {
                ObjectNode value = (ObjectNode) json.readTree(original);
                if (mode.equals("MISSING_ID")) ((ObjectNode)value.get("workflow")).remove("id");
                else value.put("taskId", "another-task");
                altered = json.writeValueAsBytes(value);
            }
            response.reset(); response.setStatus(200); response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8"); response.setHeader("Connection", "close");
            response.setContentLength(mode.equals("TRUNCATE") ? original.length : altered.length);
            if (mode.equals("TRUNCATE")) response.getOutputStream().write(original,0,Math.min(8,original.length));
            else response.getOutputStream().write(altered);
            response.flushBuffer();
            response.getOutputStream().close();
            System.out.println("RUNTIME_DRAFT_RESPONSE_FAULT mode="+mode+" transactionCompleted=true");
        }
    }
}
