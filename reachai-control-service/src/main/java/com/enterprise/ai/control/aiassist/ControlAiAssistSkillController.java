package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.agentskill.AgentSkillCatalogService;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillDetail;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility facade for the public AI-assist download URLs.
 * Canonical package bytes and versions now come from the governed Agent Skill catalog.
 */
@RestController
@RequestMapping("/api/ai-assist")
public class ControlAiAssistSkillController {

    private static final String PUBLISHER = "reachai";
    private static final String AGENT_AI_CODING = "agent-ai-coding";
    private static final String ONBOARDING = "reachai-onboarding";
    private static final String WORKFLOW_AI_CODING = "workflow-ai-coding";

    private final AgentSkillCatalogService catalogService;

    public ControlAiAssistSkillController(AgentSkillCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/skills/reachai-onboarding/latest")
    public ResponseEntity<SkillPackageResponse> latestSkill(HttpServletRequest request) {
        return ResponseEntity.ok(skillResponse(request, ONBOARDING));
    }

    @GetMapping("/skills/workflow-ai-coding/latest")
    public ResponseEntity<SkillPackageResponse> latestWorkflowAiCodingSkill(HttpServletRequest request) {
        return ResponseEntity.ok(skillResponse(request, WORKFLOW_AI_CODING));
    }

    @GetMapping("/skills/agent-ai-coding/latest")
    public ResponseEntity<SkillPackageResponse> latestAgentAiCodingSkill(HttpServletRequest request) {
        return ResponseEntity.ok(skillResponse(request, AGENT_AI_CODING));
    }

    @GetMapping(value = "/skills/reachai-onboarding/latest.zip", produces = "application/zip")
    public ResponseEntity<byte[]> downloadLatestSkill() {
        return zipResponse(ONBOARDING);
    }

    @GetMapping(value = "/skills/workflow-ai-coding/latest.zip", produces = "application/zip")
    public ResponseEntity<byte[]> downloadLatestWorkflowAiCodingSkill() {
        return zipResponse(WORKFLOW_AI_CODING);
    }

    @GetMapping(value = "/skills/agent-ai-coding/latest.zip", produces = "application/zip")
    public ResponseEntity<byte[]> downloadLatestAgentAiCodingSkill() {
        return zipResponse(AGENT_AI_CODING);
    }

    private SkillPackageResponse skillResponse(HttpServletRequest request, String name) {
        VersionView version = catalogService.latestPublished(PUBLISHER, name);
        SkillDetail skill = catalogService.detail(version.skillId());
        List<SkillFileResponse> files = new ArrayList<>();
        if (version.packageManifest() != null && version.packageManifest().path("files").isArray()) {
            version.packageManifest().path("files").forEach(file -> {
                String path = file.path("path").asText(null);
                if (StringUtils.hasText(path)) {
                    files.add(new SkillFileResponse(path));
                }
            });
        }
        return new SkillPackageResponse(
                skill.skill().name(),
                version.version(),
                skill.skill().description(),
                requestBaseUrl(request) + "/api/ai-assist/skills/" + name + "/latest.zip",
                List.copyOf(files));
    }

    private ResponseEntity<byte[]> zipResponse(String name) {
        VersionView version = catalogService.latestPublished(PUBLISHER, name);
        byte[] body = catalogService.packageBytes(version);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name + "-" + version.version() + ".zip", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-ReachAI-Skill-SHA256", version.sourceSha256())
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(body.length)
                .body(body);
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

    record SkillPackageResponse(
            String name,
            String version,
            String description,
            String downloadUrl,
            List<SkillFileResponse> files) {
    }

    record SkillFileResponse(String path) {
    }
}
