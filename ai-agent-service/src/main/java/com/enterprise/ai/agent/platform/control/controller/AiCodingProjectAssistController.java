package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.aicoding.AiCodingAccessDeniedException;
import com.enterprise.ai.agent.aicoding.AiCodingAccessGuard;
import com.enterprise.ai.agent.aicoding.AiCodingUnauthorizedException;
import com.enterprise.ai.agent.assist.AiAccessSessionService;
import com.enterprise.ai.agent.platform.control.identity.PageActionCatalogContracts;
import com.enterprise.ai.agent.registry.SdkAccessCheckService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai-coding/projects/{projectId}")
@RequiredArgsConstructor
public class AiCodingProjectAssistController {

    private final AiAssistController aiAssistController;
    private final AiCodingAccessGuard aiCodingAccessGuard;

    @GetMapping("/onboarding-manifest")
    public ResponseEntity<?> onboardingManifest(@PathVariable Long projectId,
                                                HttpServletRequest request) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null
                ? denied
                : aiAssistController.onboardingManifestForProjectApiRoot(projectId, request, projectApiRoot(request, projectId));
    }

    @PostMapping("/agents/provision")
    public ResponseEntity<?> provisionProjectAgent(@PathVariable Long projectId,
                                                   @RequestBody(required = false)
                                                   AiAssistController.AgentProvisionRequest request) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null ? denied : aiAssistController.provisionProjectAgent(projectId, request);
    }

    @PostMapping("/access-sessions")
    public ResponseEntity<?> startAccessSession(@PathVariable Long projectId,
                                                @RequestParam(value = "toolName", required = false) String toolName) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null ? denied : aiAssistController.startAccessSession(projectId, toolName);
    }

    @GetMapping("/access-sessions/latest")
    public ResponseEntity<?> latestAccessSession(@PathVariable Long projectId) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null ? denied : aiAssistController.latestAccessSession(projectId);
    }

    @PostMapping("/access-sessions/{sessionId}/steps/{stepKey}/report")
    public ResponseEntity<?> reportAccessSessionStep(@PathVariable Long projectId,
                                                     @PathVariable String sessionId,
                                                     @PathVariable String stepKey,
                                                     @RequestBody(required = false)
                                                     AiAccessSessionService.StepReportRequest request) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null
                ? denied
                : aiAssistController.reportAccessSessionStep(projectId, sessionId, stepKey, request);
    }

    @PostMapping("/access-sessions/{sessionId}/checks/run")
    public ResponseEntity<?> runAccessSessionChecks(@PathVariable Long projectId,
                                                    @PathVariable String sessionId,
                                                    @RequestBody(required = false)
                                                    SdkAccessCheckService.SdkAccessCheckRequest request) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null
                ? denied
                : aiAssistController.runAccessSessionChecks(projectId, sessionId, request);
    }

    @GetMapping(value = "/page-assistant/onboarding-manifest", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> pageAssistantManifest(@PathVariable Long projectId,
                                                   @RequestParam(value = "pageKey", required = false) String pageKey,
                                                   @RequestParam(value = "routePattern", required = false) String routePattern,
                                                   @RequestParam(value = "actionKeys", required = false) List<String> actionKeys,
                                                   @RequestParam(value = "toolName", required = false) String toolName,
                                                   HttpServletRequest request) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null
                ? denied
                : aiAssistController.pageAssistantManifestForProjectApiRoot(
                projectId, pageKey, routePattern, actionKeys, toolName, request, projectApiRoot(request, projectId));
    }

    @PostMapping("/page-assistant/sessions")
    public ResponseEntity<?> startPageAssistantSession(@PathVariable Long projectId,
                                                       @RequestBody(required = false)
                                                       AiAccessSessionService.PageAssistantSessionRequest request) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null ? denied : aiAssistController.startPageAssistantSession(projectId, request);
    }

    @GetMapping("/page-assistant/sessions/latest")
    public ResponseEntity<?> latestPageAssistantSession(@PathVariable Long projectId,
                                                        @RequestParam(value = "pageKey", required = false) String pageKey) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null ? denied : aiAssistController.latestPageAssistantSession(projectId, pageKey);
    }

    @GetMapping("/page-assistant/sessions")
    public ResponseEntity<?> listPageAssistantSessions(@PathVariable Long projectId,
                                                       @RequestParam(value = "pageKey", required = false) String pageKey) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null ? denied : aiAssistController.listPageAssistantSessions(projectId, pageKey);
    }

    @PostMapping("/page-assistant/pages/register")
    public ResponseEntity<?> registerPageAssistantPage(@PathVariable Long projectId,
                                                       @RequestBody(required = false)
                                                       AiAccessSessionService.PageAssistantPageRegisterRequest request) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null ? denied : aiAssistController.registerPageAssistantPage(projectId, request);
    }

    @PostMapping("/page-assistant/sessions/{sessionId}/steps/{stepKey}/report")
    public ResponseEntity<?> reportPageAssistantSessionStep(@PathVariable Long projectId,
                                                            @PathVariable String sessionId,
                                                            @PathVariable String stepKey,
                                                            @RequestBody(required = false)
                                                            AiAccessSessionService.StepReportRequest request) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null
                ? denied
                : aiAssistController.reportPageAssistantSessionStep(projectId, sessionId, stepKey, request);
    }

    @PostMapping("/page-assistant/sessions/{sessionId}/workflow-ai-coding-result")
    public ResponseEntity<?> reportPageAssistantWorkflowAiCodingResult(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false)
            AiAccessSessionService.WorkflowAiCodingResultRequest request) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null
                ? denied
                : aiAssistController.reportPageAssistantWorkflowAiCodingResult(projectId, sessionId, request);
    }

    @DeleteMapping("/page-assistant/sessions/{sessionId}/workflow-ai-coding-result")
    public ResponseEntity<?> resetPageAssistantWorkflowAiCodingResult(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestParam(value = "deleteWorkflow", defaultValue = "true") boolean deleteWorkflow) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null
                ? denied
                : aiAssistController.resetPageAssistantWorkflowAiCodingResult(projectId, sessionId, deleteWorkflow);
    }

    @PutMapping("/page-assistant/sessions/{sessionId}/target")
    public ResponseEntity<?> bindPageAssistantSessionTarget(@PathVariable Long projectId,
                                                            @PathVariable String sessionId,
                                                            @RequestBody(required = false)
                                                            AiAccessSessionService.PageAssistantTargetRequest request) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null
                ? denied
                : aiAssistController.bindPageAssistantSessionTarget(projectId, sessionId, request);
    }

    @PostMapping("/page-assistant/sessions/{sessionId}/catalog/sync")
    public ResponseEntity<?> syncPageAssistantCatalog(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false)
            PageActionCatalogContracts.PageCatalogRegisterRequest request) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null
                ? denied
                : aiAssistController.syncPageAssistantCatalog(projectId, sessionId, request);
    }

    @PostMapping("/page-assistant/sessions/{sessionId}/checks/run")
    public ResponseEntity<?> runPageAssistantSessionChecks(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false)
            AiAccessSessionService.PageAssistantCheckRequest request) {
        ResponseEntity<?> denied = rejectIfInvalid(projectId);
        return denied != null
                ? denied
                : aiAssistController.runPageAssistantSessionChecks(projectId, sessionId, request);
    }

    private ResponseEntity<?> rejectIfInvalid(Long projectId) {
        try {
            aiCodingAccessGuard.requireProjectAccess(projectId);
            return null;
        } catch (AiCodingUnauthorizedException | AiCodingAccessDeniedException | IllegalArgumentException ex) {
            return ResponseEntity.status(403).body(new AiAssistController.ApiErrorResponse(ex.getMessage()));
        }
    }

    private static String projectApiRoot(HttpServletRequest request, Long projectId) {
        return AiAssistController.requestBaseUrl(request) + "/api/ai-coding/projects/" + projectId;
    }
}
