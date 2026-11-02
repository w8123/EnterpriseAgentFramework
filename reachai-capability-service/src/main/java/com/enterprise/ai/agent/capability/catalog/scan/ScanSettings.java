package com.enterprise.ai.agent.capability.catalog.scan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 项目来源设置：说明来源顺序供 SDK Starter 拉取，其余用于 OpenAPI 文档导入。
 * 旧版源码扫描字段（Javadoc、类过滤、增量模式等）已移除，读取旧 JSON 时忽略。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScanSettings {

    private List<String> descriptionSourceOrder;
    private List<String> paramDescriptionSourceOrder;
    private Map<String, Boolean> descriptionSourceEnabled;
    private Map<String, Boolean> paramDescriptionSourceEnabled;
    private List<String> httpMethodWhitelist;
    private boolean skipDeprecated;
    private ScanDefaultFlags defaultFlags;

    public static ScanSettings defaults() {
        ScanSettings settings = new ScanSettings();
        settings.setDescriptionSourceOrder(List.of("SWAGGER_API_OPERATION", "OPENAPI_OPERATION", "METHOD_NAME"));
        settings.setParamDescriptionSourceOrder(List.of("PARAMETER_ANNO", "SCHEMA_ANNO", "FIELD_NAME"));
        settings.setHttpMethodWhitelist(List.of());
        settings.setSkipDeprecated(false);
        settings.setDefaultFlags(ScanDefaultFlags.defaults());
        return settings;
    }

    public List<String> getDescriptionSourceOrder() {
        return descriptionSourceOrder;
    }

    public void setDescriptionSourceOrder(List<String> descriptionSourceOrder) {
        this.descriptionSourceOrder = descriptionSourceOrder;
    }

    public List<String> getParamDescriptionSourceOrder() {
        return paramDescriptionSourceOrder;
    }

    public void setParamDescriptionSourceOrder(List<String> paramDescriptionSourceOrder) {
        this.paramDescriptionSourceOrder = paramDescriptionSourceOrder;
    }

    public Map<String, Boolean> getDescriptionSourceEnabled() {
        return descriptionSourceEnabled;
    }

    public void setDescriptionSourceEnabled(Map<String, Boolean> descriptionSourceEnabled) {
        this.descriptionSourceEnabled = descriptionSourceEnabled;
    }

    public Map<String, Boolean> getParamDescriptionSourceEnabled() {
        return paramDescriptionSourceEnabled;
    }

    public void setParamDescriptionSourceEnabled(Map<String, Boolean> paramDescriptionSourceEnabled) {
        this.paramDescriptionSourceEnabled = paramDescriptionSourceEnabled;
    }

    public List<String> getHttpMethodWhitelist() {
        return httpMethodWhitelist;
    }

    public void setHttpMethodWhitelist(List<String> httpMethodWhitelist) {
        this.httpMethodWhitelist = httpMethodWhitelist;
    }

    public boolean isSkipDeprecated() {
        return skipDeprecated;
    }

    public void setSkipDeprecated(boolean skipDeprecated) {
        this.skipDeprecated = skipDeprecated;
    }

    public ScanDefaultFlags getDefaultFlags() {
        return defaultFlags;
    }

    public void setDefaultFlags(ScanDefaultFlags defaultFlags) {
        this.defaultFlags = defaultFlags;
    }
}
