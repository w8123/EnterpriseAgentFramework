package com.enterprise.ai.capability.catalog.httpapi;

/** Source adapters planned for the API path. 3A-1 persists facts only; it does not invoke them. */
public enum HttpApiSourceKind {
    STARTER_MVC,
    CONTROLLER_SCAN,
    OPENAPI_SCAN,
    API_MARKET_OPERATION
}
