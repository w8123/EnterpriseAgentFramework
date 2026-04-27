package com.enterprise.ai.agent.scan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 项目级扫描设置，存于 {@code scan_project.scan_settings} JSON 列。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScanSettings {

    /** 描述源优先级，见 skills 端约定：JAVADOC / SWAGGER_API_OPERATION / OPENAPI_OPERATION / METHOD_NAME */
    private List<String> descriptionSourceOrder;
    /** 参数描述源：JAVADOC_PARAM / SCHEMA_ANNO / PARAMETER_ANNO / FIELD_NAME */
    private List<String> paramDescriptionSourceOrder;
    /**
     * 各接口说明源是否参与解析；缺省或 key 未填视为 true。false 时该源不生效（优先级列表仍保留作排序）。
     */
    private Map<String, Boolean> descriptionSourceEnabled;
    private Map<String, Boolean> paramDescriptionSourceEnabled;

    private boolean onlyRestController = true;
    private List<String> httpMethodWhitelist;
    private String classIncludeRegex;
    private String classExcludeRegex;

    private boolean skipDeprecated;

    private ScanDefaultFlags defaultFlags;

    /** OFF / MTIME / GIT_DIFF */
    private String incrementalMode;

    public static ScanSettings defaults() {
        ScanSettings s = new ScanSettings();
        s.setDescriptionSourceOrder(List.of(
                "JAVADOC", "SWAGGER_API_OPERATION", "OPENAPI_OPERATION", "METHOD_NAME"));
        s.setParamDescriptionSourceOrder(List.of(
                "JAVADOC_PARAM", "SCHEMA_ANNO", "PARAMETER_ANNO", "FIELD_NAME"));
        s.setOnlyRestController(true);
        s.setHttpMethodWhitelist(List.of());
        s.setClassIncludeRegex(null);
        s.setClassExcludeRegex(null);
        s.setSkipDeprecated(false);
        s.setDefaultFlags(ScanDefaultFlags.defaults());
        s.setIncrementalMode("OFF");
        return s;
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

    public boolean isOnlyRestController() {
        return onlyRestController;
    }

    public void setOnlyRestController(boolean onlyRestController) {
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

    public String getIncrementalMode() {
        return incrementalMode;
    }

    public void setIncrementalMode(String incrementalMode) {
        this.incrementalMode = incrementalMode;
    }
}
