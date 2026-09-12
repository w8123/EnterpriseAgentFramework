package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeAgentRemoteBindingReaderTest {

    private RuntimeQueryTestDatabase database;
    private AnnotationConfigApplicationContext context;
    private RuntimeAgentRemoteBindingMapper bindings;
    private RuntimeAgentConfigVersionMapper configurations;
    private RuntimeAgentRemoteBindingQuery query;

    @BeforeEach
    void databaseAndConfigurationOwner() throws Exception {
        database = new RuntimeQueryTestDatabase(
                List.of("runtime_agent_config_version", "runtime_agent_remote_agent_binding"),
                RuntimeAgentConfigVersionMapper.class, RuntimeAgentRemoteBindingMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(RuntimeAgentConfigVersionMapper.class, () -> database.mapper(RuntimeAgentConfigVersionMapper.class));
        context.registerBean(RuntimeAgentRemoteBindingMapper.class, () -> database.mapper(RuntimeAgentRemoteBindingMapper.class));
        context.register(RuntimeAgentRemoteBindingReader.class);
        context.refresh();
        configurations = context.getBean(RuntimeAgentConfigVersionMapper.class);
        bindings = context.getBean(RuntimeAgentRemoteBindingMapper.class);
        query = context.getBean(RuntimeAgentRemoteBindingQuery.class);
        configurations.insert(config(23L, "agent-1", "ACTIVE"));
        configurations.insert(config(24L, "agent-1", "DRAFT"));
        configurations.insert(config(25L, "agent-2", "ACTIVE"));
        bindings.insert(binding(31L, "agent-1", 23L, true, 10));
        bindings.insert(binding(32L, "agent-1", 23L, false, 0));
        bindings.insert(binding(33L, "agent-1", 24L, true, 0));
        bindings.insert(binding(34L, "agent-2", 25L, true, 0));
        bindings.insert(binding(35L, "agent-1", 23L, true, 5));
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (database != null) database.close();
    }

    @Test
    void delegationRequiresTheExactActiveOwnerVersionAndEnabledBinding() {
        var binding = query.requireActiveBinding("agent-1", 23L, 31L);
        assertEquals(31L, binding.getId());
        assertEquals(91L, binding.getRemoteAgentRevisionId());
        assertEquals(41L, binding.getPrincipalId());
        assertEquals("[\"review\"]", binding.getAllowedSkillIdsJson());
        assertThrows(IllegalArgumentException.class, () -> query.requireActiveBinding("agent-2", 23L, 31L));
        assertThrows(IllegalArgumentException.class, () -> query.requireActiveBinding("agent-1", 999L, 31L));
        assertThrows(IllegalArgumentException.class, () -> query.requireActiveBinding("agent-1", 24L, 33L));
        assertThrows(IllegalArgumentException.class, () -> query.requireActiveBinding("agent-1", 23L, 32L));
        assertThrows(IllegalArgumentException.class, () -> query.requireActiveBinding("agent-1", 23L, 33L));
        assertThrows(IllegalArgumentException.class, () -> query.requireActiveBinding("agent-1", 23L, 34L));
        var archived = configurations.selectById(23L);
        archived.setStatus("ARCHIVED");
        configurations.updateById(archived);
        assertThrows(IllegalArgumentException.class, () -> query.requireActiveBinding("agent-1", 23L, 31L));
    }

    @Test
    void executionCatalogKeepsPriorityOrderAndReturnsDetachedValues() {
        var catalog = query.enabledBindings("agent-1", 23L);
        assertEquals(List.of(35L, 31L), catalog.stream().map(RuntimeAgentRemoteBindingView::getId).toList());
        assertThrows(UnsupportedOperationException.class, catalog::clear);
        var changed = bindings.selectById(35L);
        changed.setRemoteAgentKeySnapshot("later-edit");
        bindings.updateById(changed);
        assertEquals("remote-35", catalog.get(0).getRemoteAgentKeySnapshot());
        assertEquals("later-edit", query.enabledBindings("agent-1", 23L).get(0).getRemoteAgentKeySnapshot());
        assertTrue(query.enabledBindings("agent-2", 23L).isEmpty());
        assertTrue(query.enabledBindings(" ", 23L).isEmpty());
        assertTrue(query.enabledBindings("agent-1", null).isEmpty());
    }

    private RuntimeAgentConfigVersionEntity config(Long id, String agentId, String status) {
        var config = new RuntimeAgentConfigVersionEntity();
        config.setId(id);
        config.setAgentId(agentId);
        config.setVersionNo(id.intValue());
        config.setStatus(status);
        return config;
    }

    private RuntimeAgentRemoteBindingEntity binding(Long id, String agentId, Long versionId, boolean enabled, int priority) {
        var binding = new RuntimeAgentRemoteBindingEntity();
        binding.setId(id);
        binding.setAgentId(agentId);
        binding.setAgentConfigVersionId(versionId);
        binding.setPrincipalId(41L);
        binding.setRemoteAgentId(51L);
        binding.setRemoteAgentRevisionId(60L + id);
        binding.setRemoteAgentKeySnapshot("remote-" + id);
        binding.setToolName("remote.review." + id);
        binding.setDescriptionSnapshot("Remote review");
        binding.setAllowedSkillIdsJson("[\"review\"]");
        binding.setInputModesJson("[\"text/plain\"]");
        binding.setOutputModesJson("[\"text/plain\"]");
        binding.setEnabled(enabled);
        binding.setPriority(priority);
        return binding;
    }
}
