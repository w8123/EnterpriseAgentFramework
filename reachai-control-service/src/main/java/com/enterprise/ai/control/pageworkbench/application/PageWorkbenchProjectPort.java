package com.enterprise.ai.control.pageworkbench.application;

import java.util.List;
import java.util.Map;

/** Project and capability facts required by the Page Workbench. */
public interface PageWorkbenchProjectPort {

    Map<String, Object> getProjectById(Long projectId);

    Map<String, Object> getOnboardingProjectById(Long projectId);

    List<Map<String, Object>> listProjectTools(Long projectId);
}
