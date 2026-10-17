package com.enterprise.ai.model.catalog;

/**
 * 目录设置。字段为 null 表示不修改；{@code analyzerModelInstanceId} 传空字符串表示清除界面设置，
 * 回退到部署环境变量 {@code MODEL_CATALOG_ANALYZER_MODEL_INSTANCE_ID}。
 */
public record ModelCatalogSettingsRequest(Boolean autoSyncEnabled, String analyzerModelInstanceId) {
}
