package com.enterprise.ai.control.pageworkbench.application;

import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Model catalog reads required by Page Workbench authoring and readiness. */
public interface PageWorkbenchModelPort {

    ResponseEntity<Map<String, Object>> list(
            String projectCode,
            String modelType,
            String provider);

    ResponseEntity<Map<String, Object>> getInternal(String id);
}
