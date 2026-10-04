package com.enterprise.ai.reach.sdk.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A response shape discovered from a controller signature, without inventing a status code. */
public class ReachHttpApiResponse {

    private String status;
    private Map<String, Object> schema = new LinkedHashMap<String, Object>();
    private List<String> contentTypes = new ArrayList<String>();

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
