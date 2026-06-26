package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.aicoding.AiCodingAccessDeniedException;
import com.enterprise.ai.agent.aicoding.AiCodingAccessGuard;
import com.enterprise.ai.agent.aicoding.AiCodingUnauthorizedException;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.enterprise.ai.agent.assist.AiAccessSessionService;
import com.enterprise.ai.agent.platform.control.identity.PageActionCatalogContracts;
import com.enterprise.ai.agent.platform.control.identity.PageActionCatalogService;
import com.enterprise.ai.agent.platform.control.identity.PageCatalogRegisterResult;
import com.enterprise.ai.agent.platform.control.auth.AiCodingKeyContext;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.agent.registry.SdkAccessCheckService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import com.enterprise.ai.agent.workflow.AgentProvisioningService;
import com.enterprise.ai.agent.workflow.PageAssistantWorkflowBindingResult;
import com.enterprise.ai.agent.workflow.PageAssistantWorkflowBindingService;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/ai-assist")
@RequiredArgsConstructor
public class AiAssistController {

    private static final String SKILL_NAME = "reachai-onboarding";
    private static final String SKILL_VERSION = "0.1.0";
    private static final String SKILL_ROOT = "ai-assist/skills/" + SKILL_NAME + "/";
    private static final String PAGE_ASSISTANT_SKILL_NAME = "reachai-page-assistant-onboarding";
    private static final String PAGE_ASSISTANT_SKILL_VERSION = "0.1.0";
    private static final String PAGE_ASSISTANT_SKILL_ROOT = "ai-assist/skills/" + PAGE_ASSISTANT_SKILL_NAME + "/";
    private static final String PAGE_ASSISTANT_HELPER_SCRIPT = "scripts/reachai-page-assistant.ps1";
    private static final String WORKFLOW_AI_CODING_SKILL_NAME = "workflow-ai-coding";
    private static final String WORKFLOW_AI_CODING_SKILL_VERSION = "0.1.0";
    private static final String WORKFLOW_AI_CODING_SKILL_ROOT = "ai-assist/skills/" + WORKFLOW_AI_CODING_SKILL_NAME + "/";
    private static final String SECRET_ENV_NAME = "REACHAI_REGISTRY_APP_SECRET";

    private static final List<String> SKILL_FILES = List.of(
            "SKILL.md",
            "agents/openai.yaml",
            "references/java-sdk-access.md",
            "references/platform-apis.md",
            "references/security.md",
            "templates/application-reachai.yml",
            "templates/pom-dependencies.xml",
            "templates/reach-capability-example.java",
            "scripts/verify-reachai-access.py"
    );

    private static final List<String> PAGE_ASSISTANT_SKILL_FILES = List.of(
            "SKILL.md",
            "references/page-action-contract.md",
            "references/angular-page-action.md",
            "templates/angular/reachai-page-action.types.ts",
            "templates/angular/reachai-page-action.service.ts",
            "templates/angular/page-registry.example.ts",
            "scripts/reachai-page-assistant.ps1"
    );

    private static final List<String> WORKFLOW_AI_CODING_SKILL_FILES = List.of(
            "SKILL.md",
            "references/graphspec.md",
            "references/page-assistant.md",
            "references/safety.md",
            "references/workflow-apis.md"
    );

    private final ScanProjectService scanProjectService;
    private final AiCodingAccessGuard aiCodingAccessGuard;
    private final RegistrySecurityService registrySecurityService;
    private final AgentEntryService agentEntryService;
    private final AgentProvisioningService agentProvisioningService;
    private final AiAccessSessionService accessSessionService;
    private final PageActionCatalogService pageActionCatalogService;
    private final PageAssistantWorkflowBindingService pageAssistantWorkflowBindingService;

    @GetMapping("/skills/reachai-onboarding/latest")
    public ResponseEntity<SkillPackageResponse> latestSkill(HttpServletRequest request) {
        String baseUrl = requestBaseUrl(request);
        String downloadUrl = baseUrl + "/api/ai-assist/skills/" + SKILL_NAME + "/latest.zip";
        List<SkillFileResponse> files = SKILL_FILES.stream()
                .map(path -> new SkillFileResponse(path))
                .toList();
        return ResponseEntity.ok(new SkillPackageResponse(
                SKILL_NAME,
                SKILL_VERSION,
                "ReachAI SDK onboarding skill for AI coding tools.",
                downloadUrl,
                files
        ));
    }

    @GetMapping("/skills/reachai-page-assistant-onboarding/latest")
    public ResponseEntity<SkillPackageResponse> latestPageAssistantSkill(HttpServletRequest request) {
        String baseUrl = requestBaseUrl(request);
        String downloadUrl = baseUrl + "/api/ai-assist/skills/" + PAGE_ASSISTANT_SKILL_NAME + "/latest.zip";
        List<SkillFileResponse> files = PAGE_ASSISTANT_SKILL_FILES.stream()
                .map(SkillFileResponse::new)
                .toList();
        return ResponseEntity.ok(new SkillPackageResponse(
                PAGE_ASSISTANT_SKILL_NAME,
                PAGE_ASSISTANT_SKILL_VERSION,
                "ReachAI Page Assistant onboarding skill for AI coding tools.",
                downloadUrl,
                files
        ));
    }

    @GetMapping("/skills/workflow-ai-coding/latest")
    public ResponseEntity<SkillPackageResponse> latestWorkflowAiCodingSkill(HttpServletRequest request) {
        String baseUrl = requestBaseUrl(request);
        String downloadUrl = baseUrl + "/api/ai-assist/skills/" + WORKFLOW_AI_CODING_SKILL_NAME + "/latest.zip";
        List<SkillFileResponse> files = WORKFLOW_AI_CODING_SKILL_FILES.stream()
                .map(SkillFileResponse::new)
                .toList();
        return ResponseEntity.ok(new SkillPackageResponse(
                WORKFLOW_AI_CODING_SKILL_NAME,
                WORKFLOW_AI_CODING_SKILL_VERSION,
                "ReachAI Workflow AI Coding skill for editing, validating, and debugging workflow drafts.",
                downloadUrl,
                files
        ));
    }

