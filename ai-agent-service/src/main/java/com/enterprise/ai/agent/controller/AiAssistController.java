package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.assist.AiAccessSessionService;
import com.enterprise.ai.agent.identity.PageActionCatalogContracts;
import com.enterprise.ai.agent.identity.PageActionCatalogService;
import com.enterprise.ai.agent.identity.PageCatalogRegisterResult;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.agent.registry.SdkAccessCheckService;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.workflow.PageAssistantWorkflowBindingResult;
import com.enterprise.ai.agent.workflow.PageAssistantWorkflowBindingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
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
import java.net.URLEncoder;
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

    private final ScanProjectService scanProjectService;
    private final RegistrySecurityService registrySecurityService;
    private final AgentDefinitionService agentDefinitionService;
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

    @GetMapping("/projects/{projectId}/onboarding-manifest")
    public ResponseEntity<?> onboardingManifest(@PathVariable Long projectId,
                                                @RequestParam(value = "aiCodingKey", required = false) String aiCodingKey,
                                                HttpServletRequest request) {
        try {
            if (StringUtils.hasText(aiCodingKey)
                    && !scanProjectService.matchesAiCodingAccessKey(projectId, aiCodingKey)) {
                return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
            }
            ScanProjectEntity project = scanProjectService.getById(projectId);
            String baseUrl = requestBaseUrl(request);
            Optional<RegistryCredentialEntity> credential = StringUtils.hasText(project.getProjectCode())
                    ? registrySecurityService.findPrimaryActiveCredential(project.getProjectCode())
                    : Optional.empty();
            String appKey = credential.map(RegistryCredentialEntity::getAppKey).orElse(null);
            EmbedManifest embed = buildEmbedManifest(project, credential);

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
                            project.getAiCodingAccessKey()
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
                            baseUrl + "/api/ai-assist/projects/" + projectId + "/onboarding-manifest",
                            baseUrl + "/api/scan-projects/" + projectId + "/sdk-access-check",
                            baseUrl + "/api/scan-projects/" + projectId + "/tools/reconcile"
                    ),
                    embed,
                    buildAgentWorkflowManifest(project, embed, baseUrl),
                    new SecurityGuidance(
                            SECRET_ENV_NAME,
                            "Do not paste or write the registry app secret into AI chat context. Store it in a local environment variable or secret manager."
                    )
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/projects/{projectId}/page-assistant/onboarding-manifest")
    public ResponseEntity<?> pageAssistantManifest(@PathVariable Long projectId,
                                                   @RequestParam(value = "pageKey", required = false) String pageKey,
                                                   @RequestParam(value = "routePattern", required = false) String routePattern,
                                                   @RequestParam(value = "actionKeys", required = false) List<String> actionKeys,
                                                   @RequestParam(value = "toolName", required = false) String toolName,
                                                   @RequestParam(value = "aiCodingKey", required = false) String aiCodingKey,
                                                   HttpServletRequest request) {
        try {
            if (StringUtils.hasText(aiCodingKey)
                    && !scanProjectService.matchesAiCodingAccessKey(projectId, aiCodingKey)) {
                return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
            }
            ScanProjectEntity project = scanProjectService.getById(projectId);
            String baseUrl = requestBaseUrl(request);
            Optional<RegistryCredentialEntity> credential = StringUtils.hasText(project.getProjectCode())
                    ? registrySecurityService.findPrimaryActiveCredential(project.getProjectCode())
                    : Optional.empty();
            String appKey = credential.map(RegistryCredentialEntity::getAppKey).orElse(null);
            AiAccessSessionService.AccessSessionView session = accessSessionService.getOrCreatePageAssistantLatest(
                    projectId,
                    new AiAccessSessionService.PageAssistantSessionRequest(toolName, pageKey, routePattern, actionKeys));
            String manifestUrl = baseUrl + "/api/ai-assist/projects/" + projectId + "/page-assistant/onboarding-manifest";
            String latestSessionUrl = baseUrl + "/api/ai-assist/projects/" + projectId + "/page-assistant/sessions/latest";
            String stepReportUrl = baseUrl + "/api/ai-assist/projects/" + projectId
                    + "/page-assistant/sessions/" + session.sessionId() + "/steps/{stepKey}/report";
            String targetBindUrl = baseUrl + "/api/ai-assist/projects/" + projectId
                    + "/page-assistant/sessions/" + session.sessionId() + "/target";
            String catalogSyncUrl = baseUrl + "/api/ai-assist/projects/" + projectId
                    + "/page-assistant/sessions/" + session.sessionId() + "/catalog/sync";
            String checksRunUrl = baseUrl + "/api/ai-assist/projects/" + projectId
                    + "/page-assistant/sessions/" + session.sessionId() + "/checks/run";
            String registerPageUrl = baseUrl + "/api/ai-assist/projects/" + projectId
                    + "/page-assistant/pages/register";
            if (StringUtils.hasText(aiCodingKey)) {
                manifestUrl = appendQuery(manifestUrl, "aiCodingKey", aiCodingKey);
                latestSessionUrl = appendQuery(latestSessionUrl, "aiCodingKey", aiCodingKey);
                stepReportUrl = appendQuery(stepReportUrl, "aiCodingKey", aiCodingKey);
                targetBindUrl = appendQuery(targetBindUrl, "aiCodingKey", aiCodingKey);
                catalogSyncUrl = appendQuery(catalogSyncUrl, "aiCodingKey", aiCodingKey);
                checksRunUrl = appendQuery(checksRunUrl, "aiCodingKey", aiCodingKey);
                registerPageUrl = appendQuery(registerPageUrl, "aiCodingKey", aiCodingKey);
            }
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
                            project.getAiCodingAccessKey()
                    ),
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
                            registerPageUrl
                    ),
                    new SecurityGuidance(
                            SECRET_ENV_NAME,
                            "Do not paste or write the registry app secret into AI chat context. Store it in a local environment variable or secret manager."
                    ),
                    new LocalExecutionManifest(
                            true,
                            "localhost platform APIs usually cannot be reached from remote WebFetch. Use local PowerShell/curl from the business frontend machine."),
                    new PageActionContractManifest(
                            "__REACHAI_PAGE_BRIDGE__",
                            "1.0",
                            List.of("angular"),
                            List.of("getPageState", "setFilters", "search", "reset", "readTable", "openRowAction"),
                            new PageActionSafetyManifest(true, true)),
                    new PageAssistantScaffoldManifest(
                            "angular",
                            List.of(
                                    new ScaffoldTemplateManifest("reachai-page-action.types.ts", "types"),
                                    new ScaffoldTemplateManifest("reachai-page-action.service.ts", "bridge-service"),
                                    new ScaffoldTemplateManifest("page-registry.example.ts", "page-registry")
                            ))
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
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
                                                        @RequestParam(value = "pageKey", required = false) String pageKey,
                                                        @RequestParam(value = "aiCodingKey", required = false) String aiCodingKey) {
        if (invalidAiCodingKey(projectId, aiCodingKey)) {
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
                                                       @RequestParam(value = "pageKey", required = false) String pageKey,
                                                       @RequestParam(value = "aiCodingKey", required = false) String aiCodingKey) {
        if (invalidAiCodingKey(projectId, aiCodingKey)) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            return ResponseEntity.ok(accessSessionService.listPageAssistantSessions(projectId, pageKey));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/access-sessions/latest")
    public ResponseEntity<?> latestAccessSession(@PathVariable Long projectId,
                                                 @RequestParam(value = "aiCodingKey", required = false) String aiCodingKey) {
        if (invalidAiCodingKey(projectId, aiCodingKey)) {
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
                                                     @RequestParam(value = "aiCodingKey", required = false) String aiCodingKey,
                                                     @RequestBody(required = false)
                                                     AiAccessSessionService.StepReportRequest request) {
        if (invalidAiCodingKey(projectId, aiCodingKey)) {
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
                                                            @RequestParam(value = "aiCodingKey", required = false) String aiCodingKey,
                                                            @RequestBody(required = false)
                                                            AiAccessSessionService.StepReportRequest request) {
        if (invalidAiCodingKey(projectId, aiCodingKey)) {
            return ResponseEntity.status(403).body(new ApiErrorResponse("invalid AI Coding access key"));
        }
        try {
            return ResponseEntity.ok(accessSessionService.reportStep(projectId, sessionId, stepKey, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PutMapping("/projects/{projectId}/page-assistant/sessions/{sessionId}/target")
    public ResponseEntity<?> bindPageAssistantSessionTarget(@PathVariable Long projectId,
                                                            @PathVariable String sessionId,
                                                            @RequestParam(value = "aiCodingKey", required = false) String aiCodingKey,
                                                            @RequestBody(required = false)
                                                            AiAccessSessionService.PageAssistantTargetRequest request) {
        if (invalidAiCodingKey(projectId, aiCodingKey)) {
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
                                                      @RequestParam(value = "aiCodingKey", required = false) String aiCodingKey,
                                                      @RequestBody(required = false)
                                                      PageActionCatalogContracts.PageCatalogRegisterRequest request) {
        if (invalidAiCodingKey(projectId, aiCodingKey)) {
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

    @PostMapping("/projects/{projectId}/page-assistant/pages/register")
    public ResponseEntity<?> registerPageAssistantPage(@PathVariable Long projectId,
                                                       @RequestParam(value = "aiCodingKey", required = false) String aiCodingKey,
                                                       @RequestBody(required = false)
                                                       AiAccessSessionService.PageAssistantPageRegisterRequest request) {
        if (invalidAiCodingKey(projectId, aiCodingKey)) {
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
            AiAccessSessionService.PageAssistantPageRegisterSessionResult sessionResult =
                    accessSessionService.applyPageAssistantPageRegistration(projectId, request, registerResult);
            List<String> actionKeys = request == null || request.actions() == null
                    ? List.of()
                    : request.actions().stream()
                    .map(PageActionCatalogContracts.PageActionDefinitionRequest::actionKey)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .toList();
            PageAssistantWorkflowBindingResult workflowBinding =
                    pageAssistantWorkflowBindingService.ensurePageWorkflowBinding(
                            project,
                            request == null ? null : request.pageKey(),
                            request == null ? null : request.routePattern(),
                            actionKeys);
            AiAccessSessionService.PageAssistantCheckRunResponse checkRun =
                    accessSessionService.runPageAssistantChecks(
                            projectId,
                            sessionResult.session().sessionId(),
                            new AiAccessSessionService.PageAssistantCheckRequest(
                                    request == null ? null : request.pageKey(),
                                    request == null ? null : request.routePattern(),
                                    actionKeys));
            return ResponseEntity.ok(new PageAssistantPageRegisterResponse(
                    checkRun.session(),
                    checkRun.checkResult(),
                    new RegisteredPageResponse(
                            registerResult.projectCode(),
                            registerResult.appId(),
                            registerResult.pageKey(),
                            request == null ? null : request.pageName(),
                            request == null ? null : request.routePattern(),
                            request == null ? null : request.framework(),
                            request == null ? null : request.bridgeGlobal()),
                    actionKeys,
                    sessionResult.fileEvidence(),
                    workflowBinding));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    private static PageActionCatalogContracts.PageCatalogRegisterRequest toCatalogRegisterRequest(
            AiAccessSessionService.PageAssistantPageRegisterRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("page assistant register request is required");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "ai-coding");
        metadata.put("framework", emptyToNull(request.framework()));
        metadata.put("frameworkVersion", emptyToNull(request.frameworkVersion()));
        metadata.put("bridgeGlobal", emptyToNull(request.bridgeGlobal()));
        metadata.put("files", request.files() == null ? List.of() : request.files());
        metadata.put("verification", request.verification() == null ? Map.of() : request.verification());
        metadata.put("handoffSummary", emptyToNull(request.handoffSummary()));
        return new PageActionCatalogContracts.PageCatalogRegisterRequest(
                request.pageKey(),
                request.pageName(),
                request.routePattern(),
                "ai-coding",
                null,
                request.replaceActions(),
                request.actions() == null ? List.of() : request.actions(),
                metadata);
    }

    @PostMapping("/projects/{projectId}/access-sessions/{sessionId}/checks/run")
    public ResponseEntity<?> runAccessSessionChecks(@PathVariable Long projectId,
                                                    @PathVariable String sessionId,
                                                    @RequestBody(required = false)
                                                    SdkAccessCheckService.SdkAccessCheckRequest request) {
        try {
            return ResponseEntity.ok(accessSessionService.runChecks(projectId, sessionId, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/page-assistant/sessions/{sessionId}/checks/run")
    public ResponseEntity<?> runPageAssistantSessionChecks(@PathVariable Long projectId,
                                                           @PathVariable String sessionId,
                                                           @RequestParam(value = "aiCodingKey", required = false) String aiCodingKey,
                                                           @RequestBody(required = false)
                                                           AiAccessSessionService.PageAssistantCheckRequest request) {
        if (invalidAiCodingKey(projectId, aiCodingKey)) {
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
        List<AgentDefinition> definitions = allowedRefs.isEmpty()
                ? listProjectEmbedAgents(project)
                : resolveAllowedAgents(allowedRefs);
        List<EmbedAgentManifest> agents = definitions.stream()
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

    private AgentWorkflowManifest buildAgentWorkflowManifest(ScanProjectEntity project, EmbedManifest embed, String baseUrl) {
        String fallbackProjectCode = StringUtils.hasText(project.getProjectCode())
                ? project.getProjectCode().trim()
                : "project-" + project.getId();
        String globalAgentKeySlug = firstNonBlank(
                embed == null ? null : embed.defaultAgentKeySlug(),
                fallbackProjectCode + "-global-ai-assistant");
        return new AgentWorkflowManifest(
                "agent-workflow.decoupled.v1",
                globalAgentKeySlug,
                "GLOBAL_EMBED",
                "ai_workflow",
                "SDK_GRAPH",
                "Bind page/action/intent workflows to the global embedded agent instead of creating one agent per workflow.",
                new AgentWorkflowEndpoints(
                        baseUrl + "/api/agents",
                        baseUrl + "/api/workflows",
                        baseUrl + "/api/agents/" + globalAgentKeySlug + "/workflow-bindings",
                        baseUrl + "/api/agents/" + globalAgentKeySlug + "/workflow-bindings/resolve-preview"
                ),
                List.of(
                        "Create or reuse one project-level GLOBAL_EMBED agent entry.",
                        "Store every executable graph as an ai_workflow draft or version.",
                        "Create ai_agent_workflow_binding rows for DEFAULT, PAGE, ACTION, ROUTE, or INTENT routing.",
                        "Do not treat LangGraph runtime configuration as the Agent identity."
                )
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean invalidAiCodingKey(Long projectId, String aiCodingKey) {
        return StringUtils.hasText(aiCodingKey)
                && !scanProjectService.matchesAiCodingAccessKey(projectId, aiCodingKey);
    }

    private List<AgentDefinition> listProjectEmbedAgents(ScanProjectEntity project) {
        if (project == null || project.getId() == null) {
            return List.of();
        }
        return agentDefinitionService.list(project.getId()).stream()
                .filter(AgentDefinition::isEnabled)
                .toList();
    }

    private List<AgentDefinition> resolveAllowedAgents(List<String> allowedRefs) {
        Map<String, AgentDefinition> unique = new LinkedHashMap<>();
        for (String ref : allowedRefs == null ? List.<String>of() : allowedRefs) {
            if (!StringUtils.hasText(ref)) {
                continue;
            }
            String value = ref.trim();
            AgentDefinition agent = agentDefinitionService.findById(value)
                    .or(() -> agentDefinitionService.findByKeySlug(value))
                    .orElse(null);
            if (agent != null) {
                unique.put(agent.getId(), agent);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static EmbedAgentManifest toEmbedAgentManifest(AgentDefinition agent) {
        return new EmbedAgentManifest(
                agent.getId(),
                emptyToNull(agent.getKeySlug()),
                agent.getName(),
                emptyToNull(agent.getProjectCode()),
                agent.isEnabled()
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

    private static String requestBaseUrl(HttpServletRequest request) {
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

    private static String appendQuery(String url, String key, String value) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator
                + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
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
            AgentWorkflowManifest agentWorkflow,
            SecurityGuidance security
    ) {
    }

    record PageAssistantManifestResponse(
            String schema,
            ProjectManifest project,
            AiCodingAccessManifest aiCodingAccess,
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
            String registerPageUrl
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
            PageActionSafetyManifest safety
    ) {
    }

    record PageActionSafetyManifest(
            boolean readonlyFirst,
            boolean highRiskActionsRequireConfirm
    ) {
    }

    record PageAssistantScaffoldManifest(
            String framework,
            List<ScaffoldTemplateManifest> templates
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
            List<AiAccessSessionService.PageAssistantFileEvidence> fileEvidence,
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

    record ApiErrorResponse(String message) {
    }
}
