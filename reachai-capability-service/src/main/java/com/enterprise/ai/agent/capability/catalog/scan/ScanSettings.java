package com.enterprise.ai.agent.capability.catalog.scan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScanSettings {

    private List<String> descriptionSourceOrder;
    private List<String> paramDescriptionSourceOrder;
    private Map<String, Boolean> descriptionSourceEnabled;
    private Map<String, Boolean> paramDescriptionSourceEnabled;
    private boolean onlyRestController = true;
    private List<String> httpMethodWhitelist;
    private String classIncludeRegex;
    private String classExcludeRegex;
    private boolean skipDeprecated;
    private ScanDefaultFlags defaultFlags;
    private String incrementalMode;

    public static ScanSettings defaults() {
        ScanSettings settings = new ScanSettings();
        settings.setDescriptionSourceOrder(List.of(
                "SWAGGER_API_OPERATION", "OPENAPI_OPERATION", "JAVADOC", "METHOD_NAME"));
        settings.setParamDescriptionSourceOrder(List.of(
                "PARAMETER_ANNO", "SCHEMA_ANNO", "JAVADOC_PARAM", "FIELD_NAME"));
        settings.setOnlyRestController(true);
        settings.setHttpMethodWhitelist(List.of());
        settings.setSkipDeprecated(false);
        settings.setDefaultFlags(ScanDefaultFlags.defaults());
        settings.setIncrementalMode("OFF");
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
