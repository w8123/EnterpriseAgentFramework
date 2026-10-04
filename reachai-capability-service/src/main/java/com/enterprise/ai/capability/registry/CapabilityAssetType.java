package com.enterprise.ai.capability.registry;

import java.util.Map;

/**
 * Server-owned validation for the source metadata contract. Legacy clients
 * omit the field; their catalog projections use {@link #UNCLASSIFIED} without
 * changing source metadata or its contract hash.
 */
enum CapabilityAssetType {
    BUSINESS_METHOD,
    HTTP_API,
    UNCLASSIFIED;

    static CapabilityAssetType fromMetadata(Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey("assetType") || metadata.get("assetType") == null) {
            return UNCLASSIFIED;
        }
        Object raw = metadata.get("assetType");
        if (!(raw instanceof String)) {
            throw invalid();
        }
        String value = ((String) raw).trim();
        if (value.isEmpty()) {
            return UNCLASSIFIED;
        }
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
