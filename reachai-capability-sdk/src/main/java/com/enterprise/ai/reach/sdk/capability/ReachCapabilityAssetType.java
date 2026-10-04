package com.enterprise.ai.reach.sdk.capability;

/**
 * Stable source classification carried with a capability registration.
 * The platform owns the legacy {@code UNCLASSIFIED} fallback rather than
 * asking current SDKs to declare it.
 */
public enum ReachCapabilityAssetType {
    BUSINESS_METHOD,
    HTTP_API
}
