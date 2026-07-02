package com.enterprise.ai.capability;

import com.enterprise.ai.agent.registry.CapabilityApplyRecordEntity;
import com.enterprise.ai.agent.registry.CapabilityApplyRecordMapper;
import com.enterprise.ai.agent.registry.CapabilityDiffItemEntity;
import com.enterprise.ai.agent.registry.CapabilityDiffItemMapper;
import com.enterprise.ai.agent.registry.CapabilitySnapshotEntity;
import com.enterprise.ai.agent.registry.CapabilitySnapshotMapper;
import com.enterprise.ai.agent.registry.CapabilitySyncLogEntity;
import com.enterprise.ai.agent.registry.CapabilitySyncLogMapper;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.enterprise.ai.agent.registry.RegistryContracts;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityRegistryPersistenceContractTest {

    @Test
    void registryPersistenceContractsLiveInCapabilityServiceModule() {
        assertEquals("com.enterprise.ai.agent.registry", CapabilityApplyRecordEntity.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.registry", CapabilityDiffItemEntity.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.registry", CapabilitySnapshotEntity.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.registry", CapabilitySyncLogEntity.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.registry", ProjectInstanceEntity.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.registry", RegistryContracts.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.registry", RegistryCredentialEntity.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.registry", RegistrySecurityService.class.getPackageName());
    }

    @Test
    void registryMappersRemainMapperInterfaces() {
        assertTrue(CapabilityApplyRecordMapper.class.isInterface());
        assertTrue(CapabilityDiffItemMapper.class.isInterface());
        assertTrue(CapabilitySnapshotMapper.class.isInterface());
        assertTrue(CapabilitySyncLogMapper.class.isInterface());
        assertTrue(ProjectInstanceMapper.class.isInterface());
        assertTrue(RegistryCredentialMapper.class.isInterface());
    }
}
