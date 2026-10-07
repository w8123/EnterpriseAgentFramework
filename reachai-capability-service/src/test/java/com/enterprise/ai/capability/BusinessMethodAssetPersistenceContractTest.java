package com.enterprise.ai.capability;

import com.baomidou.mybatisplus.annotation.TableName;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetMapper;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodRevisionEntity;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodRevisionMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessMethodAssetPersistenceContractTest {

    @Test
    void sourceAssetsAndAcceptedRevisionsHaveDedicatedOwnerTables() {
        assertEquals("capability_business_method_asset", BusinessMethodAssetEntity.class.getAnnotation(TableName.class).value());
        assertEquals("capability_business_method_revision", BusinessMethodRevisionEntity.class.getAnnotation(TableName.class).value());
        assertTrue(BusinessMethodAssetMapper.class.isInterface());
        assertTrue(BusinessMethodRevisionMapper.class.isInterface());
    }

    @Test
    void retiredIndependentAssetModelsCannotBeLoaded() {
        for (String name : new String[] {"CapabilityAssetService", "CapabilityModuleEntity", "CapabilityModuleMapper",
                "ToolAssetEntity", "ToolAssetMapper", "CompositionDefinitionEntity", "CompositionDefinitionMapper",
                "InteractionDefinitionEntity", "InteractionDefinitionMapper"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName("com.enterprise.ai.agent.capability." + name));
        }
    }

    @Test
    void sourceAssetOwnerHasNoGenericKernelOrCompositionCrudController() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.enterprise.ai.agent.capability.catalog.controller.CapabilityKernelController"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.enterprise.ai.capability.internal.CapabilityCompositionInternalController"));
    }
}
