package com.enterprise.ai.capability.registry;

import java.util.Map;

/**
 * Explicit source classification. Missing or unknown classifications cannot
 * create an accepted asset or an invocation projection.
 */
public enum CapabilityAssetType {
    BUSINESS_METHOD,
    HTTP_API;

    static CapabilityAssetType fromMetadata(Map<String, Object> metadata) {
        return fromValue(metadata == null ? null : metadata.get("assetType"));
    }

    static CapabilityAssetType fromValue(Object raw) {
        if (!(raw instanceof String)) {
            throw invalid();
        }
        String value = ((String) raw).trim();
        return switch (value) {
            case "BUSINESS_METHOD" -> BUSINESS_METHOD;
            case "HTTP_API" -> HTTP_API;
            default -> throw invalid();
        };
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("能力资产类型无效，仅支持 BUSINESS_METHOD 或 HTTP_API");
    }
}
