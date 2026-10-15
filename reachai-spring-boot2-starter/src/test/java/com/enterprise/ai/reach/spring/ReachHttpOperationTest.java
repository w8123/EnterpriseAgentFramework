package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachHttpOperation;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReachHttpOperationTest {
    @Test
    void marksLegacyGetThatWritesWithoutManufacturingJavaAssets() {
        ReachCapabilityBeanScanner.ScanResult inventory = new ReachCapabilityBeanScanner(
                new Object[]{new LegacyController()}).scanResult();
        assertTrue(inventory.getBusinessMethods().isEmpty());
        assertEquals(3, inventory.getHttpApis().size());
        assertEquals("WRITE", find(inventory.getHttpApis(), "/send").getSideEffect());
        assertEquals("IRREVERSIBLE", find(inventory.getHttpApis(), "/delete").getSideEffect());
        assertEquals("READ_ONLY", find(inventory.getHttpApis(), "/read").getSideEffect());
    }

    @Test
    void methodSideEffectOverridesTheClassDefault() {
        List<ReachHttpApiDescriptor> inventory = ReachSpringMvcEndpointScanner.scanClass(ClassDefaultController.class);
        assertEquals("WRITE", find(inventory, "/default").getSideEffect());
        assertEquals("READ_ONLY", find(inventory, "/override").getSideEffect());
    }

    @Test
    void effectDeclarationDoesNotAlterStableHttpSourceIdentity() {
        ReachHttpApiDescriptor operation = find(ReachSpringMvcEndpointScanner.scanClass(LegacyController.class), "/send");
        assertTrue(operation.getSourceKey().startsWith("mvc:"));
        assertTrue(operation.getSourceLocation().contains("#send("));
        assertEquals("GET", operation.getHttpMethod());
        assertEquals("WRITE", operation.getSideEffect());
    }

    private static ReachHttpApiDescriptor find(List<ReachHttpApiDescriptor> inventory, String path) {
        return inventory.stream().filter(item -> path.equals(item.getEndpointPath())).findFirst().orElseThrow();
    }

    @RestController
    static class LegacyController {
        @GetMapping("/send") @ReachHttpOperation(reason = "Sends a real message")
        public String send() { return ""; }
        @GetMapping("/delete") @ReachHttpOperation(sideEffect = ReachSideEffectLevel.IRREVERSIBLE, reason = "Permanently removes records")
        public String delete() { return ""; }
        @GetMapping("/read") public String read() { return ""; }
    }
    @RestController @ReachHttpOperation
    static class ClassDefaultController {
        @GetMapping("/default") public String defaultEffect() { return ""; }
        @GetMapping("/override") @ReachHttpOperation(sideEffect = ReachSideEffectLevel.READ)
        public String override() { return ""; }
    }
}
