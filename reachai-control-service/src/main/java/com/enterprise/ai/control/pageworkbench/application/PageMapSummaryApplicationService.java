package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService.LatestAppliedTaskArtifact;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageMapSummaryView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PageMapSummaryApplicationService {

    private final PageCatalogApplicationService pageCatalog;
    private final com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService
            taskService;

    public PageMapSummaryView summary(String projectCode) {
        LatestAppliedTaskArtifact taskState = taskService.latestAppliedTaskArtifact(
                projectCode,
                PageMapScanTaskProvider.TASK_KIND,
                100);
        return summary(taskState, null, projectCode);
    }

    public PageMapSummaryView summary(
            List<PageView> pages,
            List<TaskView> tasks) {
        LatestAppliedTaskArtifact taskState = taskService.latestAppliedTaskArtifact(
                tasks,
                PageMapScanTaskProvider.TASK_KIND,
                100);
        return summary(taskState, pages, null);
    }

    private PageMapSummaryView summary(
            LatestAppliedTaskArtifact taskState,
            List<PageView> loadedPages,
            String projectCode) {
        JsonNode result = taskState.applicationResult();
        boolean scanned = result != null
                || (loadedPages == null
                        ? pageCatalog.listPages(projectCode, false)
                        : loadedPages).stream()
                        .map(PageView::sourceType)
                        .anyMatch("AI_SCAN"::equals);
        return new PageMapSummaryView(
                scanned,
                text(result, "repositoryBranch"),
                text(result, "repositoryRevision"),
                dateTime(result, "scannedAt"),
                taskState.latestTaskId(),
                taskState.latestExecutionStatus());
    }

    private static String text(JsonNode value, String field) {
        if (value == null || !value.hasNonNull(field)) {
            return null;
        }
        String text = value.get(field).asText();
        return StringUtils.hasText(text) ? text : null;
    }

    private static LocalDateTime dateTime(JsonNode value, String field) {
        String text = text(value, field);
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
