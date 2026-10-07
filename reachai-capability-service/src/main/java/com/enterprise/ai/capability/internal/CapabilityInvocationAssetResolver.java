package com.enterprise.ai.capability.internal;

import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodCatalogService;
import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Resolves owner assets; independent Tool/Kernel stores are no longer invocation fallbacks. */
@Component
@RequiredArgsConstructor
public class CapabilityInvocationAssetResolver {
    private final BusinessMethodCatalogService businessMethods;

    public CapabilityInvocationAsset require(String qualifiedName) {
        if (!StringUtils.hasText(qualifiedName)) throw missing(qualifiedName);
        var method = businessMethods.find(qualifiedName.trim()).orElseThrow(() -> missing(qualifiedName));
        var asset = method.asset();
        return new CapabilityInvocationAsset(CapabilityInvocationAsset.Source.BUSINESS_METHOD,
                asset.getQualifiedName(), asset.getInvocationName(), method.declaration().title(),
                asset.getProjectCode(), "CATALOG_HTTP", method.declaration().sideEffect(),
                Boolean.TRUE.equals(asset.getEnabled()));
    }

    private CapabilityInvocationPolicyException missing(String reference) {
        return new CapabilityInvocationPolicyException("CAPABILITY_TOOL_NOT_FOUND",
                CapabilityInvocationFailureCategory.NOT_FOUND, "Business method not found: " + reference);
    }
}