    @GetMapping(value = "/skills/reachai-onboarding/latest.zip", produces = "application/zip")
    public ResponseEntity<byte[]> downloadLatestSkill() throws IOException {
        byte[] body = zipSkillFiles(SKILL_NAME, SKILL_ROOT, SKILL_FILES);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(SKILL_NAME + "-" + SKILL_VERSION + ".zip", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(body.length)
                .body(body);
    }

    @GetMapping(value = "/skills/reachai-page-assistant-onboarding/latest.zip", produces = "application/zip")
    public ResponseEntity<byte[]> downloadLatestPageAssistantSkill() throws IOException {
        byte[] body = zipSkillFiles(PAGE_ASSISTANT_SKILL_NAME, PAGE_ASSISTANT_SKILL_ROOT, PAGE_ASSISTANT_SKILL_FILES);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(PAGE_ASSISTANT_SKILL_NAME + "-" + PAGE_ASSISTANT_SKILL_VERSION + ".zip", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(body.length)
                .body(body);
    }

    @GetMapping(value = "/skills/workflow-ai-coding/latest.zip", produces = "application/zip")
    public ResponseEntity<byte[]> downloadLatestWorkflowAiCodingSkill() throws IOException {
        byte[] body = zipSkillFiles(
                WORKFLOW_AI_CODING_SKILL_NAME,
                WORKFLOW_AI_CODING_SKILL_ROOT,
                WORKFLOW_AI_CODING_SKILL_FILES);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(WORKFLOW_AI_CODING_SKILL_NAME + "-" + WORKFLOW_AI_CODING_SKILL_VERSION + ".zip", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(body.length)
                .body(body);
    }

    @GetMapping(value = "/skills/reachai-page-assistant-onboarding/scripts/reachai-page-assistant.ps1",
            produces = "text/plain")
    public ResponseEntity<byte[]> downloadPageAssistantHelperScript() throws IOException {
        ClassPathResource resource = new ClassPathResource(PAGE_ASSISTANT_SKILL_ROOT + PAGE_ASSISTANT_HELPER_SCRIPT);
        if (!resource.exists()) {
            throw new IOException("Missing ReachAI page assistant helper script");
        }
        byte[] body = resource.getInputStream().readAllBytes();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("reachai-page-assistant.ps1", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType("text/plain; charset=UTF-8"))
                .contentLength(body.length)
                .body(body);
    }

    @GetMapping("/projects/{projectId}/onboarding-manifest")
    public ResponseEntity<?> onboardingManifest(@PathVariable Long projectId,
                                                HttpServletRequest request) {
        String baseUrl = requestBaseUrl(request);
        return onboardingManifestForProjectApiRoot(
                projectId,
                request,
                baseUrl + "/api/ai-assist/projects/" + projectId);
    }

    ResponseEntity<?> onboardingManifestForProjectApiRoot(Long projectId,
                                                          HttpServletRequest request,
                                                          String projectApiRoot) {
        try {
            String contextAiCodingKey = AiCodingKeyContext.get();
            String requestAiCodingKey = contextAiCodingKey;
            if (invalidAiCodingKey(projectId, requestAiCodingKey)) {
                return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
            }
            ScanProjectEntity project = scanProjectService.getById(projectId);
            String baseUrl = requestBaseUrl(request);
            Optional<RegistryCredentialEntity> credential = StringUtils.hasText(project.getProjectCode())
                    ? registrySecurityService.findPrimaryActiveCredential(project.getProjectCode())
                    : Optional.empty();
            String appKey = credential.map(RegistryCredentialEntity::getAppKey).orElse(null);
            EmbedManifest embed = buildEmbedManifest(project, credential);
            boolean requestUsedHeaderAiCodingKey = StringUtils.hasText(contextAiCodingKey);
            AgentProvisioningManifest agentProvisioning = buildAgentProvisioningManifest(project, projectApiRoot);

            return ResponseEntity.ok(new OnboardingManifestResponse(
                    "reachai.onboarding.v1",
                    new ProjectManifest(
                            project.getId(),
                            project.getName(),
                            project.getProjectCode(),
                            project.getProjectKind(),
                            project.getEnvironment(),
                            project.getBaseUrl(),
                            emptyToNull(project.getContextPath()),
                            appKey,
                            credential.isPresent()
                    ),
                    new AiCodingAccessManifest(
                            Boolean.TRUE.equals(project.getAiCodingAccessEnabled()),
                            requestUsedHeaderAiCodingKey ? null : project.getAiCodingAccessKey()
                    ),
                    new SdkManifest(
                            "1.0.0-SNAPSHOT",
                            List.of(
                                    new MavenDependency("com.enterprise.ai", "reachai-capability-sdk", "1.0.0-SNAPSHOT"),
                                    new MavenDependency("com.enterprise.ai", "reachai-spring-boot2-starter", "1.0.0-SNAPSHOT")
                            ),
                            new ReachAiConfigManifest(
                                    baseUrl,
                                    appKey,
                                    SECRET_ENV_NAME,
                                    emptyToNull(project.getProjectCode()),
                                    project.getName(),
                                    project.getBaseUrl(),
                                    emptyToNull(project.getContextPath()),
                                    emptyToNull(project.getEnvironment())
                            )
                    ),
                    new PlatformEndpoints(
                            baseUrl + "/api/ai-assist/skills/" + SKILL_NAME + "/latest.zip",
                            projectApiRoot + "/onboarding-manifest",
                            baseUrl + "/api/scan-projects/" + projectId + "/sdk-access-check",
                            baseUrl + "/api/scan-projects/" + projectId + "/tools/reconcile"
                    ),
                    embed,
                    agentProvisioning,
                    buildAgentWorkflowManifest(project, embed, agentProvisioning, baseUrl),
                    new SecurityGuidance(
                            SECRET_ENV_NAME,
                            "Do not paste or write the registry app secret into AI chat context. Store it in a local environment variable or secret manager."
                    )
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/projects/{projectId}/agents/provision")
    public ResponseEntity<?> provisionProjectAgent(@PathVariable Long projectId,
                                                   @RequestBody(required = false) AgentProvisionRequest request) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            ScanProjectEntity project = scanProjectService.getById(projectId);
            String agentKind = request == null ? null : request.agentKind();
            if (StringUtils.hasText(agentKind) && !AgentProvisioningService.PAGE_COPILOT_KIND.equalsIgnoreCase(agentKind.trim())) {
                return ResponseEntity.badRequest().body(new ApiErrorResponse("unsupported agentKind: " + agentKind));
            }
            boolean ensureDefaultWorkflow = request == null || request.ensureDefaultWorkflow() == null || request.ensureDefaultWorkflow();
            String requestedBy = request == null ? "ai-coding" : firstNonBlank(request.requestedBy(), "ai-coding");
            AgentProvisioningService.AgentProvisioningResult result =
                    agentProvisioningService.provisionPageCopilot(project, requestedBy, ensureDefaultWorkflow);
            return ResponseEntity.ok(toAgentProvisionResponse(result));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping(value = "/projects/{projectId}/page-assistant/onboarding-manifest", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> pageAssistantManifest(@PathVariable Long projectId,
                                                   @RequestParam(value = "pageKey", required = false) String pageKey,
                                                   @RequestParam(value = "routePattern", required = false) String routePattern,
                                                   @RequestParam(value = "actionKeys", required = false) List<String> actionKeys,
                                                   @RequestParam(value = "toolName", required = false) String toolName,
                                                   HttpServletRequest request) {
        String baseUrl = requestBaseUrl(request);
        return pageAssistantManifestForProjectApiRoot(
                projectId,
                pageKey,
                routePattern,
                actionKeys,
                toolName,
                request,
                baseUrl + "/api/ai-assist/projects/" + projectId);
    }

    ResponseEntity<?> pageAssistantManifestForProjectApiRoot(Long projectId,
                                                             String pageKey,
                                                             String routePattern,
                                                             List<String> actionKeys,
                                                             String toolName,
                                                             HttpServletRequest request,
                                                             String projectApiRoot) {
        try {
            String contextAiCodingKey = AiCodingKeyContext.get();
            String requestAiCodingKey = contextAiCodingKey;
            if (invalidAiCodingKey(projectId, requestAiCodingKey)) {
                return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
            }
            ScanProjectEntity project = scanProjectService.getById(projectId);
            String baseUrl = requestBaseUrl(request);
            String pageAssistantRoot = projectApiRoot + "/page-assistant";
            Optional<RegistryCredentialEntity> credential = StringUtils.hasText(project.getProjectCode())
                    ? registrySecurityService.findPrimaryActiveCredential(project.getProjectCode())
                    : Optional.empty();
            String appKey = credential.map(RegistryCredentialEntity::getAppKey).orElse(null);
            AiAccessSessionService.AccessSessionView session = accessSessionService.getOrCreatePageAssistantLatest(
                    projectId,
                    new AiAccessSessionService.PageAssistantSessionRequest(toolName, pageKey, routePattern, actionKeys));
            String effectiveAiCodingKey = resolveEffectiveAiCodingKey(project, requestAiCodingKey);
            boolean requestUsedHeaderAiCodingKey = StringUtils.hasText(contextAiCodingKey);
            String manifestUrl = pageAssistantRoot + "/onboarding-manifest";
            String latestSessionUrl = pageAssistantRoot + "/sessions/latest";
            String stepReportUrl = pageAssistantRoot + "/sessions/" + session.sessionId() + "/steps/{stepKey}/report";
            String targetBindUrl = pageAssistantRoot + "/sessions/" + session.sessionId() + "/target";
            String catalogSyncUrl = pageAssistantRoot + "/sessions/" + session.sessionId() + "/catalog/sync";
            String checksRunUrl = pageAssistantRoot + "/sessions/" + session.sessionId() + "/checks/run";
            String registerPageUrl = pageAssistantRoot + "/pages/register";
            String skillPackageUrl = baseUrl + "/api/ai-assist/skills/" + PAGE_ASSISTANT_SKILL_NAME + "/latest.zip";
            String scriptDownloadUrl = baseUrl + "/api/ai-assist/skills/" + PAGE_ASSISTANT_SKILL_NAME
                    + "/scripts/reachai-page-assistant.ps1";
            String resolvedPageKey = session.targetPageKey();
            String resolvedRoute = session.targetRoute();
            boolean useHeaderCommandAuth = StringUtils.hasText(effectiveAiCodingKey);
            String scaffoldCommand = buildPageAssistantScaffoldCommand(manifestUrl, useHeaderCommandAuth);
            String verifyCommand = buildPageAssistantVerifyCommand(manifestUrl, resolvedPageKey, resolvedRoute, useHeaderCommandAuth);
            return ResponseEntity.ok(new PageAssistantManifestResponse(
                    "reachai.page-assistant-onboarding.v2",
                    new ProjectManifest(
                            project.getId(),
                            project.getName(),
                            project.getProjectCode(),
                            project.getProjectKind(),
                            project.getEnvironment(),
                            project.getBaseUrl(),
                            emptyToNull(project.getContextPath()),
                            appKey,
                            credential.isPresent()
                    ),
                    new AiCodingAccessManifest(
                            Boolean.TRUE.equals(project.getAiCodingAccessEnabled()),
                            requestUsedHeaderAiCodingKey ? null : project.getAiCodingAccessKey()
                    ),
                    buildPageAssistantAuthManifest(projectApiRoot, baseUrl, projectId, requestUsedHeaderAiCodingKey),
                    new PageAssistantTargetManifest(
                            session.targetPageKey(),
                            session.targetRoute(),
                            actionKeys == null ? List.of() : actionKeys
                    ),
                    session,
                    new PageAssistantEndpoints(
                            manifestUrl,
                            latestSessionUrl,
                            stepReportUrl,
                            targetBindUrl,
                            catalogSyncUrl,
                            checksRunUrl,
                            registerPageUrl,
                            skillPackageUrl,
                            scriptDownloadUrl
                    ),
                    new SecurityGuidance(
                            SECRET_ENV_NAME,
                            "Do not paste or write the registry app secret into AI chat context. Store it in a local environment variable or secret manager."
                    ),
                    new LocalExecutionManifest(
                            true,
                            "localhost platform APIs usually cannot be reached from remote WebFetch. Use local PowerShell/curl from the business frontend machine."),
                    buildPageActionContractManifest(),
                    new PageAssistantScaffoldManifest(
                            "angular",
                            List.of(
                                    new ScaffoldTemplateManifest("reachai-page-action.types.ts", "types"),
                                    new ScaffoldTemplateManifest("reachai-page-action.service.ts", "bridge-service"),
                                    new ScaffoldTemplateManifest("page-registry.example.ts", "page-registry")
                            ),
                            PAGE_ASSISTANT_HELPER_SCRIPT,
                            scriptDownloadUrl,
                            skillPackageUrl,
                            scaffoldCommand,
                            verifyCommand)
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    private static PageAssistantAuthManifest buildPageAssistantAuthManifest(String projectApiRoot,
                                                                           String baseUrl,
                                                                           Long projectId,
                                                                           boolean requestUsedHeaderAiCodingKey) {
        boolean aiCodingGatewayRoot = projectApiRoot != null && projectApiRoot.contains("/api/ai-coding/projects/");
        String mode = aiCodingGatewayRoot || requestUsedHeaderAiCodingKey ? "ai-coding-key" : "platform-session";
        String externalRoot = baseUrl + "/api/ai-coding/projects/" + projectId + "/page-assistant";
        String consoleRoot = baseUrl + "/api/ai-assist/projects/" + projectId + "/page-assistant";
        return new PageAssistantAuthManifest(
                mode,
                "X-ReachAI-AiCoding-Key",
                "REACHAI_AI_CODING_KEY",
                externalRoot + "/**",
                consoleRoot + "/**",
                List.of(
                        "External AI coding tools must call /api/ai-coding/projects/{projectId}/page-assistant/** with X-ReachAI-AiCoding-Key.",
                        "Platform console pages may call /api/ai-assist/projects/{projectId}/page-assistant/** with platform session login.",
                        "Do not mix origins or reuse platform-session endpoints in helper commands copied to Cursor/Codex."
                )
        );
    }

    @PatchMapping("/projects/{projectId}/ai-coding-access")
    public ResponseEntity<?> updateAiCodingAccess(@PathVariable Long projectId,
                                                  @RequestBody(required = false) AiCodingAccessUpdateRequest request) {
        try {
            ScanProjectEntity project = scanProjectService.updateAiCodingAccess(projectId,
                    new ScanProjectService.AiCodingAccessUpdate(
                            request == null ? Boolean.FALSE : request.enabled(),
                            request == null ? null : request.accessKey()));
            return ResponseEntity.ok(new AiCodingAccessManifest(
                    Boolean.TRUE.equals(project.getAiCodingAccessEnabled()),
                    project.getAiCodingAccessKey()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/access-sessions")
    public ResponseEntity<?> startAccessSession(@PathVariable Long projectId,
                                                @RequestParam(value = "toolName", required = false) String toolName) {
        try {
            return ResponseEntity.ok(accessSessionService.getOrCreateLatest(projectId, toolName));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/page-assistant/sessions")
    public ResponseEntity<?> startPageAssistantSession(@PathVariable Long projectId,
                                                       @RequestBody(required = false)
                                                       AiAccessSessionService.PageAssistantSessionRequest request) {
        try {
            return ResponseEntity.ok(accessSessionService.getOrCreatePageAssistantLatest(projectId, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/page-assistant/sessions/latest")
    public ResponseEntity<?> latestPageAssistantSession(@PathVariable Long projectId,
                                                        @RequestParam(value = "pageKey", required = false) String pageKey) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            return ResponseEntity.ok(accessSessionService.getLatestPageAssistant(projectId, pageKey));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/page-assistant/sessions")
    public ResponseEntity<?> listPageAssistantSessions(@PathVariable Long projectId,
                                                       @RequestParam(value = "pageKey", required = false) String pageKey) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            return ResponseEntity.ok(accessSessionService.listPageAssistantSessions(projectId, pageKey));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/access-sessions/latest")
    public ResponseEntity<?> latestAccessSession(@PathVariable Long projectId) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            return ResponseEntity.ok(accessSessionService.getLatest(projectId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/access-sessions/{sessionId}/steps/{stepKey}/report")
    public ResponseEntity<?> reportAccessSessionStep(@PathVariable Long projectId,
                                                     @PathVariable String sessionId,
                                                     @PathVariable String stepKey,
                                                     @RequestBody(required = false)
                                                     AiAccessSessionService.StepReportRequest request) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            return ResponseEntity.ok(accessSessionService.reportStep(projectId, sessionId, stepKey, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/page-assistant/sessions/{sessionId}/steps/{stepKey}/report")
    public ResponseEntity<?> reportPageAssistantSessionStep(@PathVariable Long projectId,
                                                            @PathVariable String sessionId,
                                                            @PathVariable String stepKey,
                                                            @RequestBody(required = false)
                                                            AiAccessSessionService.StepReportRequest request) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            return ResponseEntity.ok(accessSessionService.reportStep(projectId, sessionId, stepKey, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/page-assistant/sessions/{sessionId}/workflow-ai-coding-result")
    public ResponseEntity<?> reportPageAssistantWorkflowAiCodingResult(@PathVariable Long projectId,
                                                                       @PathVariable String sessionId,
                                                                       @RequestBody(required = false)
                                                                       AiAccessSessionService.WorkflowAiCodingResultRequest request) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            return ResponseEntity.ok(accessSessionService.reportWorkflowAiCodingResult(projectId, sessionId, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @DeleteMapping("/projects/{projectId}/page-assistant/sessions/{sessionId}/workflow-ai-coding-result")
    public ResponseEntity<?> resetPageAssistantWorkflowAiCodingResult(@PathVariable Long projectId,
                                                                      @PathVariable String sessionId,
                                                                      @RequestParam(value = "deleteWorkflow", defaultValue = "true") boolean deleteWorkflow) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            return ResponseEntity.ok(accessSessionService.resetWorkflowAiCodingResult(projectId, sessionId, deleteWorkflow));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PutMapping("/projects/{projectId}/page-assistant/sessions/{sessionId}/target")
    public ResponseEntity<?> bindPageAssistantSessionTarget(@PathVariable Long projectId,
                                                            @PathVariable String sessionId,
                                                            @RequestBody(required = false)
                                                            AiAccessSessionService.PageAssistantTargetRequest request) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            return ResponseEntity.ok(accessSessionService.bindPageAssistantTarget(projectId, sessionId, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/page-assistant/sessions/{sessionId}/catalog/sync")
    public ResponseEntity<?> syncPageAssistantCatalog(@PathVariable Long projectId,
                                                      @PathVariable String sessionId,
                                                      @RequestBody(required = false)
                                                      PageActionCatalogContracts.PageCatalogRegisterRequest request) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            ScanProjectEntity project = scanProjectService.getById(projectId);
            RegistryCredentialEntity credential = StringUtils.hasText(project.getProjectCode())
                    ? registrySecurityService.findPrimaryActiveCredential(project.getProjectCode()).orElse(null)
                    : null;
            if (credential == null) {
                return ResponseEntity.badRequest().body(new ApiErrorResponse("active registry credential is required for page catalog sync"));
            }
            PageCatalogRegisterResult result = pageActionCatalogService.registerFromProjectCredential(credential, request);
            List<String> actionKeys = request == null || request.actions() == null
                    ? List.of()
                    : request.actions().stream()
                    .map(PageActionCatalogContracts.PageActionDefinitionRequest::actionKey)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .toList();
            AiAccessSessionService.AccessSessionView session = accessSessionService.bindPageAssistantTarget(
                    projectId,
                    sessionId,
                    new AiAccessSessionService.PageAssistantTargetRequest(
                            request == null ? null : request.pageKey(),
                            request == null ? null : request.routePattern(),
                            actionKeys));
            PageAssistantWorkflowBindingResult workflowBinding =
                    pageAssistantWorkflowBindingService.ensurePageWorkflowBinding(
                            project,
                            request == null ? null : request.pageKey(),
                            request == null ? null : request.routePattern(),
                            actionKeys);
            return ResponseEntity.ok(new PageAssistantCatalogSyncResponse(
                    result.projectCode(),
                    result.appId(),
                    result.pageKey(),
                    result.actionCount(),
                    session,
                    workflowBinding));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping(value = "/projects/{projectId}/page-assistant/pages/register", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> registerPageAssistantPage(@PathVariable Long projectId,
                                                       @RequestBody(required = false)
                                                       AiAccessSessionService.PageAssistantPageRegisterRequest request) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            ScanProjectEntity project = scanProjectService.getById(projectId);
            RegistryCredentialEntity credential = StringUtils.hasText(project.getProjectCode())
                    ? registrySecurityService.findPrimaryActiveCredential(project.getProjectCode()).orElse(null)
                    : null;
            if (credential == null) {
                return ResponseEntity.badRequest().body(new ApiErrorResponse("active registry credential is required for page register"));
            }
            PageActionCatalogContracts.PageCatalogRegisterRequest catalogRequest = toCatalogRegisterRequest(request);
            PageCatalogRegisterResult registerResult = pageActionCatalogService.registerFromProjectCredential(credential, catalogRequest);
            AiAccessSessionService.PageAssistantPageRegisterRequest normalizedRequest = normalizeRegisterRequest(request);
            AiAccessSessionService.PageAssistantPageRegisterSessionResult sessionResult =
                    accessSessionService.applyPageAssistantPageRegistration(projectId, normalizedRequest, registerResult);
            List<String> actionKeys = normalizedRequest.actions() == null
                    ? List.of()
                    : normalizedRequest.actions().stream()
                    .map(PageActionCatalogContracts.PageActionDefinitionRequest::actionKey)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .toList();
            PageAssistantWorkflowBindingResult workflowBinding =
                    pageAssistantWorkflowBindingService.ensurePageWorkflowBinding(
                            project,
                            normalizedRequest.pageKey(),
                            normalizedRequest.routePattern(),
                            actionKeys);
            AiAccessSessionService.PageAssistantCheckRunResponse checkRun =
                    accessSessionService.runPageAssistantChecks(
                            projectId,
                            sessionResult.session().sessionId(),
                            AiAccessSessionService.buildCheckRequestFromRegister(normalizedRequest, actionKeys));
            return ResponseEntity.ok(new PageAssistantPageRegisterResponse(
                    checkRun.session(),
                    checkRun.checkResult(),
                    new RegisteredPageResponse(
                            registerResult.projectCode(),
                            registerResult.appId(),
                            registerResult.pageKey(),
                            normalizedRequest.pageName(),
                            normalizedRequest.routePattern(),
                            normalizedRequest.framework(),
                            normalizedRequest.bridgeGlobal()),
                    actionKeys,
                    sessionResult.fileEvidence(),
                    workflowBinding));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    private static PageActionCatalogContracts.PageCatalogRegisterRequest toCatalogRegisterRequest(
            AiAccessSessionService.PageAssistantPageRegisterRequest request) {
        AiAccessSessionService.PageAssistantPageRegisterRequest normalized = normalizeRegisterRequest(request);
        if (normalized == null) {
            throw new IllegalArgumentException("page assistant register request is required");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "ai-coding");
        metadata.put("framework", emptyToNull(normalized.framework()));
        metadata.put("frameworkVersion", emptyToNull(normalized.frameworkVersion()));
        metadata.put("bridgeGlobal", emptyToNull(normalized.bridgeGlobal()));
        metadata.put("files", normalized.files() == null ? List.of() : normalized.files());
        metadata.put("verification", normalized.verification() == null ? Map.of() : normalized.verification());
        metadata.put("handoffSummary", emptyToNull(normalized.handoffSummary()));
        return new PageActionCatalogContracts.PageCatalogRegisterRequest(
                normalized.pageKey(),
                normalized.pageName(),
                normalized.routePattern(),
                "ai-coding",
                null,
                normalized.replaceActions(),
                normalized.actions() == null ? List.of() : normalized.actions(),
                metadata);
    }

    private static AiAccessSessionService.PageAssistantPageRegisterRequest normalizeRegisterRequest(
            AiAccessSessionService.PageAssistantPageRegisterRequest request) {
        if (request == null) {
            return null;
        }
        return new AiAccessSessionService.PageAssistantPageRegisterRequest(
                request.sessionId(),
                request.toolName(),
                request.pageKey(),
                request.pageName(),
                request.routePattern(),
                request.framework(),
                request.frameworkVersion(),
                request.bridgeGlobal(),
                request.replaceActions(),
                AiAccessSessionService.normalizeFileEvidence(request.files()),
                request.actions(),
                request.verification(),
                request.handoffSummary());
    }

    private static PageActionContractManifest buildPageActionContractManifest() {
        return new PageActionContractManifest(
                "__REACHAI_PAGE_BRIDGE__",
                "1.0",
                List.of("angular"),
                List.of("getPageState", "setFilters", "search", "reset", "readTable", "openRowAction"),
                new PageActionSafetyManifest(true, true),
                buildPageActionBridgeApi());
    }

    private static PageActionBridgeApiManifest buildPageActionBridgeApi() {
        Map<String, String> methods = Map.of(
                "register", "register(pageKey, actionKey, handler, metadata?)",
                "unregisterPage", "unregisterPage(pageKey)",
                "execute", "execute(pageKey, actionKey, args?, options?)",
                "list", "list(pageKey?)");
        Map<String, Object> executeRequest = Map.of(
                "type", "object",
                "required", List.of("pageKey", "actionKey"),
                "properties", Map.of(
                        "pageKey", Map.of("type", "string"),
                        "actionKey", Map.of("type", "string"),
                        "args", Map.of("type", "object"),
                        "options", Map.of("type", "object", "properties", Map.of(
                                "confirm", Map.of("type", "boolean"),
                                "timeoutMs", Map.of("type", "integer")))));
        Map<String, Object> executeResponse = Map.of(
                "type", "object",
                "required", List.of("status"),
                "properties", Map.of(
                        "status", Map.of("type", "string", "enum", List.of("SUCCESS", "WARN", "ERROR")),
                        "message", Map.of("type", "string"),
                        "data", Map.of("type", "object"),
                        "error", Map.of("type", "object", "properties", Map.of(
                                "code", Map.of("type", "string"),
                                "message", Map.of("type", "string"))),
                        "metadata", Map.of("type", "object")));
        return new PageActionBridgeApiManifest(
                "window.__REACHAI_PAGE_BRIDGE__",
                methods,
                new BridgeApiSchemas(
                        Map.of("type", "object", "required", List.of("pageKey", "actionKey", "handler"),
                                "properties", Map.of(
                                        "pageKey", Map.of("type", "string"),
                                        "actionKey", Map.of("type", "string"),
                                        "handler", Map.of("type", "object"),
                                        "metadata", Map.of("type", "object"))),
                        executeRequest,
                        executeResponse),
                List.of("SUCCESS", "WARN", "ERROR"),
                List.of("HANDLER_NOT_FOUND", "HANDLER_ERROR", "CONFIRM_REQUIRED", "PENDING_CONFIRM"),
                List.of(
                        new BridgeApiExampleManifest(
                                "getPageState execute",
                                "getPageState",
                                Map.of("pageKey", "teamArchive.list", "actionKey", "getPageState", "args", Map.of()),
                                Map.of("status", "SUCCESS", "data", Map.of(
                                        "filters", Map.of("teamName", "A"),
                                        "pagination", Map.of("page", 1, "pageSize", 10),
                                        "rows", List.of()))),
                        new BridgeApiExampleManifest(
                                "high-risk pending confirm",
                                "openRowAction",
                                Map.of("pageKey", "teamArchive.list", "actionKey", "openRowAction",
                                        "args", Map.of("rowId", "1001")),
                                Map.of("status", "ERROR", "error", Map.of(
                                        "code", "PENDING_CONFIRM",
                                        "message", "High-risk action requires user confirmation")))),
                new PageActionSafetyManifest(true, true));
    }

    private static String buildPageAssistantScaffoldCommand(String manifestUrl, boolean useHeaderAuth) {
        return ".\\scripts\\reachai-page-assistant.ps1 scaffold -ManifestUrl \""
                + manifestUrl + "\""
                + pageAssistantAiCodingKeyArgument(useHeaderAuth)
                + " -Framework angular -OutputDir \".\\src\\app\\shared\\reachai\"";
    }

    private static String buildPageAssistantVerifyCommand(String manifestUrl,
                                                          String pageKey,
                                                          String routePattern,
                                                          boolean useHeaderAuth) {
        String resolvedPageKey = StringUtils.hasText(pageKey) ? pageKey.trim() : "<pageKey>";
        String resolvedRoute = StringUtils.hasText(routePattern) ? routePattern.trim() : "<目标路由>";
        return ".\\scripts\\reachai-page-assistant.ps1 verify -ManifestUrl \""
                + manifestUrl + "\""
                + pageAssistantAiCodingKeyArgument(useHeaderAuth)
                + " -FrontendUrl \"<业务前端地址>\" -Route \""
                + resolvedRoute + "\" -PageKey \"" + resolvedPageKey + "\"";
    }

    private static String pageAssistantAiCodingKeyArgument(boolean useHeaderAuth) {
        return useHeaderAuth ? " -AiCodingKey $env:REACHAI_AI_CODING_KEY" : "";
    }

    private static String resolveEffectiveAiCodingKey(ScanProjectEntity project, String requestAiCodingKey) {
        if (StringUtils.hasText(requestAiCodingKey)) {
            return requestAiCodingKey.trim();
        }
        if (project != null
                && Boolean.TRUE.equals(project.getAiCodingAccessEnabled())
                && StringUtils.hasText(project.getAiCodingAccessKey())) {
            return project.getAiCodingAccessKey().trim();
        }
        return null;
    }

    @PostMapping("/projects/{projectId}/access-sessions/{sessionId}/checks/run")
    public ResponseEntity<?> runAccessSessionChecks(@PathVariable Long projectId,
                                                    @PathVariable String sessionId,
                                                    @RequestBody(required = false)
                                                    SdkAccessCheckService.SdkAccessCheckRequest request) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            return ResponseEntity.ok(accessSessionService.runChecks(projectId, sessionId, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping(value = "/projects/{projectId}/page-assistant/sessions/{sessionId}/checks/run", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> runPageAssistantSessionChecks(@PathVariable Long projectId,
                                                           @PathVariable String sessionId,
                                                           @RequestBody(required = false)
                                                           AiAccessSessionService.PageAssistantCheckRequest request) {
        if (invalidAiCodingKey(projectId, effectiveAiCodingKey())) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            return ResponseEntity.ok(accessSessionService.runPageAssistantChecks(projectId, sessionId, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    private EmbedManifest buildEmbedManifest(ScanProjectEntity project, Optional<RegistryCredentialEntity> credential) {
        List<String> allowedRefs = credential
                .map(RegistryCredentialEntity::getAllowedAgentIdsJson)
                .map(AiAssistController::readStringList)
                .orElse(List.of());
        List<AgentEntryEntity> entries = allowedRefs.isEmpty()
                ? listProjectEmbedAgents(project)
                : resolveAllowedAgents(allowedRefs);
        List<EmbedAgentManifest> agents = entries.stream()
                .map(AiAssistController::toEmbedAgentManifest)
                .toList();
        EmbedAgentManifest defaultAgent = agents.stream()
                .filter(EmbedAgentManifest::enabled)
                .findFirst()
                .orElse(null);
        return new EmbedManifest(
                "/api/reachai/embed-token",
                defaultAgent == null ? null : defaultAgent.id(),
                defaultAgent == null ? null : defaultAgent.keySlug(),
                agents
        );
    }

    private AgentProvisioningManifest buildAgentProvisioningManifest(ScanProjectEntity project,
                                                                      String projectApiRoot) {
        String fallbackProjectCode = projectCodeOrFallback(project);
        String defaultKeySlug = agentProvisioningService.pageCopilotKeySlug(fallbackProjectCode);
        String provisionUrl = projectApiRoot + "/agents/provision";
        return new AgentProvisioningManifest(
                "agent-provisioning.v1",
                AgentProvisioningService.PAGE_COPILOT_KIND,
                defaultKeySlug,
                provisionUrl,
                true,
                true,
                true,
                List.of(
                        "Call provisionAgentUrl before wiring embedded chat.",
                        "Use response.agent.keySlug as the business frontend agentId.",
                        "Do not ask the user to manually create or choose a project Agent during SDK onboarding."
                )
        );
    }

    private AgentWorkflowManifest buildAgentWorkflowManifest(ScanProjectEntity project,
                                                             EmbedManifest embed,
                                                             AgentProvisioningManifest provisioning,
                                                             String baseUrl) {
        String globalAgentKeySlug = firstNonBlank(
                provisioning == null ? null : provisioning.defaultKeySlug(),
                embed == null ? null : embed.defaultAgentKeySlug(),
                agentProvisioningService.pageCopilotKeySlug(projectCodeOrFallback(project)));
        return new AgentWorkflowManifest(
                "agent-workflow.decoupled.v1",
                globalAgentKeySlug,
                AgentProvisioningService.PAGE_COPILOT_KIND,
                "ai_workflow",
                "SDK_GRAPH",
                "Bind page/action/intent workflows to the project page copilot Agent instead of creating one agent per workflow.",
                new AgentWorkflowEndpoints(
                        baseUrl + "/api/agents",
                        baseUrl + "/api/workflows",
                        baseUrl + "/api/agents/" + globalAgentKeySlug + "/workflow-bindings",
                        baseUrl + "/api/agents/" + globalAgentKeySlug + "/workflow-bindings/resolve-preview"
                ),
                buildWorkflowAiCodingManifest(baseUrl),
                List.of(
                        "Provision or reuse one project-level PAGE_COPILOT Agent entry.",
                        "Store every executable graph as an ai_workflow draft or version.",
                        "Create ai_agent_workflow_binding rows for DEFAULT, PAGE, ACTION, ROUTE, or INTENT routing.",
                        "Do not treat LangGraph runtime configuration as the Agent identity.",
                        "Use Workflow AI Coding REST APIs or the workflow-ai-coding skill for draft edits, validation, debug runs, release readiness checks, and first publish."
                )
        );
    }

    private WorkflowAiCodingManifest buildWorkflowAiCodingManifest(String baseUrl) {
        return new WorkflowAiCodingManifest(
                baseUrl + "/api/ai-assist/skills/" + WORKFLOW_AI_CODING_SKILL_NAME + "/latest.zip",
                baseUrl + "/api/workflows/ai-coding/workflows",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/context",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/patch",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/validate",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/run",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/versions",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/publish",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/runs",
                List.of(
                        "Download and install the workflow-ai-coding skill before editing graphs from AI tools.",
                        "All Workflow AI Coding endpoints require project aiCodingKey via header X-ReachAI-AiCoding-Key; manage the key in project detail.",
                        "Read /context before patch; use workflow.updatedAt as baseRevision when saving.",
                        "After the first valid workflow draft is saved, call /publish once to create the initial ACTIVE workflow version."
                )
        );
    }

    private AgentProvisionResponse toAgentProvisionResponse(AgentProvisioningService.AgentProvisioningResult result) {
        AgentEntryEntity agent = result.agent();
        WorkflowDefinitionEntity workflow = result.defaultWorkflow();
        AgentWorkflowBindingEntity binding = result.defaultBinding();
        return new AgentProvisionResponse(
                "agent-provisioning.v1",
                toProvisionedAgent(agent),
                workflow == null ? null : new ProvisionedWorkflow(
                        workflow.getId(),
                        workflow.getKeySlug(),
                        workflow.getName(),
                        workflow.getWorkflowType(),
                        workflow.getStatus(),
                        workflow.getManagedBy()),
                binding == null ? null : new ProvisionedBinding(
                        binding.getId(),
                        binding.getAgentId(),
                        binding.getWorkflowId(),
                        binding.getBindingType(),
                        binding.getEnabled()),
                result.createdAgent(),
                result.createdDefaultWorkflow(),
                result.createdDefaultBinding()
        );
    }

    private ProvisionedAgent toProvisionedAgent(AgentEntryEntity agent) {
        return new ProvisionedAgent(
                agent.getId(),
                agent.getKeySlug(),
                agent.getName(),
                agent.getProjectCode(),
                agent.getAgentKind(),
                agent.getEnabled() == null || agent.getEnabled()
        );
    }

    private String projectCodeOrFallback(ScanProjectEntity project) {
        if (project != null && StringUtils.hasText(project.getProjectCode())) {
            return project.getProjectCode().trim();
        }
        return "project-" + (project == null ? "unknown" : project.getId());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String effectiveAiCodingKey() {
        return AiCodingKeyContext.get();
    }

    private boolean invalidAiCodingKey(Long projectId, String aiCodingKey) {
        if (!StringUtils.hasText(aiCodingKey)) {
            return false;
        }
        try {
            aiCodingAccessGuard.requireProjectAccess(projectId, aiCodingKey);
            return false;
        } catch (AiCodingUnauthorizedException | AiCodingAccessDeniedException | IllegalArgumentException ex) {
            return true;
        }
    }

    private List<AgentEntryEntity> listProjectEmbedAgents(ScanProjectEntity project) {
        if (project == null || project.getId() == null) {
            return List.of();
        }
        return agentEntryService.list(project.getId(), null, null).stream()
                .filter(entry -> entry.getEnabled() == null || entry.getEnabled())
                .toList();
    }

    private List<AgentEntryEntity> resolveAllowedAgents(List<String> allowedRefs) {
        Map<String, AgentEntryEntity> unique = new LinkedHashMap<>();
        for (String ref : allowedRefs == null ? List.<String>of() : allowedRefs) {
            if (!StringUtils.hasText(ref)) {
                continue;
            }
            String value = ref.trim();
            AgentEntryEntity agent = agentEntryService.findById(value)
                    .or(() -> agentEntryService.findByKeySlug(value))
                    .orElse(null);
            if (agent != null) {
                unique.put(agent.getId(), agent);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static EmbedAgentManifest toEmbedAgentManifest(AgentEntryEntity agent) {
        return new EmbedAgentManifest(
                agent.getId(),
                emptyToNull(agent.getKeySlug()),
                agent.getName(),
                emptyToNull(agent.getProjectCode()),
                agent.getEnabled() == null || agent.getEnabled()
        );
    }

    private static List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private byte[] zipSkillFiles(String skillName, String skillRoot, List<String> skillFiles) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String path : skillFiles) {
                ClassPathResource resource = new ClassPathResource(skillRoot + path);
                if (!resource.exists()) {
                    throw new IOException("Missing ReachAI skill resource: " + path);
                }
                ZipEntry entry = new ZipEntry(skillName + "/" + path);
                zip.putNextEntry(entry);
                try (var input = resource.getInputStream()) {
                    input.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static String requestBaseUrl(HttpServletRequest request) {
        String scheme = headerOrDefault(request, "X-Forwarded-Proto", request.getScheme());
        String host = headerOrDefault(request, "X-Forwarded-Host", request.getServerName());
        String port = request.getServerPort() <= 0 ? "" : ":" + request.getServerPort();
        if (host.contains(":") || ("http".equalsIgnoreCase(scheme) && request.getServerPort() == 80)
                || ("https".equalsIgnoreCase(scheme) && request.getServerPort() == 443)) {
            port = "";
        }
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        return scheme + "://" + host + port + contextPath;
    }

    private static String headerOrDefault(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return StringUtils.hasText(value) ? value.split(",")[0].trim() : fallback;
    }

    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    record SkillPackageResponse(
            String name,
            String version,
            String description,
            String downloadUrl,
            List<SkillFileResponse> files
    ) {
    }

    record SkillFileResponse(String path) {
    }

    record OnboardingManifestResponse(
            String schema,
            ProjectManifest project,
            AiCodingAccessManifest aiCodingAccess,
            SdkManifest sdk,
            PlatformEndpoints endpoints,
            EmbedManifest embed,
            AgentProvisioningManifest agentProvisioning,
            AgentWorkflowManifest agentWorkflow,
            SecurityGuidance security
    ) {
    }

    record PageAssistantManifestResponse(
            String schema,
            ProjectManifest project,
            AiCodingAccessManifest aiCodingAccess,
            PageAssistantAuthManifest auth,
            PageAssistantTargetManifest target,
            AiAccessSessionService.AccessSessionView session,
            PageAssistantEndpoints endpoints,
            SecurityGuidance security,
            LocalExecutionManifest localExecution,
            PageActionContractManifest pageActionContract,
            PageAssistantScaffoldManifest scaffold
    ) {
    }

    record ProjectManifest(
            Long id,
            String name,
            String projectCode,
            String projectKind,
            String environment,
            String baseUrl,
            String contextPath,
            String registryAppKey,
            boolean registryCredentialConfigured
    ) {
    }

    record AiCodingAccessManifest(
            boolean enabled,
            String accessKey
    ) {
    }

    record PageAssistantAuthManifest(
            String mode,
            String headerName,
            String keyEnv,
            String externalToolPath,
            String platformSessionPath,
            List<String> guidance
    ) {
    }

    record PageAssistantTargetManifest(
            String pageKey,
            String routePattern,
            List<String> actionKeys
    ) {
    }

    record AiCodingAccessUpdateRequest(
            Boolean enabled,
            String accessKey
    ) {
    }

    record AgentProvisionRequest(
            String agentKind,
            Boolean ensureDefaultWorkflow,
            String requestedBy
    ) {
    }

    record AgentProvisioningManifest(
            String model,
            String defaultAgentKind,
            String defaultKeySlug,
            String provisionAgentUrl,
            boolean idempotent,
            boolean createsDefaultWorkflow,
            boolean createsDefaultBinding,
            List<String> requiredSteps
    ) {
    }

    record AgentProvisionResponse(
            String schema,
            ProvisionedAgent agent,
            ProvisionedWorkflow defaultWorkflow,
            ProvisionedBinding defaultBinding,
            boolean createdAgent,
            boolean createdDefaultWorkflow,
            boolean createdDefaultBinding
    ) {
    }

    record ProvisionedAgent(
            String id,
            String keySlug,
            String name,
            String projectCode,
            String agentKind,
            boolean enabled
    ) {
    }

    record ProvisionedWorkflow(
            String id,
            String keySlug,
            String name,
            String workflowType,
            String status,
            String managedBy
    ) {
    }

    record ProvisionedBinding(
            Long id,
            String agentId,
            String workflowId,
            String bindingType,
            Boolean enabled
    ) {
    }

    record SdkManifest(
            String version,
            List<MavenDependency> dependencies,
            ReachAiConfigManifest config
    ) {
    }

    record MavenDependency(String groupId, String artifactId, String version) {
    }

    record ReachAiConfigManifest(
            String registryUrl,
            String appKey,
            String appSecretEnv,
            String projectCode,
            String projectName,
            String projectBaseUrl,
            String projectContextPath,
            String environment
    ) {
    }

    record PlatformEndpoints(
            String skillPackageUrl,
            String manifestUrl,
            String sdkAccessCheckUrl,
            String reconcileToolsUrl
    ) {
    }

    record PageAssistantEndpoints(
            String manifestUrl,
            String latestSessionUrl,
            String stepReportUrl,
            String targetBindUrl,
            String catalogSyncUrl,
            String checksRunUrl,
            String registerPageUrl,
            String skillPackageUrl,
            String scriptDownloadUrl
    ) {
    }

    record LocalExecutionManifest(
            boolean requiresLocalShell,
            String reason
    ) {
    }

    record PageActionContractManifest(
            String bridgeGlobal,
            String protocolVersion,
            List<String> supportedFrameworks,
            List<String> recommendedActions,
            PageActionSafetyManifest safety,
            PageActionBridgeApiManifest bridgeApi
    ) {
    }

    record PageActionBridgeApiManifest(
            String global,
            Map<String, String> methods,
            BridgeApiSchemas schemas,
            List<String> statusValues,
            List<String> errorCodes,
            List<BridgeApiExampleManifest> examples,
            PageActionSafetyManifest safety
    ) {
    }

    record BridgeApiSchemas(
            Map<String, Object> registerRequest,
            Map<String, Object> executeRequest,
            Map<String, Object> executeResponse
    ) {
    }

    record BridgeApiExampleManifest(
            String name,
            String actionKey,
            Map<String, Object> request,
            Map<String, Object> response
    ) {
    }

    record PageActionSafetyManifest(
            boolean readonlyFirst,
            boolean highRiskActionsRequireConfirm
    ) {
    }

    record PageAssistantScaffoldManifest(
            String framework,
            List<ScaffoldTemplateManifest> templates,
            String helperScriptPath,
            String scriptDownloadUrl,
            String skillPackageUrl,
            String scaffoldCommand,
            String verifyCommand
    ) {
    }

    record ScaffoldTemplateManifest(
            String name,
            String role
    ) {
    }

    record PageAssistantCatalogSyncResponse(
            String projectCode,
            String appId,
            String pageKey,
            int actionCount,
            AiAccessSessionService.AccessSessionView session,
            PageAssistantWorkflowBindingResult workflowBinding
    ) {
    }

    record PageAssistantPageRegisterResponse(
            AiAccessSessionService.AccessSessionView session,
            AiAccessSessionService.PageAssistantCheckResponse checkResult,
            RegisteredPageResponse registeredPage,
            List<String> registeredActions,
            List<AiAccessSessionService.PageAssistantFileEvidenceView> fileEvidence,
            PageAssistantWorkflowBindingResult workflowBinding
    ) {
    }

    record RegisteredPageResponse(
            String projectCode,
            String appId,
            String pageKey,
            String pageName,
            String routePattern,
            String framework,
            String bridgeGlobal
    ) {
    }

    record EmbedManifest(
            String tokenPath,
            String defaultAgentId,
            String defaultAgentKeySlug,
            List<EmbedAgentManifest> allowedAgents
    ) {
    }

    record EmbedAgentManifest(
            String id,
            String keySlug,
            String name,
            String projectCode,
            boolean enabled
    ) {
    }

    record AgentWorkflowManifest(
            String model,
            String globalAgentKeySlug,
            String globalAgentKind,
            String workflowStorage,
            String sdkGraphWorkflowType,
            String bindingStrategy,
            AgentWorkflowEndpoints endpoints,
            WorkflowAiCodingManifest workflowAiCoding,
            List<String> requiredSteps
    ) {
    }

    record WorkflowAiCodingManifest(
            String skillPackageUrl,
            String createUrl,
            String contextUrlTemplate,
            String patchUrlTemplate,
            String validateUrlTemplate,
            String runUrlTemplate,
            String versionsUrlTemplate,
            String publishUrlTemplate,
            String runsUrlTemplate,
            List<String> requiredSteps
    ) {
    }

    record AgentWorkflowEndpoints(
            String agentsUrl,
            String workflowsUrl,
            String globalAgentBindingsUrl,
            String resolvePreviewUrl
    ) {
    }

    record SecurityGuidance(String appSecretEnv, String message) {
    }

    public record ApiErrorResponse(String message) {
    }
}
