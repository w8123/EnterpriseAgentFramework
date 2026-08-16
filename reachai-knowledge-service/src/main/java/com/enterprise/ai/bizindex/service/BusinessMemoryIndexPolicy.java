package com.enterprise.ai.bizindex.service;

import com.enterprise.ai.bizindex.domain.dto.BizUpsertRequest;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndex;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndexRecord;
import com.enterprise.ai.runtime.contract.memory.BusinessMemoryReference;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/** Fail-closed eligibility rules for exposing a business search hit to an Agent. */
public final class BusinessMemoryIndexPolicy {

    private BusinessMemoryIndexPolicy() {
    }

    public static void validateIndex(BusinessIndex index) {
        if (index == null || !Boolean.TRUE.equals(index.getAgentMemoryEnabled())) return;
        require(index.getTenantId(), "tenantId");
        require(index.getProjectCode(), "projectCode");
        require(index.getSourceSystem(), "sourceSystem");
        require(index.getResolverCapabilityKey(), "resolverCapabilityKey");
        // Constructor validation also enforces identifier syntax without creating a usable reference.
        BusinessMemoryReference.create(index.getTenantId(), index.getProjectCode(), index.getSourceSystem(),
                "validation", "validation", "validation", index.getResolverCapabilityKey(),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    public static void validateUpsert(BusinessIndex index, BizUpsertRequest request) {
        validateIndex(index);
        if (index == null || !Boolean.TRUE.equals(index.getAgentMemoryEnabled())) return;
        if (request == null) throw new IllegalArgumentException("business index upsert is required");
        require(request.getSourceVersion(), "sourceVersion");
    }

    public static Optional<BusinessMemoryReference> reference(
            BusinessIndex index,
            BusinessIndexRecord record) {
        if (index == null || record == null || !Boolean.TRUE.equals(index.getAgentMemoryEnabled())
                || !StringUtils.hasText(record.getSourceVersion())) {
            return Optional.empty();
        }
        try {
            validateIndex(index);
            String resourceType = StringUtils.hasText(record.getBizType())
                    ? record.getBizType().trim() : "record";
            return Optional.of(BusinessMemoryReference.create(
                    index.getTenantId(), index.getProjectCode(), index.getSourceSystem(),
                    resourceType, record.getBizId(), record.getSourceVersion(),
                    index.getResolverCapabilityKey(), OffsetDateTime.now(ZoneOffset.UTC)));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static String require(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required when agentMemoryEnabled=true");
        }
        return value.trim();
    }
}
