package com.enterprise.ai.capability;

import com.enterprise.ai.agent.capability.CapabilityAssetService;
import com.enterprise.ai.agent.capability.CapabilityModuleEntity;
import com.enterprise.ai.agent.capability.CapabilityModuleMapper;
import com.enterprise.ai.agent.capability.CompositionDefinitionEntity;
import com.enterprise.ai.agent.capability.CompositionDefinitionMapper;
import com.enterprise.ai.agent.capability.InteractionDefinitionEntity;
import com.enterprise.ai.agent.capability.InteractionDefinitionMapper;
import com.enterprise.ai.agent.capability.ToolAssetEntity;
import com.enterprise.ai.agent.capability.ToolAssetMapper;
import com.enterprise.ai.agent.capability.catalog.controller.CapabilityKernelController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityAssetPersistenceContractTest {

    @Test
    void capabilityAssetContractsLiveInCapabilityServiceModule() {
        assertEquals("com.enterprise.ai.agent.capability", CapabilityAssetService.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.capability", CapabilityModuleEntity.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.capability", ToolAssetEntity.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.capability", CompositionDefinitionEntity.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.capability", InteractionDefinitionEntity.class.getPackageName());
    }

    @Test
    void capabilityAssetMappersRemainMapperInterfaces() {
        assertTrue(CapabilityModuleMapper.class.isInterface());
        assertTrue(ToolAssetMapper.class.isInterface());
        assertTrue(CompositionDefinitionMapper.class.isInterface());
        assertTrue(InteractionDefinitionMapper.class.isInterface());
    }

    @Test
    void capabilityKernelControllerOwnsCapabilitiesRouteLocally() {
        RequestMapping mapping = CapabilityKernelController.class.getAnnotation(RequestMapping.class);

        assertArrayEquals(new String[] {"/api/capabilities"}, mapping.value());
    }
}
