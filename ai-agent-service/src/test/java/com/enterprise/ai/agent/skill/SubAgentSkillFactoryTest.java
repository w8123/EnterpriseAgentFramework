package com.enterprise.ai.agent.skill;

import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SubAgentSkillFactoryTest {

    private SubAgentSkillFactory factory;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        factory = new SubAgentSkillFactory(objectMapper, mock(SubAgentSkillExecutor.class));
    }

    @Test
    void buildRejectsNonSkillEntity() {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName("foo");
        entity.setKind("TOOL");
        assertThrows(IllegalArgumentException.class, () -> factory.build(entity));
    }

    @Test
    void buildRejectsMissingSpec() {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName("foo");
        entity.setKind("SKILL");
        entity.setSpecJson(null);
        assertThrows(IllegalArgumentException.class, () -> factory.build(entity));
    }

    @Test
    void buildRejectsBlankSystemPrompt() {
        ToolDefinitionEntity entity = skillEntity("{\"systemPrompt\":\"\",\"toolWhitelist\":[\"a\"]}");
        assertThrows(IllegalArgumentException.class, () -> factory.build(entity));
    }

    @Test
    void buildRejectsEmptyToolWhitelist() {
        ToolDefinitionEntity entity = skillEntity("{\"systemPrompt\":\"you are helpful\",\"toolWhitelist\":[]}");
        assertThrows(IllegalArgumentException.class, () -> factory.build(entity));
    }

    @Test
    void buildAcceptsValidSkill() {
        ToolDefinitionEntity entity = skillEntity(
                "{\"systemPrompt\":\"handle triage\",\"toolWhitelist\":[\"customer_lookup\"],\"maxSteps\":5}");
        entity.setDescription("triage a customer");
        entity.setParametersJson("[{\"name\":\"customerId\",\"type\":\"string\",\"description\":\"id\",\"required\":true}]");
        entity.setSideEffect("READ_ONLY");

        SubAgentSkill skill = factory.build(entity);
        assertNotNull(skill);
        assertEquals("risk_triage", skill.name());
        assertEquals(1, skill.parameters().size());
        assertEquals("customerId", skill.parameters().get(0).name());
        assertEquals("SUB_AGENT", skill.kind().name());
        assertTrue(skill.dependsOnTools().contains("customer_lookup"));
    }

    @Test
    void buildRejectsUnsupportedSkillKind() {
        ToolDefinitionEntity entity = skillEntity(
                "{\"systemPrompt\":\"x\",\"toolWhitelist\":[\"a\"]}");
        entity.setSkillKind("WORKFLOW");
        assertThrows(IllegalArgumentException.class, () -> factory.build(entity));
    }

    @Test
    void serializeRoundTrip() throws Exception {
        SubAgentSpec spec = new SubAgentSpec(
                "prompt", java.util.List.of("a", "b"), "openai", "gpt-4", 10, false);
        String json = factory.serializeSpec(spec);
        SubAgentSpec parsed = factory.parseSpec(json);
        assertEquals(spec.systemPrompt(), parsed.systemPrompt());
        assertEquals(spec.toolWhitelist(), parsed.toolWhitelist());
        assertEquals(10, parsed.maxSteps());
    }

    private ToolDefinitionEntity skillEntity(String specJson) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName("risk_triage");
        entity.setKind("SKILL");
        entity.setSkillKind("SUB_AGENT");
        entity.setSpecJson(specJson);
        return entity;
    }
}
