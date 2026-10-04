package com.enterprise.ai.reach.sdk.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A request parameter of a discovered HTTP operation, independent of a business capability. */
public class ReachHttpApiParameter {

    private String name;
    private String location;
    private boolean required;
    private Map<String, Object> schema = new LinkedHashMap<String, Object>();
    private List<String> contentTypes = new ArrayList<String>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public Map<String, Object> getSchema() {
        return Collections.unmodifiableMap(schema);
    }

    public void setSchema(Map<String, Object> schema) {
        this.schema = schema == null ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(schema);
    }

    public List<String> getContentTypes() {
        return Collections.unmodifiableList(contentTypes);
    }

    public void setContentTypes(List<String> contentTypes) {
        this.contentTypes = contentTypes == null ? new ArrayList<String>()
                : new ArrayList<String>(contentTypes);
    }
}
