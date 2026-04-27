package com.enterprise.ai.text.tooling.scanner;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 扫描可选项，与 {@code ai-agent-service} 中 Feign 契约字段一致，JSON 反序列化时均可缺省。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScanOptions {

    public static final String MODE_OFF = "OFF";
    public static final String MODE_MTIME = "MTIME";
    public static final String MODE_GIT = "GIT_DIFF";

    public static final String SRC_JAVADOC = "JAVADOC";
    public static final String SRC_SWAGGER_API = "SWAGGER_API_OPERATION";
    public static final String SRC_OPENAPI_OP = "OPENAPI_OPERATION";
    public static final String SRC_METHOD_NAME = "METHOD_NAME";

    public static final String PS_JD = "JAVADOC_PARAM";
    public static final String PS_SCHEMA = "SCHEMA_ANNO";
    public static final String PS_PARAM = "PARAMETER_ANNO";
    public static final String PS_FIELD = "FIELD_NAME";

    private List<String> descriptionSourceOrder;
    private List<String> paramDescriptionSourceOrder;
    private Map<String, Boolean> descriptionSourceEnabled;
    private Map<String, Boolean> paramDescriptionSourceEnabled;
    private Boolean onlyRestController;
    private List<String> httpMethodWhitelist;
    private String classIncludeRegex;
    private String classExcludeRegex;
    private Boolean skipDeprecated;
    private String incrementalMode;

    public static ScanOptions empty() {
        return new ScanOptions();
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

    public Boolean getOnlyRestController() {
        return onlyRestController;
    }

    public void setOnlyRestController(Boolean onlyRestController) {
        this.onlyRestController = onlyRestController;
    }

    public List<String> getHttpMethodWhitelist() {
        return httpMethodWhitelist;
    }

    public void setHttpMethodWhitelist(List<String> httpMethodWhitelist) {
        this.httpMethodWhitelist = httpMethodWhitelist;
    }

    public String getClassIncludeRegex() {
        return classIncludeRegex;
    }

    public void setClassIncludeRegex(String classIncludeRegex) {
        this.classIncludeRegex = classIncludeRegex;
    }

    public String getClassExcludeRegex() {
        return classExcludeRegex;
    }

    public void setClassExcludeRegex(String classExcludeRegex) {
        this.classExcludeRegex = classExcludeRegex;
    }

    public Boolean getSkipDeprecated() {
        return skipDeprecated;
    }

    public void setSkipDeprecated(Boolean skipDeprecated) {
        this.skipDeprecated = skipDeprecated;
    }

    public String getIncrementalMode() {
        return incrementalMode;
    }

    public void setIncrementalMode(String incrementalMode) {
        this.incrementalMode = incrementalMode;
    }
}
