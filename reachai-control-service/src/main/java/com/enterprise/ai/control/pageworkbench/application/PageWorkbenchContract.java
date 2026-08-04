package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingDeliveryEvidence.BrowserVerification;
import com.enterprise.ai.control.aicoding.domain.AiCodingDeliveryEvidence.ReportedCheck;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

public final class PageWorkbenchContract {

    private PageWorkbenchContract() {
    }

    public record ManualPageCommand(
            Long projectId,
            String pageKey,
            String moduleKey,
            String moduleName,
            String name,
            String description,
            String routePattern,
            String componentPath,
            List<ResourceInput> resources,
            List<ActionInput> actions) {
    }

    public record ResourceInput(
            String resourceType,
            String resourceKey,
            String displayName,
            String location,
            String httpMethod,
            String accessMode,
            JsonNode metadata) {
    }

    public record ActionInput(
            String actionKey,
            String title,
            String description,
            String actionType,
            String riskLevel,
            Boolean confirmRequired,
            String permissionKey,
            JsonNode inputSchema,
            JsonNode outputSchema,
            JsonNode sampleArgs,
            List<String> allowedAgentIds,
            String implementationRef,
            JsonNode metadata) {
    }

    public record PageView(
            Long id,
            Long projectId,
            String projectCode,
            String pageKey,
            String moduleKey,
            String moduleName,
            String name,
            String description,
            String routePattern,
            String componentPath,
            String sourceType,
            String lifecycleStatus,
            LocalDateTime lastDiscoveredAt,
            LocalDateTime lastVerifiedAt,
            List<ResourceView> resources,
            List<ActionView> actions) {
    }

    public record ResourceView(
            Long id,
            String resourceType,
            String resourceKey,
            String displayName,
            String location,
            String httpMethod,
            String accessMode,
            JsonNode metadata) {
    }

    public record ActionView(
            Long id,
            Long pageId,
            Long projectId,
            String projectCode,
            String pageKey,
            String actionKey,
            String title,
            String description,
            String actionType,
            String riskLevel,
            boolean confirmRequired,
            String permissionKey,
            JsonNode inputSchema,
            JsonNode outputSchema,
            JsonNode sampleArgs,
            List<String> allowedAgentIds,
            String implementationRef,
            String sourceType,
            String status,
            JsonNode metadata,
            LocalDateTime lastVerifiedAt) {
    }

    public record PageMapSummaryView(
            boolean scanned,
            String repositoryBranch,
            String repositoryRevision,
            LocalDateTime scannedAt,
            String taskId,
            String taskStatus) {
    }

