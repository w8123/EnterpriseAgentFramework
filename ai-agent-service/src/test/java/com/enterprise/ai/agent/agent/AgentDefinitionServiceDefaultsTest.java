package com.enterprise.ai.agent.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDefinitionServiceDefaultsTest {

    @TempDir
    Path tempDir;

    @Test
    void seedsOnlySafeDefaultAgentsAfterToolingTrim() {
        AgentDefinitionService service = new AgentDefinitionService();
        ReflectionTestUtils.setField(service, "definitionsFile", tempDir.resolve("agent-definitions.json").toString());

        service.init();

        assertEquals(Set.of("KNOWLEDGE_QA", "GENERAL_CHAT"),
                service.list().stream()
                        .map(AgentDefinition::getIntentType)
                        .filter(intent -> intent != null && !intent.isBlank())
                        .collect(java.util.stream.Collectors.toSet()));

        assertTrue(service.list().stream()
                .flatMap(def -> def.getTools().stream())
                .noneMatch(tool -> Set.of("query_database", "call_business_api", "query_user_profile").contains(tool)));
    }
}
