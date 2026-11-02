package com.enterprise.ai.control.platform;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PlatformEmbedAgentLookupTest {
    @Test void requiresAnExplicitMatchingAgentProject() {
        assertTrue(PlatformEmbedAgentLookup.find(List.of(Map.of("id", "bot", "enabled", true)), "erp", "bot").isEmpty());
        assertTrue(PlatformEmbedAgentLookup.find(List.of(Map.of("id", "bot", "projectCode", "crm")), "erp", "bot").isEmpty());
        assertTrue(PlatformEmbedAgentLookup.find(List.of(Map.of("id", "bot", "projectCode", "erp", "enabled", false)), "erp", "bot").isEmpty());
        assertTrue(PlatformEmbedAgentLookup.find(List.of(Map.of("id", "bot", "projectCode", "erp", "enabled", true)), "erp", "bot").isPresent());
    }
}
