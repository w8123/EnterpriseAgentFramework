package com.enterprise.ai.bizindex.service;

import com.enterprise.ai.bizindex.domain.dto.BizUpsertRequest;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndex;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndexRecord;
import com.enterprise.ai.runtime.contract.bizindex.BusinessIndexReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessIndexResolutionPolicyTest {

    @Test
    void legacyIndexIsNotEligibleForAgentReferenceByDefault() {
        BusinessIndex index = new BusinessIndex();
        index.setIndexCode("legacy_index");
        index.setAgentReferenceEnabled(false);
        BusinessIndexRecord record = new BusinessIndexRecord();
        record.setBizId("100");
        record.setSourceVersion("v1");

        assertTrue(BusinessIndexResolutionPolicy.reference(index, record).isEmpty());
    }

    @Test
    void enabledIndexRequiresScopeResolverAndSourceVersion() {
        BusinessIndex incomplete = new BusinessIndex();
        incomplete.setAgentReferenceEnabled(true);
        assertThrows(IllegalArgumentException.class,
                () -> BusinessIndexResolutionPolicy.validateIndex(incomplete));

        BusinessIndex index = validIndex();
        BizUpsertRequest request = new BizUpsertRequest();
        request.setBizId("C-100");
        assertThrows(IllegalArgumentException.class,
                () -> BusinessIndexResolutionPolicy.validateUpsert(index, request));
    }

    @Test
    void emitsHydrationOnlyReferenceForEligibleProjection() {
        BusinessIndex index = validIndex();
        BusinessIndexRecord record = new BusinessIndexRecord();
        record.setBizId("C-100");
        record.setBizType("customer");
        record.setSourceVersion("v42");

        BusinessIndexReference reference = BusinessIndexResolutionPolicy.reference(index, record).orElseThrow();

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
        index.setAgentReferenceEnabled(true);
        return index;
    }
}
