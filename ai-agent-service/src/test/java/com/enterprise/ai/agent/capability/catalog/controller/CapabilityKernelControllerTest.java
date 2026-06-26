package com.enterprise.ai.agent.capability.catalog.controller;

import com.enterprise.ai.agent.capability.catalog.controller.CapabilityKernelController;
import com.enterprise.ai.agent.runtime.host.controller.CapabilityRuntimeController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CapabilityKernelControllerTest {

    @Test
    void exposesInteractionAssetAndResumeRuntimeRoutes() throws Exception {
        Method listInteractions = CapabilityKernelController.class.getMethod("listInteractions", String.class);
        Method saveInteraction = CapabilityKernelController.class.getMethod("saveInteraction", String.class,
                com.enterprise.ai.agent.capability.InteractionDefinitionEntity.class);
        Method resumeInteraction = CapabilityRuntimeController.class.getMethod("resumeInteraction", String.class,
                CapabilityRuntimeController.RuntimeExecuteRequest.class);

        assertNotNull(listInteractions.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class));
        assertNotNull(saveInteraction.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class));
        assertNotNull(resumeInteraction.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class));
    }
}
