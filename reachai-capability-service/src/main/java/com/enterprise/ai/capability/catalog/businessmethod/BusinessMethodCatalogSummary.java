package com.enterprise.ai.capability.catalog.businessmethod;

/** Counts accepted method owners, independent of filters and derived invocation projections. */
public record BusinessMethodCatalogSummary(long total, long enabled, long disabled) {
}
