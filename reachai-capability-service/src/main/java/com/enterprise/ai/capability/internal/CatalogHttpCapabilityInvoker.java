package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Adapter for registered SDK/OpenAPI HTTP Tools; signing remains in the existing executor. */
@Component
public class CatalogHttpCapabilityInvoker implements CapabilityInvoker {

    private final CapabilityToolExecutionService executionService;

    public CatalogHttpCapabilityInvoker(CapabilityToolExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public boolean supports(CapabilityInvocationAsset asset) {
        return asset.source() == CapabilityInvocationAsset.Source.TOOL_CATALOG
                && "CATALOG_HTTP".equals(asset.executorType());
    }

    @Override
    public Map<String, Object> invoke(CapabilityInvocationAsset asset,
                                      CapabilityInvocationRequest request) {
        return executionService.execute(asset.qualifiedName(), request.toLegacyExecutionMap());
    }
}
