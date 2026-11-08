package com.enterprise.ai.control.aicoding.retrofit;

import com.enterprise.ai.control.aicoding.api.AiCodingTaskConsoleController;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDetailView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One read for the retrofit page: the candidate list, the developer's choice, the latest report and
 * the live platform state of every declared capability. Access is the console task's own check.
 */
@RestController
@RequiredArgsConstructor
public class CapabilityRetrofitConsoleController {

    private final AiCodingTaskConsoleController console;
    private final CapabilityRetrofitTaskProvider provider;
    private final ObjectMapper objectMapper;

    @GetMapping("/api/ai-coding-console/tasks/{taskId}/capability-retrofit")
    public ResponseEntity<Map<String, Object>> overview(@PathVariable String taskId, HttpServletRequest request) {
        TaskDetailView detail = console.detail(taskId, request).getBody();
        if (detail == null || detail.task() == null || !CapabilityRetrofitProtocol.TASK_KIND.equals(detail.task().taskKind())) {
            throw new IllegalArgumentException("不是业务能力改造任务：" + taskId);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(overview(detail));
    }

    Map<String, Object> overview(TaskDetailView detail) {
        TaskView task = detail.task();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "reachai.capability-retrofit-overview.v1");
        body.put("taskId", task.taskId());
        body.put("executionStatus", task.executionStatus());
        CapabilityRetrofitTaskProvider.Selection selection = provider.selection(task.taskId());
        Map<String, Object> selectionView = new LinkedHashMap<>();
        if (selection != null) {
            selectionView.put("questionId", selection.questionId());
            selectionView.put("status", selection.status());
            selectionView.put("answered", selection.answered());
            selectionView.put("selected", selection.selected());
            selectionView.put("answeredBy", selection.answeredBy());
            selectionView.put("candidates", selection.candidates());
        }
        body.put("selection", selection == null ? null : selectionView);
        JsonNode report = latestReport(detail.artifacts());
        body.put("report", report);
        String mode = report == null ? "SDK_DECLARATION" : CapabilityRetrofitProtocol.text(report.get("mode"));
        body.put("mode", mode);
        List<String> names = CapabilityRetrofitTaskProvider.declaredNames(report);
        body.put("capabilityStates", provider.capabilityStates(descriptor(task), mode, names));
        return body;
    }

    private static JsonNode latestReport(List<ArtifactView> artifacts) {
        if (artifacts == null) return null;
        return artifacts.stream()
                .filter(artifact -> CapabilityRetrofitTaskProvider.CONTRACT_KEY.equals(artifact.contractKey()))
                .filter(artifact -> artifact.applicationResult() != null && artifact.applicationResult().has("codingReport"))
                .max(Comparator.comparing(ArtifactView::artifactId))
                .map(artifact -> artifact.applicationResult().get("codingReport"))
                .orElse(null);
    }

    private TaskDescriptor descriptor(TaskView task) {
        return new TaskDescriptor(task.taskId(), task.projectId(), task.projectCode(), task.capabilityKey(),
                task.taskKind(), task.protocolVersion(), task.executorProvider(), task.title(), task.objective(),
                task.accessMode(), task.executionStatus(), objectMapper.createObjectNode(), task.targets(),
                task.startedAt(), task.createdAt());
    }
}
