package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigDraftRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftEditCommand;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftVersionCommand;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowToolCatalogQuery;
import com.enterprise.ai.runtime.internalauth.*;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.runtime.RuntimeAgentDraftGateway;
import com.enterprise.ai.control.aiassist.ControlAiCodingAgentDraftGateway;
import com.enterprise.ai.control.aiassist.VerifiedAiCodingProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real H2 transactions exercise the shared Agent lock and persisted draft contents. */
class RuntimeAgentDraftEditPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private RuntimeAgentConfigService configs;
    private RuntimeWorkflowToolCatalogQuery catalog;

    @BeforeEach void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_agent", "runtime_agent_config_version",
                "runtime_agent_workflow_tool", "runtime_agent_skill_binding", "runtime_agent_remote_agent_binding"),
                RuntimeAgentMapper.class, RuntimeAgentConfigVersionMapper.class, RuntimeAgentWorkflowToolMapper.class,
                RuntimeAgentSkillBindingMapper.class, RuntimeAgentRemoteBindingMapper.class);
        var mapper = new ObjectMapper().findAndRegisterModules();
        catalog = mock(RuntimeWorkflowToolCatalogQuery.class);
        var target = new RuntimeAgentConfigService(database.mapper(RuntimeAgentConfigVersionMapper.class),
                database.mapper(RuntimeAgentWorkflowToolMapper.class), database.mapper(RuntimeAgentSkillBindingMapper.class),
                database.mapper(RuntimeAgentRemoteBindingMapper.class), database.mapper(RuntimeAgentMapper.class),
                catalog, mapper);
        target.setDraftRevision(new RuntimeAgentDraftRevision(mapper, "fixture-revision-key-at-least-32-bytes"));
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(database.jdbc().getDataSource()),
                new AnnotationTransactionAttributeSource()));
        configs = (RuntimeAgentConfigService) proxy.getProxy();
        database.jdbc().update("INSERT INTO runtime_agent (id,key_slug,name,project_id,project_code,enabled) VALUES (?,?,?,?,?,?)",
                "agent-1", "orders-agent", "订单助手", 7L, "orders", true);
    }

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void projectSignedGatewayPersistsUtf8RejectsHumanDriftAndPublishesWithCredentialIdentity() throws Exception {
        String secret = "fixture-project-signing-secret-at-least-32bytes";
        var properties = new InternalServiceAuthProperties(secret, 300, 600, 1000, 1_048_576);
        var filter = new InternalServiceAuthFilter(new InternalServiceAuthVerifier(properties, new InMemoryInternalAuthNonceStore()), properties);
        var json = new ObjectMapper().findAndRegisterModules();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RuntimeProjectAgentDraftEditController(configs))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).addFilters(filter).build();
        var runtime = mock(RuntimeProxyClient.class);
        when(runtime.projectAgentDraftContext(any(), any())).thenAnswer(call -> forward(mvc, json,
                "/internal/runtime/project-agent-config-drafts/context", call.getArgument(0), call.getArgument(1)));
        when(runtime.projectAgentConfigVersions(any(), any())).thenAnswer(call -> forward(mvc, json,
                "/internal/runtime/project-agent-config-drafts/versions", call.getArgument(0), call.getArgument(1)));
        when(runtime.saveProjectAgentDraft(any(), any())).thenAnswer(call -> forward(mvc, json,
                "/internal/runtime/project-agent-config-drafts/save", call.getArgument(0), call.getArgument(1)));
        when(runtime.publishProjectAgentDraft(any(), any())).thenAnswer(call -> forward(mvc, json,
                "/internal/runtime/project-agent-config-drafts/publish", call.getArgument(0), call.getArgument(1)));
        var gateway = new ControlAiCodingAgentDraftGateway(runtime, new InternalServiceAuthSigner(secret), json);
        var incoming = new org.springframework.mock.web.MockHttpServletRequest();
        String actor = "ai-coding-" + "c".repeat(64);
        incoming.setAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE, new VerifiedAiCodingProject(7L, actor));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(incoming));
        try {
            var context = gateway.context(7L, "orders", "agent-1");
            var saved = gateway.save(7L, "orders", "agent-1", (String) context.get("revision"),
                    Map.of("systemPrompt", "项目 AI 草稿", "modelInstanceId", "model-1"));
            var base = (Map<?, ?>) saved.get("base");
            long id = ((Number) base.get("id")).longValue();
            assertEquals("项目 AI 草稿", database.jdbc().queryForObject(
                    "SELECT system_prompt FROM runtime_agent_config_version WHERE id=?", String.class, id));
            configs.saveDraft("agent-1", changes("人工继续编辑"));
            assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> gateway.save(7L, "orders", "agent-1", (String) saved.get("revision"), Map.of("systemPrompt", "覆盖人工")))
                    .getStatusCode().value());
            assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> gateway.publish(7L, "orders", "agent-1", (String) saved.get("revision"), id)).getStatusCode().value());
            var current = gateway.context(7L, "orders", "agent-1");
            assertEquals("人工继续编辑", ((Map<?, ?>) current.get("base")).get("systemPrompt"));
            var published = gateway.publish(7L, "orders", "agent-1", (String) current.get("revision"), id);
            assertEquals("ACTIVE", ((Map<?, ?>) published.get("base")).get("status"));
            String fingerprint = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    java.util.HexFormat.of().parseHex("c".repeat(64)));
            assertEquals("project-key:" + fingerprint, database.jdbc().queryForObject(
                    "SELECT published_by FROM runtime_agent_config_version WHERE id=?", String.class, id));
            assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> gateway.context(7L, "billing", "agent-1")).getStatusCode().value());
            assertEquals(1, ((List<?>) gateway.versions(7L, "orders", "agent-1").get("versions")).size());
            database.jdbc().update("UPDATE runtime_agent SET project_code='billing' WHERE id='agent-1'");
            assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> gateway.versions(7L, "orders", "agent-1")).getStatusCode().value());
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test void detectsHumanChangesAndProjectMovesWithoutOverwritingThem() {
        var empty = configs.readEditableDraft(read());
        assertNull(empty.base());
        var first = configs.saveEditableDraft(save(empty.revision(), "AI 草稿"));
        assertEquals("AI 草稿", first.base().systemPrompt());
        assertNotEquals(empty.revision(), first.revision());
        configs.saveDraft("agent-1", changes("人工修改"));
        assertThrows(RuntimeAgentDraftConflictException.class, () -> configs.saveEditableDraft(save(first.revision(), "覆盖人工")));
        assertEquals("人工修改", configs.readEditableDraft(read()).base().systemPrompt());
        database.jdbc().update("UPDATE runtime_agent SET project_code='billing' WHERE id='agent-1'");
        assertThrows(RuntimeAgentDraftConflictException.class, () -> configs.saveEditableDraft(save(first.revision(), "跨项目")));
    }

    @Test void concurrentWritersWithTheSameRevisionHaveOnlyOneWinner() throws Exception {
        String revision = configs.readEditableDraft(read()).revision();
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var results = java.util.stream.IntStream.range(0, 2).mapToObj(i -> workers.submit(() -> {
                ready.countDown(); start.await();
                try { configs.saveEditableDraft(save(revision, "编辑 " + i)); return true; }
                catch (RuntimeAgentDraftConflictException conflict) { return false; }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            int winners = 0;
            for (var result : results) if (result.get(15, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
            assertTrue(List.of("编辑 0", "编辑 1").contains(configs.readEditableDraft(read()).base().systemPrompt()));
        } finally { workers.shutdownNow(); }
    }

    @Test void stalePublishAndCopyDoNotOverwriteAConcurrentEdit() {
        var initial = configs.createInitialDraft("agent-1", "线上配置", "model-1", null);
        configs.publish("agent-1", initial.id(), "reviewer");
        configs.saveDraft("agent-1", changes("待发布的草稿"));
        var reviewed = configs.readEditableDraft(read());
        configs.saveDraft("agent-1", changes("其他人更新后的草稿"));
        assertThrows(RuntimeAgentDraftConflictException.class, () -> configs.publishEditableDraft(
                version(reviewed.revision(), reviewed.base().id()), "reviewer"));
        assertThrows(RuntimeAgentDraftConflictException.class, () -> configs.copyEditableDraft(
                version(reviewed.revision(), initial.id())));
        assertEquals("其他人更新后的草稿", configs.readEditableDraft(read()).base().systemPrompt());

        var latest = configs.readEditableDraft(read());
        var published = configs.publishEditableDraft(version(latest.revision(), latest.base().id()), "reviewer");
        assertEquals("ACTIVE", published.base().status());
        assertEquals("其他人更新后的草稿", published.base().systemPrompt());
        assertThrows(RuntimeAgentDraftConflictException.class, () -> configs.copyEditableDraft(
                version(latest.revision(), initial.id())));
        var copied = configs.copyEditableDraft(version(published.revision(), initial.id()));
        assertEquals("DRAFT", copied.base().status());
        assertEquals("线上配置", copied.base().systemPrompt());
    }

    @Test void signedControlGatewayReachesRuntimeAndRejectsAStaleEdit() throws Exception {
        String secret = "fixture-internal-signing-secret-32-bytes";
        var properties = new InternalServiceAuthProperties(secret, 300, 600, 1000, 1_048_576);
        var filter = new InternalServiceAuthFilter(new InternalServiceAuthVerifier(properties, new InMemoryInternalAuthNonceStore()), properties);
        var json = new ObjectMapper().findAndRegisterModules();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RuntimeAgentDraftEditController(configs))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).addFilters(filter).build();
        var runtime = mock(RuntimeProxyClient.class);
        when(runtime.readAgentDraftContext(any(), any())).thenAnswer(invocation -> forward(mvc, json,
                "/internal/runtime/agent-config-drafts/context", invocation.getArgument(0), invocation.getArgument(1)));
        when(runtime.readAgentConfigVersionsForProject(any(), any())).thenAnswer(invocation -> forward(mvc, json,
                "/internal/runtime/agent-config-drafts/versions", invocation.getArgument(0), invocation.getArgument(1)));
        when(runtime.saveAgentDraftIfUnchanged(any(), any())).thenAnswer(invocation -> forward(mvc, json,
                "/internal/runtime/agent-config-drafts/save", invocation.getArgument(0), invocation.getArgument(1)));
        when(runtime.copyAgentDraftIfUnchanged(any(), any())).thenAnswer(invocation -> forward(mvc, json,
                "/internal/runtime/agent-config-drafts/copy", invocation.getArgument(0), invocation.getArgument(1)));
        when(runtime.publishAgentDraftIfUnchanged(any(), any())).thenAnswer(invocation -> forward(mvc, json,
                "/internal/runtime/agent-config-drafts/publish", invocation.getArgument(0), invocation.getArgument(1)));
        when(runtime.readAgentDraftPublicationContext(any(), any())).thenAnswer(invocation -> forward(mvc, json,
                "/internal/runtime/agent-config-drafts/publication-context", invocation.getArgument(0), invocation.getArgument(1)));
        when(runtime.publishApprovedAgentDraft(any(), any())).thenAnswer(invocation -> forward(mvc, json,
                "/internal/runtime/agent-config-drafts/publish-approved", invocation.getArgument(0), invocation.getArgument(1)));
        var gateway = new RuntimeAgentDraftGateway(runtime, new InternalServiceAuthSigner(secret), json);
        var actor = new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "Operator"), "session",
                null, List.of("OPERATOR"), List.of(), List.of());
        var context = gateway.context("agent-1", 7L, "orders", actor);
        assertEquals(200, context.getStatusCode().value());
        String revision = (String) ((Map<?, ?>) context.getBody()).get("revision");
        var saved = gateway.save("agent-1", 7L, "orders", revision, Map.of("systemPrompt", "审核订单", "modelInstanceId", "model-1"), actor);
        assertEquals(200, saved.getStatusCode().value());
        assertEquals("审核订单", ((Map<?, ?>) ((Map<?, ?>) saved.getBody()).get("base")).get("systemPrompt"));
        var versions = gateway.versions("agent-1", 7L, "orders", actor);
        assertEquals(200, versions.getStatusCode().value());
        assertEquals("orders", ((Map<?, ?>) versions.getBody()).get("projectCode"));
        assertEquals("审核订单", ((Map<?, ?>) ((List<?>) ((Map<?, ?>) versions.getBody()).get("versions")).get(0)).get("systemPrompt"));
        var stale = gateway.save("agent-1", 7L, "orders", revision, Map.of("systemPrompt", "过期编辑"), actor);
        assertEquals(409, stale.getStatusCode().value());
        assertEquals("AGENT_DRAFT_CHANGED", ((Map<?, ?>) stale.getBody()).get("code"));
        assertEquals("审核订单", configs.readEditableDraft(read()).base().systemPrompt());
        var savedBody = (Map<?, ?>) saved.getBody();
        long versionId = ((Number) ((Map<?, ?>) savedBody.get("base")).get("id")).longValue();
        assertEquals(409, gateway.publish("agent-1", 7L, "orders", revision, versionId, actor).getStatusCode().value());
        var preview = gateway.publicationContext("agent-1", 7L, "orders", (String) savedBody.get("revision"), versionId, actor);
        assertEquals(200, preview.getStatusCode().value());
        assertEquals(Map.of(), ((Map<?, ?>) preview.getBody()).get("workflowVersions"));
        var published = gateway.publishApproved("agent-1", 7L, "orders", (String) savedBody.get("revision"), versionId, Map.of(), actor);
        assertEquals(200, published.getStatusCode().value());
        assertEquals("platform-user:42", ((Map<?, ?>) ((Map<?, ?>) published.getBody()).get("base")).get("publishedBy"));
        var copied = gateway.copy("agent-1", 7L, "orders", (String) ((Map<?, ?>) published.getBody()).get("revision"), versionId, actor);
        assertEquals(200, copied.getStatusCode().value());
        assertEquals("DRAFT", ((Map<?, ?>) ((Map<?, ?>) copied.getBody()).get("base")).get("status"));
        database.jdbc().update("UPDATE runtime_agent SET project_code='billing' WHERE id='agent-1'");
        var moved = gateway.versions("agent-1", 7L, "orders", actor);
        assertEquals(409, moved.getStatusCode().value());
        assertFalse(((Map<?, ?>) moved.getBody()).containsKey("versions"));
    }

    @Test void approvedPublicationPinsExactlyTheReviewedWorkflowVersionsAndRejectsDrift() {
        var draft = configs.createInitialDraft("agent-1", "待上线配置", "model-1", null);
        database.jdbc().update("INSERT INTO runtime_agent_workflow_tool (agent_id,agent_config_version_id,workflow_id,workflow_version_id,tool_name) VALUES (?,?,?,?,?)",
                "agent-1", draft.id(), "wf-1", 91L, "lookup_order");
        var activeVersion = new java.util.concurrent.atomic.AtomicLong(100L);
        when(catalog.current(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> Map.of("wf-1", workflowEntry(activeVersion.get())));
        when(catalog.pinned(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> Map.of(
                new RuntimeWorkflowToolCatalogQuery.Reference("wf-1", 91L), workflowEntry(91L),
                new RuntimeWorkflowToolCatalogQuery.Reference("wf-1", 100L), workflowEntry(100L),
                new RuntimeWorkflowToolCatalogQuery.Reference("wf-1", 101L), workflowEntry(101L)));
        var current = configs.readEditableDraft(read());
        var preview = configs.previewDraftPublication(version(current.revision(), draft.id()));
        assertEquals(Map.of("wf-1", 100L), preview.workflowVersions());
        var approved = new RuntimeAgentConfigViews.AgentDraftApprovedPublishCommand("agent-1", 7L, "orders",
                preview.revision(), draft.id(), preview.workflowVersions());
        activeVersion.set(101L);
        assertThrows(RuntimeAgentDraftConflictException.class, () -> configs.publishApprovedDraft(approved, "platform-user:42"));
        assertEquals("DRAFT", database.jdbc().queryForObject("SELECT status FROM runtime_agent_config_version WHERE id=?", String.class, draft.id()));
        assertEquals(91L, database.jdbc().queryForObject("SELECT workflow_version_id FROM runtime_agent_workflow_tool WHERE agent_config_version_id=?", Long.class, draft.id()));
        activeVersion.set(100L);
        var published = configs.publishApprovedDraft(approved, "platform-user:42");
        assertEquals("ACTIVE", published.base().status());
        assertEquals(100L, published.base().tools().get(0).workflowVersionId());
        assertThrows(RuntimeAgentDraftConflictException.class, () -> configs.publishApprovedDraft(approved, "platform-user:42"));
    }

    @Test void anUnchangedDraftCannotPublishOverAnUnreviewedActiveConfiguration() {
        var draft = configs.createInitialDraft("agent-1", "待上线配置", "model-1", null);
        var context = configs.readEditableDraft(read());
        database.jdbc().update("UPDATE runtime_agent SET active_config_version_id=99 WHERE id='agent-1'");
        assertNotEquals(context.revision(), configs.readEditableDraft(read()).revision());
        assertThrows(RuntimeAgentDraftConflictException.class, () -> configs.publishApprovedDraft(
                new RuntimeAgentConfigViews.AgentDraftApprovedPublishCommand("agent-1", 7L, "orders", context.revision(), draft.id(), Map.of()),
                "platform-user:42"));
        database.jdbc().update("UPDATE runtime_agent SET active_config_version_id=NULL, visibility='PUBLIC', allowed_roles_json='[\"operator\"]' WHERE id='agent-1'");
        assertNotEquals(context.revision(), configs.readEditableDraft(read()).revision());
        assertThrows(RuntimeAgentDraftConflictException.class, () -> configs.publishApprovedDraft(
                new RuntimeAgentConfigViews.AgentDraftApprovedPublishCommand("agent-1", 7L, "orders", context.revision(), draft.id(), Map.of()),
                "platform-user:42"));
        var audience = configs.readEditableDraft(read());
        database.jdbc().update("UPDATE runtime_agent SET allowed_roles_json='[\"manager\"]' WHERE id='agent-1'");
        assertThrows(RuntimeAgentDraftConflictException.class, () -> configs.publishApprovedDraft(
                new RuntimeAgentConfigViews.AgentDraftApprovedPublishCommand("agent-1", 7L, "orders", audience.revision(), draft.id(), Map.of()),
                "platform-user:42"));
    }

    private static RuntimeWorkflowToolCatalogQuery.Entry workflowEntry(long version) {
        return new RuntimeWorkflowToolCatalogQuery.Entry(new RuntimeWorkflowToolCatalogQuery.Workflow("wf-1", "orders", "订单查询", "", true),
                new RuntimeWorkflowToolCatalogQuery.Version(version, "v" + version, true, "{}", "{}"));
    }

    private static ResponseEntity<Object> forward(MockMvc mvc, ObjectMapper json, String path,
                                                   Map<String, String> headers, byte[] body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        headers.forEach(request::header);
        var response = mvc.perform(request).andReturn().getResponse();
        byte[] content = response.getContentAsByteArray();
        Object parsed = content.length == 0 ? null : json.readValue(content, Object.class);
        return ResponseEntity.status(response.getStatus()).body(parsed);
    }

    private static AgentDraftEditCommand read() { return new AgentDraftEditCommand("agent-1", 7L, "orders", null, null); }
    private static AgentDraftEditCommand save(String revision, String prompt) {
        return new AgentDraftEditCommand("agent-1", 7L, "orders", revision, changes(prompt));
    }
    private static AgentDraftVersionCommand version(String revision, Long configVersionId) {
        return new AgentDraftVersionCommand("agent-1", 7L, "orders", revision, configVersionId);
    }
    private static AgentConfigDraftRequest changes(String prompt) {
        return new AgentConfigDraftRequest(null, prompt, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
