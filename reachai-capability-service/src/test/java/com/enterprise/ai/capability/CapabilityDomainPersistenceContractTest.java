package com.enterprise.ai.capability;

import com.enterprise.ai.agent.capability.catalog.config.DomainProperties;
import com.enterprise.ai.agent.capability.catalog.controller.DomainController;
import com.enterprise.ai.agent.capability.catalog.domain.DomainAssignmentEntity;
import com.enterprise.ai.agent.capability.catalog.domain.DomainAssignmentMapper;
import com.enterprise.ai.agent.capability.catalog.domain.DomainAssignmentService;
import com.enterprise.ai.agent.capability.catalog.domain.DomainClassifier;
import com.enterprise.ai.agent.capability.catalog.domain.DomainDefEntity;
import com.enterprise.ai.agent.capability.catalog.domain.DomainDefMapper;
import com.enterprise.ai.agent.capability.catalog.domain.KeywordDomainClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityDomainPersistenceContractTest {

    @Test
    void domainContractsLiveInCapabilityServiceModule() {
        assertEquals("com.enterprise.ai.agent.capability.catalog.domain", DomainAssignmentEntity.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.capability.catalog.domain", DomainDefEntity.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.capability.catalog.domain", DomainAssignmentService.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.capability.catalog.domain", DomainClassifier.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.capability.catalog.domain", KeywordDomainClassifier.class.getPackageName());
        assertEquals("com.enterprise.ai.agent.capability.catalog.config", DomainProperties.class.getPackageName());
    }

    @Test
    void domainMappersRemainMapperInterfaces() {
        assertTrue(DomainAssignmentMapper.class.isInterface());
        assertTrue(DomainDefMapper.class.isInterface());
    }

    @Test
    void domainControllerOwnsDomainsRouteLocally() {
        RequestMapping mapping = DomainController.class.getAnnotation(RequestMapping.class);

        assertArrayEquals(new String[] {"/api/domains"}, mapping.value());
    }
}