    public record FindingView(
            Long id,
            String findingKey,
            Long pageId,
            String pageKey,
            String sourceTaskId,
            String category,
            String title,
            String confirmedFact,
            String technicalInference,
            String openQuestion,
            String useCase,
            String businessConfirmStatus,
            String technicalFeasibility,
            String operationRisk,
            String informationCompleteness,
            String readScope,
            String writeScope,
            String implementationReference,
            String acceptanceCriteria,
            List<String> relatedPages,
            JsonNode evidence,
            JsonNode codeReferences,
            String status,
            String reviewedBy,
            LocalDateTime reviewedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record PageMapPayload(
            String scanMode,
            String repositoryBranch,
            String repositoryRevision,
            LocalDateTime scannedAt,
            List<ModuleReport> modules,
            List<PageReport> pages) {
    }

    public record ModuleReport(String moduleKey, String name, String description) {
    }

    public record PageReport(
            String pageKey,
            String moduleKey,
            String moduleName,
            String name,
            String description,
            String routePattern,
            String componentPath,
            List<ResourceInput> resources,
            List<ActionInput> actions) {
    }

    public record PageAnalysisPayload(String pageKey, List<FindingReport> findings) {
    }

    public record FindingReport(
            String findingKey,
            String category,
            String title,
            String confirmedFact,
            String technicalInference,
            String openQuestion,
            String useCase,
            String businessConfirmStatus,
            String technicalFeasibility,
            String operationRisk,
            String informationCompleteness,
            String readScope,
            String writeScope,
            String implementationReference,
            String acceptanceCriteria,
            List<String> relatedPages,
            JsonNode evidence,
            JsonNode codeReferences) {
    }

    public record ImplementationPayload(
            String summary,
            List<String> changedFiles,
            List<ReportedCheck> tests,
            BrowserVerification browserVerification,
            String workflowId,
            List<String> remainingQuestions) {
    }

    public record WorkflowEngineeringPayload(
            String pageKey,
            String summary,
            WorkflowDraftInput workflow,
            List<String> selectedActionKeys,
            List<String> referencedFiles,
            List<String> acceptanceCriteria,
            List<String> remainingQuestions) {
    }

    public record WorkflowDraftInput(
            String name,
            String keySlug,
            String description,
            String defaultModelInstanceId,
            JsonNode graphSpec,
            JsonNode canvas) {
    }

    public record WorkflowEngineeringDraftView(
            String schema,
            String taskId,
            String pageKey,
            String summary,
            List<String> selectedActionKeys,
            List<String> referencedFiles,
            List<String> acceptanceCriteria,
            List<String> remainingQuestions,
            WorkflowDraftView workflow,
            WorkflowDraftValidationView validation) {
    }

    public record WorkflowDraftView(
            String id,
            String keySlug,
            String name,
            String status,
            LocalDateTime updatedAt) {
    }

    public record WorkflowDraftValidationView(
            boolean valid,
            List<ReleaseValidationItem> errors,
            List<ReleaseValidationItem> warnings) {
    }

    public record WorkflowDeliveryView(
            String schema,
            String taskId,
            String pageKey,
            String workflowId,
            String workflowKeySlug,
            String workflowName,
            Long workflowVersionId,
            String workflowVersion,
            LocalDateTime publishedAt,
            String agentId,
            String agentKeySlug,
            Long agentConfigVersionId,
            Integer agentConfigVersion,
            String toolName,
            String configStatus,
            boolean published) {
    }

    public record PageIntegrationReadinessView(
            String schema,
            String projectCode,
            Long pageId,
            String pageKey,
            String status,
            String message,
            List<PageIntegrationReadinessItem> items,
            LocalDateTime checkedAt) {
    }

    public record PageIntegrationReadinessItem(
            String key,
            String label,
            String status,
            String message,
            JsonNode evidence) {
    }

    public record AcceptancePayload(
            boolean passed,
            String summary,
            List<ReportedCheck> checks,
            BrowserVerification browserVerification,
            String traceId) {
    }

    public record PreReleasePayload(
            boolean passed,
            String summary,
            List<ReportedCheck> checks,
            String workflowId,
            String workflowVersion) {
    }

    public record ReleaseValidationItem(
            String code,
            String level,
            String nodeId,
            String message) {
    }

    public record WorkflowReleaseReadinessView(
            String status,
            String code,
            String message,
            String projectCode,
            String pageKey,
            String workflowId,
            String requestedVersion,
            String workflowKind,
            String workflowStatus,
            String releaseState,
            Boolean releaseValid,
            Long publishedVersionId,
            String publishedVersion,
            List<ReleaseValidationItem> errors,
            List<ReleaseValidationItem> warnings,
            LocalDateTime checkedAt) {
    }

    public record WorkflowExecutionReadinessView(
            String status,
            String code,
            String message,
            String projectCode,
            String pageKey,
            String sessionId,
            String pageInstanceId,
            String traceId,
            String workflowId,
            Long workflowVersionId,
            String workflowVersion,
            String runStatus,
            String entryType,
            boolean workflowObserved,
            LocalDateTime checkedAt) {
    }

    public record PublishedWorkflowView(
            String pageKey,
            String workflowId,
            String workflowKeySlug,
            String workflowName,
            String workflowDescription,
            String workflowStatus,
            Long workflowVersionId,
            String workflowVersion,
            String publishedBy,
            LocalDateTime publishedAt,
            String agentId,
            String agentKeySlug,
            String agentName,
            Long agentConfigVersionId,
            Integer agentConfigVersion,
            String toolName,
            String riskLevel,
            String permissionKey,
            int recentCallCount,
            Double successRate,
            LocalDateTime latestCallAt,
            String latestCallStatus,
            String latestTraceId) {
    }

    public record PageAccessCenterOverviewView(
            String schema,
            String projectCode,
            PageAccessCenterSummaryView summary,
            List<PageAccessJourneyView> pages,
            List<PageAccessActivityView> activities,
            List<PublishedWorkflowView> onlineCapabilities,
            boolean runtimeAvailable,
            String runtimeMessage,
            LocalDateTime generatedAt,
            List<PageView> catalogPages,
            PageMapSummaryView pageMap,
            List<FindingView> findings,
            List<TaskView> tasks) {
    }

    public record PageAccessCenterSummaryView(
            int discoveredCount,
            int waitingCount,
            int activeCount,
            int awaitingAcceptanceCount,
            int completedCount,
            int unavailableCount) {
    }

    public record PageAccessJourneyView(
            Long pageId,
            String pageKey,
            String stage,
            String status,
            int currentStep,
            int completedSteps,
            String statusLabel,
            String title,
            String message,
            PageAccessNextActionView nextAction,
            String activeTaskId,
            String activeTaskKind,
            String activeTaskStatus,
            int unreadFindingCount,
            int keptFindingCount,
            int resourceCount,
            int actionCount,
            String workflowId,
            String workflowVersion,
            LocalDateTime updatedAt) {
    }

    public record PageAccessNextActionView(
            String code,
            String label,
            String message,
            boolean enabled) {
    }

    public record PageAccessActivityView(
            String taskId,
            String pageKey,
            String taskKind,
            String executorProvider,
            String title,
            String executionStatus,
            String lastMessage,
            int openQuestionCount,
            String connectionStatus,
            LocalDateTime updatedAt) {
    }
}
