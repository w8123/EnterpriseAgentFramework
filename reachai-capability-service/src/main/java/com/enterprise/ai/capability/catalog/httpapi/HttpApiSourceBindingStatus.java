package com.enterprise.ai.capability.catalog.httpapi;

/** State of one observed source binding; it never represents acceptance or authorization. */
public enum HttpApiSourceBindingStatus {
    DISCOVERED,
    EQUIVALENT,
    CONFLICT,
    REMOVED
}
