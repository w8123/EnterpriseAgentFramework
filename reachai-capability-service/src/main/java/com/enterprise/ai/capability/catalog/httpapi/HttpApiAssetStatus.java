package com.enterprise.ai.capability.catalog.httpapi;

/** Aggregate state derived from active source bindings and a future accepted contract. */
public enum HttpApiAssetStatus {
    DISCOVERED,
    ACCEPTED,
    CONTRACT_DRIFT,
    CONFLICT,
    SOURCE_MISSING
}
