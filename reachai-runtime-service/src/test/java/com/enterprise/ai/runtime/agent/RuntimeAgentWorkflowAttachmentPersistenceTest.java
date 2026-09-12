package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelCatalogClient;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.workflow.*;
import com.enterprise.ai.runtime.internal.RuntimeAgentSupervisorWorkflowAttachmentService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort.AttachRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Agent persistence and transaction proxies; Workflow/model catalogs are external test doubles. */
class RuntimeAgentWorkflowAttachmentPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private RuntimeAgentConfigService configs;
    private RuntimeAgentSupervisorWorkflowAttachmentService attachments;
    private RuntimeAgentMapper agents;
    private RuntimeAgentConfigVersionMapper versions;
    private RuntimeAgentWorkflowToolMapper tools;
    private RuntimeModelCatalogClient models;
    private final Map<String, Long> currentVersions = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_agent", "runtime_agent_config_version",
                "runtime_agent_workflow_tool", "runtime_agent_skill_binding", "runtime_agent_remote_agent_binding"),
                RuntimeAgentMapper.class, RuntimeAgentConfigVersionMapper.class, RuntimeAgentWorkflowToolMapper.class,
                RuntimeAgentSkillBindingMapper.class, RuntimeAgentRemoteBindingMapper.class);
        agents = spy(database.mapper(RuntimeAgentMapper.class));
        versions = database.mapper(RuntimeAgentConfigVersionMapper.class);
        tools = database.mapper(RuntimeAgentWorkflowToolMapper.class);
        var catalog = mock(RuntimeWorkflowToolCatalogQuery.class);
        when(catalog.current(anyList())).thenAnswer(call -> {
            Map<String, RuntimeWorkflowToolCatalogQuery.Entry> result = new LinkedHashMap<>();
            for (String id : (List<String>) call.getArgument(0)) result.put(id, entry(id, currentVersions.getOrDefault(id, 100L)));
            return result;
        });
        when(catalog.pinned(anyList())).thenAnswer(call -> {
            Map<RuntimeWorkflowToolCatalogQuery.Reference, RuntimeWorkflowToolCatalogQuery.Entry> result = new LinkedHashMap<>();
            for (RuntimeWorkflowToolCatalogQuery.Reference ref : (List<RuntimeWorkflowToolCatalogQuery.Reference>) call.getArgument(0)) {
                result.put(ref, entry(ref.workflowId(), ref.versionId()));
            }
            return result;
        });
        var json = new ObjectMapper().findAndRegisterModules();
        configs = transactional(new RuntimeAgentConfigService(versions, tools,
                database.mapper(RuntimeAgentSkillBindingMapper.class), database.mapper(RuntimeAgentRemoteBindingMapper.class),
                agents, catalog, json));
        var projects = mock(RuntimeCapabilityCatalogClient.class);
        when(projects.getProjectById(7L)).thenReturn(Map.of("projectId", 7L, "projectCode", "orders"));
        models = mock(RuntimeModelCatalogClient.class);
        when(models.isActiveLlm(anyString())).thenReturn(true);
        when(models.firstActiveLlmId()).thenReturn("model-1");
        var definitions = mock(RuntimeWorkflowDefinitionService.class);
        when(definitions.findById(anyString())).thenAnswer(call -> {
            String id = call.getArgument(0);
            var workflow = new RuntimeWorkflowDefinitionEntity();
            workflow.setId(id); workflow.setKeySlug(id); workflow.setProjectId(7L);
            workflow.setProjectCode("orders"); workflow.setStatus("ACTIVE"); workflow.setWorkflowKind("GENERAL");
            return Optional.of(workflow);
        });
        attachments = new RuntimeAgentSupervisorWorkflowAttachmentService(projects, new RuntimeWorkflowManagementService(definitions, null, null),
                transactional(new RuntimeAgentWorkflowAttachmentService(agents, configs, models, json)), json);
        database.jdbc().update("""
                INSERT INTO runtime_agent (id, key_slug, name, project_id, project_code, enabled)
                VALUES ('agent-1', 'orders-page-copilot', 'Agent', 7, 'orders', true)
                """);
        var initial = configs.createInitialDraft("agent-1", "approved prompt", "model-1", null);
        configs.publish("agent-1", initial.id(), "reviewer");
    }

    @AfterEach
    void close() { if (database != null) database.close(); }

    @Test
    void newAttachmentDoesNotPublishOrModifyAnExistingDraft() {
        var draft = configs.createInitialDraft("agent-1", "unreviewed user prompt", "draft-model", "{\"draftOnly\":true}");
        configs.upsertWorkflowToolInDraft("agent-1", tool("draft_only"));
        var before = snapshot(draft.id());
        var result = attach("new_workflow");
        assertAll(
                () -> assertNotEquals(draft.id(), result.activeConfig().id()),
                () -> assertEquals(before, snapshot(draft.id())),
                () -> assertEquals("approved prompt", versions.selectById(result.activeConfig().id()).getSystemPrompt()),
                () -> assertEquals(List.of("new_workflow"), workflowIds(result.activeConfig().id())));
    }

    @Test
    void refreshingTheAttachedVersionDoesNotPublishAnExistingDraft() {
        attach("workflow_one");
        var draft = configs.createInitialDraft("agent-1", "unreviewed user prompt", "draft-model", null);
        var before = snapshot(draft.id());
        currentVersions.put("workflow_one", 200L);
        var result = attach("workflow_one");
        assertAll(
                () -> assertNotEquals(draft.id(), result.activeConfig().id()),
                () -> assertEquals(before, snapshot(draft.id())),
                () -> assertEquals(List.of("workflow_one"), workflowIds(result.activeConfig().id())),
                () -> assertEquals(200L, configs.listTools("agent-1", result.activeConfig().id()).get(0).workflowVersionId()));
    }

    @Test
    void attachingOneWorkflowPreservesOtherPublishedVersionPins() {
        attach("workflow_one");
        currentVersions.put("workflow_one", 200L);
        var result = attach("workflow_two");
        assertEquals(100L, configs.listTools("agent-1", result.activeConfig().id()).stream()
                .filter(t -> t.workflowId().equals("workflow_one")).findFirst().orElseThrow().workflowVersionId());
    }

    @Test
    void replacementUsesThePublishedCatalogAndPreservesTheDraft() {
        attach("workflow_one");
        var draft = configs.saveDraft("agent-1", null);
        configs.upsertWorkflowToolInDraft("agent-1", tool("draft_only"));
        var before = snapshot(draft.id());
        var result = attachments.attach(7L, new AttachRequest("replacement", "agent-1", null,
                "model-1", "reviewer", null, null, null, null, null, null, null, null, "workflow_one"));
        assertAll(
                () -> assertEquals(before, snapshot(draft.id())),
                () -> assertEquals(List.of("replacement"), workflowIds(result.activeConfig().id())));
    }

    @Test
    void repeatedAttachmentReusesActiveAndLeavesDraftUntouched() {
        var first = attach("workflow_one");
        var draft = configs.createInitialDraft("agent-1", "private draft", "draft-model", null);
        var before = snapshot(draft.id());
        var repeated = attach("workflow_one");
        assertEquals(first.activeConfig().id(), repeated.activeConfig().id());
        assertTrue(repeated.reused());
        assertEquals(before, snapshot(draft.id()));
    }

    @Test
    void disabledWorkflowEntriesAndTheirOverridesSurviveUnrelatedAttachment() {
        var first = attach("workflow_one");
        database.jdbc().update("""
                UPDATE runtime_agent_workflow_tool SET enabled = false, priority = 17,
                  description_override = 'approved override', input_schema_override_json = '{"fixed":true}'
                WHERE agent_config_version_id = ?
                """, first.activeConfig().id());
        var before = bindingSnapshot("runtime_agent_workflow_tool", first.activeConfig().id());
        var second = attach("workflow_two");
        var retained = bindingSnapshot("runtime_agent_workflow_tool", second.activeConfig().id()).stream()
                .filter(row -> row.get("workflow_id").equals("workflow_one")).toList();
        assertEquals(before, retained);
    }

    @Test
    void copiesAllPublishedSkillAndRemoteBindingSnapshots() {
        Long active = agents.selectById("agent-1").getActiveConfigVersionId();
        String digest = "a".repeat(64);
        String manifest = "{\"name\":\"skill-one\",\"sourceRoot\":\"\",\"sourceSha256\":\"" + digest
                + "\",\"contentTreeSha256\":\"" + digest + "\",\"files\":[{\"path\":\"SKILL.md\"}]}";
        database.jdbc().update("""
                INSERT INTO runtime_agent_skill_binding
                  (agent_id, agent_config_version_id, skill_id, skill_version_id, publisher, standard_name,
                   visibility, project_code, version, source_sha256, content_tree_sha256, package_manifest_json, enabled)
                VALUES ('agent-1', ?, 1, 11, 'reachai', 'skill-one', 'PROJECT', 'orders', '1.0.0', ?, ?, ?, false)
                """, active, digest, digest, manifest);
        database.jdbc().update("""
                INSERT INTO runtime_agent_remote_agent_binding
                  (agent_id, agent_config_version_id, principal_id, remote_agent_id, remote_agent_revision_id,
                   remote_agent_key_snapshot, tool_name, description_snapshot, input_modes_json, output_modes_json,
                   permission_key, allowed_skill_ids_json, enabled)
                VALUES ('agent-1', ?, 1, 2, 3, 'remote-one', 'remote_one', 'approved remote', '["text"]', '["text"]', 'remote:one', '["skill-one"]', false)
                """, active);
        var skills = bindingSnapshot("runtime_agent_skill_binding", active);
        var remotes = bindingSnapshot("runtime_agent_remote_agent_binding", active);
        var result = attach("workflow_one");
        assertEquals(skills, bindingSnapshot("runtime_agent_skill_binding", result.activeConfig().id()));
        assertEquals(remotes, bindingSnapshot("runtime_agent_remote_agent_binding", result.activeConfig().id()));
    }

    @Test
    void sqlFailureAtFinalPointerUpdateRollsBackArchiveCandidateAndBindings() {
        Long active = agents.selectById("agent-1").getActiveConfigVersionId();
        var draft = configs.saveDraft("agent-1", null);
        var before = snapshot(draft.id());
        database.jdbc().execute("ALTER TABLE runtime_agent ADD CONSTRAINT keep_active CHECK (active_config_version_id = " + active + ")");
        assertThrows(RuntimeException.class, () -> attach("workflow_one"));
        assertEquals(active, agents.selectById("agent-1").getActiveConfigVersionId());
        assertEquals("ACTIVE", versions.selectById(active).getStatus());
        assertEquals(before, snapshot(draft.id()));
        assertEquals(2, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_agent_config_version", Integer.class));
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_agent_workflow_tool", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentAttachmentsSerializeOnAgentWithoutLostToolsOrDuplicatePublication(boolean sameWorkflow) throws Exception {
        var lockAttempts = new AtomicInteger();
        var contender = new CountDownLatch(1);
        var firstModelRead = new AtomicBoolean(true);
        doAnswer(call -> {
            if (lockAttempts.incrementAndGet() == 2) contender.countDown();
            return call.callRealMethod();
        }).when(agents).lockById("agent-1");
        when(models.isActiveLlm("model-1")).thenAnswer(call -> {
            if (firstModelRead.getAndSet(false)) assertTrue(contender.await(5, TimeUnit.SECONDS));
            return true;
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> attach("workflow_one"));
            var two = pool.submit(() -> attach(sameWorkflow ? "workflow_one" : "workflow_two"));
            var first = one.get(10, TimeUnit.SECONDS);
            var second = two.get(10, TimeUnit.SECONDS);
            Long active = agents.selectById("agent-1").getActiveConfigVersionId();
            assertEquals(sameWorkflow ? List.of("workflow_one") : List.of("workflow_one", "workflow_two"), workflowIds(active));
            assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_agent_config_version WHERE status = 'ACTIVE'", Integer.class));
            if (sameWorkflow) {
                assertEquals(first.activeConfig().id(), second.activeConfig().id());
                assertTrue(first.reused() != second.reused());
                assertEquals(2, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_agent_config_version", Integer.class));
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test
    void provisionsManagedAgentWithChineseDefaultsAndNoEditableCandidateLeftBehind() {
        database.jdbc().update("UPDATE runtime_agent SET key_slug = 'custom-agent' WHERE id = 'agent-1'");
        var result = attachments.attach(7L, new AttachRequest("workflow_one", null, null, "model-1", "Codex"));
        var created = agents.selectById(result.agent().id());
        assertEquals("orders 页面副驾驶 Agent", created.getName());
        assertTrue(versions.selectById(result.activeConfig().id()).getSystemPrompt().contains("名称、说明和回复默认使用简体中文"));
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_agent_config_version WHERE agent_id = ? AND status = 'DRAFT'", Integer.class, created.getId()));
    }

    @Test
    void firstManagedPublicationUsesDefaultsAndPreservesExistingUnreviewedDraft() {
        database.jdbc().update("UPDATE runtime_agent_config_version SET status = 'DRAFT', system_prompt = 'private draft'");
        database.jdbc().update("UPDATE runtime_agent SET active_config_version_id = NULL");
        Long draft = database.jdbc().queryForObject("SELECT id FROM runtime_agent_config_version", Long.class);
        var before = snapshot(draft);
        var result = attach("workflow_one");
        assertEquals(before, snapshot(draft));
        assertTrue(versions.selectById(result.activeConfig().id()).getSystemPrompt().startsWith("你是当前项目的页面副驾驶 Supervisor"));
    }

    @Test
    void customAgentRequiresExplicitInitialPublication() {
        database.jdbc().update("UPDATE runtime_agent_config_version SET status = 'DRAFT'");
        database.jdbc().update("UPDATE runtime_agent SET active_config_version_id = NULL, key_slug = 'custom-agent'");
        assertThrows(RuntimeException.class, () -> attach("workflow_one"));
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_agent_config_version WHERE status = 'ACTIVE'", Integer.class));
    }

    @Test
    void modelLookupFailureRollsBackAutomaticAgentProvisioning() {
        database.jdbc().update("UPDATE runtime_agent SET key_slug = 'custom-agent'");
        when(models.isActiveLlm("model-1")).thenThrow(new IllegalStateException("catalog unavailable"));
        var error = assertThrows(com.enterprise.ai.runtime.workflow.aicoding.AiCodingAttachmentException.class,
                () -> attachments.attach(7L, new AttachRequest("workflow_one", null, null, "model-1", "Codex")));
        assertEquals("RUNTIME_DEPENDENCY_UNAVAILABLE", error.code());
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_agent", Integer.class));
    }

    @Test
    void rejectsForeignAgentWithoutPublishingOrChangingItsDraft() {
        var draft = configs.saveDraft("agent-1", null);
        var before = snapshot(draft.id());
        database.jdbc().update("UPDATE runtime_agent SET project_id = 99, project_code = 'foreign'");
        var error = assertThrows(com.enterprise.ai.runtime.workflow.aicoding.AiCodingAttachmentException.class,
                () -> attach("workflow_one"));
        assertEquals("AGENT_PROJECT_MISMATCH", error.code());
        assertEquals(before, snapshot(draft.id()));
    }

    @Test
    void missingActiveModelKeepsExistingAgentConfigurationUnchanged() {
        Long active = agents.selectById("agent-1").getActiveConfigVersionId();
        when(models.isActiveLlm(anyString())).thenReturn(false);
        when(models.firstActiveLlmId()).thenReturn(null);
        var error = assertThrows(com.enterprise.ai.runtime.workflow.aicoding.AiCodingAttachmentException.class,
                () -> attachments.attach(7L, new AttachRequest("workflow_one", "agent-1", null, null, "Codex")));
        assertEquals("NO_ACTIVE_LLM", error.code());
        assertEquals(active, agents.selectById("agent-1").getActiveConfigVersionId());
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_agent_config_version", Integer.class));
    }

    @Test
    void preservesExplicitPriorityAndOnlyUpdatesTheRequestedToolConfiguration() {
        attach("workflow_one");
        var first = attach("workflow_two");
        var original = bindingSnapshot("runtime_agent_workflow_tool", first.activeConfig().id()).stream()
                .filter(row -> row.get("workflow_id").equals("workflow_one")).toList();
        var updated = attachments.attach(7L, new AttachRequest("workflow_two", "agent-1", null, "model-1", "reviewer",
                "custom_tool", "已审核的描述", Map.of("type", "object"), null, "WRITE", "write:custom", false, 17));
        var tool = configs.listTools("agent-1", updated.activeConfig().id()).stream()
                .filter(row -> row.workflowId().equals("workflow_two")).findFirst().orElseThrow();
        assertEquals(17, tool.priority());
        assertEquals("custom_tool", tool.toolName());
        assertEquals("已审核的描述", tool.descriptionOverride());
        assertFalse(tool.readOnly());
        assertEquals(original, bindingSnapshot("runtime_agent_workflow_tool", updated.activeConfig().id()).stream()
                .filter(row -> row.get("workflow_id").equals("workflow_one")).toList());
    }

    @Test
    void rejectsReplacementThatExistsOnlyInTheUnpublishedDraft() {
        var draft = configs.saveDraft("agent-1", null);
        configs.upsertWorkflowToolInDraft("agent-1", tool("draft_only"));
        var before = snapshot(draft.id());
        var error = assertThrows(com.enterprise.ai.runtime.workflow.aicoding.AiCodingAttachmentException.class,
                () -> attachments.attach(7L, new AttachRequest("new_workflow", "agent-1", null, "model-1", "reviewer",
                        null, null, null, null, null, null, null, null, "draft_only")));
        assertEquals("WORKFLOW_REPLACEMENT_INVALID", error.code());
        assertEquals(before, snapshot(draft.id()));
    }

    @Test
    void identityEditSerializesWithPublicationAndCannotRestoreAnOldConfigPointer() throws Exception {
        var identity = transactional(new RuntimeAgentService(agents, configs));
        var identityRead = new CountDownLatch(1);
        var releaseIdentity = new CountDownLatch(1);
        var publicationLock = new CountDownLatch(1);
        var firstIdentityRead = new AtomicBoolean(true);
        doAnswer(call -> {
            Object row = call.callRealMethod();
            if (Thread.currentThread().getName().equals("identity-edit") && firstIdentityRead.getAndSet(false)) {
                identityRead.countDown();
                assertTrue(releaseIdentity.await(5, TimeUnit.SECONDS));
            }
            return row;
        }).when(agents).selectById("agent-1");
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("attachment-publish")) publicationLock.countDown();
            return call.callRealMethod();
        }).when(agents).lockById("agent-1");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var edit = pool.submit(() -> {
                Thread.currentThread().setName("identity-edit");
                return identity.update("agent-1", new RuntimeAgentIdentityRequest(null, null, null, null,
                        "已更名", null, null, null, false));
            });
            assertTrue(identityRead.await(5, TimeUnit.SECONDS));
            var publish = pool.submit(() -> {
                Thread.currentThread().setName("attachment-publish");
                return attach("workflow_one");
            });
            assertTrue(publicationLock.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> publish.get(200, TimeUnit.MILLISECONDS));
            releaseIdentity.countDown();
            edit.get(5, TimeUnit.SECONDS);
            var result = publish.get(5, TimeUnit.SECONDS);
            var stored = agents.selectById("agent-1");
            assertEquals(result.activeConfig().id(), stored.getActiveConfigVersionId());
            assertEquals("已更名", stored.getName());
            assertFalse(stored.getEnabled());
        } finally {
            releaseIdentity.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentFirstAttachmentsShareOneManagedAgentAndKeepBothTools() throws Exception {
        database.jdbc().update("UPDATE runtime_agent SET key_slug = 'custom-agent'");
        var missingTargets = new CountDownLatch(2);
        doAnswer(call -> {
            Object target = call.callRealMethod();
            if (target == null) {
                missingTargets.countDown();
                assertTrue(missingTargets.await(5, TimeUnit.SECONDS));
            }
            return target;
        }).when(agents).selectOne(any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> attachments.attach(7L, new AttachRequest("workflow_one", null, null, "model-1", "Codex")));
            var two = pool.submit(() -> attachments.attach(7L, new AttachRequest("workflow_two", null, null, "model-1", "Codex")));
            var first = one.get(10, TimeUnit.SECONDS);
            var second = two.get(10, TimeUnit.SECONDS);
            assertEquals(first.agent().id(), second.agent().id());
            assertEquals(2, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_agent", Integer.class));
            var stored = agents.selectById(first.agent().id());
            assertEquals(List.of("workflow_one", "workflow_two"), workflowIds(stored.getActiveConfigVersionId()));
            assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_agent_config_version WHERE agent_id = ? AND status = 'ACTIVE'", Integer.class, stored.getId()));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    private List<Map<String, Object>> bindingSnapshot(String table, Long versionId) {
        if (!Set.of("runtime_agent_workflow_tool", "runtime_agent_skill_binding", "runtime_agent_remote_agent_binding").contains(table)) {
            throw new IllegalArgumentException("Unexpected fixture table");
        }
        return database.jdbc().queryForList("SELECT * FROM " + table + " WHERE agent_config_version_id = ? ORDER BY id", versionId)
                .stream().map(row -> {
                    row.keySet().removeAll(Set.of("id", "agent_config_version_id", "created_at", "updated_at"));
                    return row;
                }).toList();
    }

    private RuntimeWorkflowAgentAttachmentPort.AttachmentResult attach(String workflowId) {
        return attachments.attach(7L, new AttachRequest(workflowId, "agent-1", null, "model-1", "reviewer"));
    }

    private List<String> workflowIds(Long versionId) {
        return database.jdbc().queryForList("SELECT workflow_id FROM runtime_agent_workflow_tool WHERE agent_config_version_id = ? ORDER BY workflow_id", String.class, versionId);
    }

    private List<Object> snapshot(Long versionId) {
        return List.of(database.jdbc().queryForMap("SELECT * FROM runtime_agent_config_version WHERE id = ?", versionId),
                database.jdbc().queryForList("SELECT * FROM runtime_agent_workflow_tool WHERE agent_config_version_id = ? ORDER BY id", versionId));
    }

    private RuntimeAgentConfigViews.WorkflowToolRequest tool(String workflowId) {
        return new RuntimeAgentConfigViews.WorkflowToolRequest(workflowId, workflowId, null, null, null,
                "READ", "workflow:" + workflowId, true, true, null);
    }

    private RuntimeWorkflowToolCatalogQuery.Entry entry(String id, Long versionId) {
        return new RuntimeWorkflowToolCatalogQuery.Entry(
                new RuntimeWorkflowToolCatalogQuery.Workflow(id, id, id, "Description", true),
                new RuntimeWorkflowToolCatalogQuery.Version(versionId, "v" + versionId, true, "{}", "{}"));
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T service) {
        var proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(database.jdbc().getDataSource()),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
