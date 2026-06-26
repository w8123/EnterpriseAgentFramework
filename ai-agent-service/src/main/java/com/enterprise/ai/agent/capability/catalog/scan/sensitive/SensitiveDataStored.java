package com.enterprise.ai.agent.capability.catalog.scan.sensitive;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 持久化到 {@code scan_project_tool.sensitive_data_json} 的结构。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SensitiveDataStored {

    private List<String> types = new ArrayList<>();
    private String summary;
    private String scannedAt;
    private String modelName;

    public List<String> getTypes() {
        return types;
    }

    public void setTypes(List<String> types) {
        this.types = types == null ? new ArrayList<>() : types;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getScannedAt() {
        return scannedAt;
    }

    public void setScannedAt(String scannedAt) {
        this.scannedAt = scannedAt;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }
}
