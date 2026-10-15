package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HttpApiConnectionViewCompatibilityTest {
    @Test void existingConstructorsStayCompatibleAndBlockedDefaultsFailClosed() {
        var legacy = new HttpApiConsoleContracts.ConnectionView("ref", 7L, "fixture", "dev", null,
                "NONE", null, null, null, null, "UNCONFIGURED", null, null);
        assertTrue(legacy.configurationAllowed());
        var blocked = new HttpApiConsoleContracts.ConnectionView("ref", 7L, "fixture", "dev", null,
                "NONE", null, null, null, 1L, "BLOCKED", "unsupported", null, null);
        assertFalse(blocked.configurationAllowed());
    }

    @Test void repairPermissionIsAnExplicitWireFactIndependentOfInvocationStatus() throws Exception {
        var repairable = new HttpApiConsoleContracts.ConnectionView("ref", 7L, "fixture", "dev", "http://old.invalid",
                "NONE", null, null, null, 1L, "BLOCKED", "invalid saved origin", null, null, true);
        var json = new ObjectMapper();
        var wire = json.valueToTree(repairable);
        assertTrue(wire.path("configurationAllowed").asBoolean()); assertEquals("BLOCKED", wire.path("status").asText());
        assertEquals(repairable, json.treeToValue(wire, HttpApiConsoleContracts.ConnectionView.class));
    }
}
