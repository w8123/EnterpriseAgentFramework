package com.enterprise.ai.bizindex.service;

import com.enterprise.ai.bizindex.domain.dto.BizUpsertRequest;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndex;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndexRecord;
import com.enterprise.ai.runtime.contract.memory.BusinessMemoryReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessMemoryIndexPolicyTest {

    @Test
    void legacyIndexIsNotEligibleForAgentMemoryByDefault() {
        BusinessIndex index = new BusinessIndex();
        index.setIndexCode("legacy_index");
        index.setAgentMemoryEnabled(false);
        BusinessIndexRecord record = new BusinessIndexRecord();
        record.setBizId("100");
        record.setSourceVersion("v1");

        assertTrue(BusinessMemoryIndexPolicy.reference(index, record).isEmpty());
    }

    @Test
    void enabledIndexRequiresScopeResolverAndSourceVersion() {
        BusinessIndex incomplete = new BusinessIndex();
        incomplete.setAgentMemoryEnabled(true);
        assertThrows(IllegalArgumentException.class,
                () -> BusinessMemoryIndexPolicy.validateIndex(incomplete));

        BusinessIndex index = validIndex();
        BizUpsertRequest request = new BizUpsertRequest();
        request.setBizId("C-100");
        assertThrows(IllegalArgumentException.class,
                () -> BusinessMemoryIndexPolicy.validateUpsert(index, request));
    }

    @Test
    void emitsHydrationOnlyReferenceForEligibleProjection() {
        BusinessIndex index = validIndex();
        BusinessIndexRecord record = new BusinessIndexRecord();
        record.setBizId("C-100");
        record.setBizType("customer");
        record.setSourceVersion("v42");

        BusinessMemoryReference reference = BusinessMemoryIndexPolicy.reference(index, record).orElseThrow();

        assertEquals("C-100", reference.resourceId());
        assertEquals("v42", reference.sourceVersion());
        assertEquals("crm.customer.get", reference.resolverCapabilityKey());
        assertFalse(reference.authoritative());
        assertTrue(reference.hydrationRequired());
    }

    private static BusinessIndex validIndex() {
        BusinessIndex index = new BusinessIndex();
        index.setIndexCode("crm_customer");
        index.setTenantId("tenant-a");
        index.setProjectCode("project-a");
        index.setSourceSystem("crm");
        index.setResolverCapabilityKey("crm.customer.get");
        index.setAgentMemoryEnabled(true);
        return index;
    }
}
