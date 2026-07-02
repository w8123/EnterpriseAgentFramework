package com.enterprise.ai.control.aiassist;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-assist")
public class ControlAiAssistSkillController {

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
            PAGE_ASSISTANT_HELPER_SCRIPT
    );

    private static final List<String> WORKFLOW_AI_CODING_SKILL_FILES = List.of(
            "SKILL.md",
            "references/graphspec.md",
            "references/page-assistant.md",
            "references/safety.md",
            "references/workflow-apis.md"
    );

    @GetMapping("/skills/reachai-onboarding/latest")
    public ResponseEntity<SkillPackageResponse> latestSkill(HttpServletRequest request) {
        return ResponseEntity.ok(skillResponse(
                request,
                SKILL_NAME,
                SKILL_VERSION,
                "ReachAI SDK onboarding skill for AI coding tools.",
                SKILL_FILES));
    }

    @GetMapping("/skills/reachai-page-assistant-onboarding/latest")
    public ResponseEntity<SkillPackageResponse> latestPageAssistantSkill(HttpServletRequest request) {
        return ResponseEntity.ok(skillResponse(
                request,
                PAGE_ASSISTANT_SKILL_NAME,
                PAGE_ASSISTANT_SKILL_VERSION,
                "ReachAI Page Assistant onboarding skill for AI coding tools.",
                PAGE_ASSISTANT_SKILL_FILES));
    }

    @GetMapping("/skills/workflow-ai-coding/latest")
    public ResponseEntity<SkillPackageResponse> latestWorkflowAiCodingSkill(HttpServletRequest request) {
        return ResponseEntity.ok(skillResponse(
                request,
                WORKFLOW_AI_CODING_SKILL_NAME,
                WORKFLOW_AI_CODING_SKILL_VERSION,
                "ReachAI Workflow AI Coding skill for editing, validating, and debugging workflow drafts.",
                WORKFLOW_AI_CODING_SKILL_FILES));
    }

    @GetMapping(value = "/skills/reachai-onboarding/latest.zip", produces = "application/zip")
    public ResponseEntity<byte[]> downloadLatestSkill() throws IOException {
        return zipResponse(SKILL_NAME, SKILL_VERSION, SKILL_ROOT, SKILL_FILES);
    }

    @GetMapping(value = "/skills/reachai-page-assistant-onboarding/latest.zip", produces = "application/zip")
    public ResponseEntity<byte[]> downloadLatestPageAssistantSkill() throws IOException {
        return zipResponse(PAGE_ASSISTANT_SKILL_NAME, PAGE_ASSISTANT_SKILL_VERSION,
                PAGE_ASSISTANT_SKILL_ROOT, PAGE_ASSISTANT_SKILL_FILES);
    }

    @GetMapping(value = "/skills/workflow-ai-coding/latest.zip", produces = "application/zip")
    public ResponseEntity<byte[]> downloadLatestWorkflowAiCodingSkill() throws IOException {
        return zipResponse(WORKFLOW_AI_CODING_SKILL_NAME, WORKFLOW_AI_CODING_SKILL_VERSION,
                WORKFLOW_AI_CODING_SKILL_ROOT, WORKFLOW_AI_CODING_SKILL_FILES);
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

    private static SkillPackageResponse skillResponse(HttpServletRequest request,
                                                      String name,
                                                      String version,
                                                      String description,
                                                      List<String> files) {
        return new SkillPackageResponse(
                name,
                version,
                description,
                requestBaseUrl(request) + "/api/ai-assist/skills/" + name + "/latest.zip",
                files.stream().map(SkillFileResponse::new).toList());
    }

    private static ResponseEntity<byte[]> zipResponse(String name,
                                                      String version,
                                                      String root,
                                                      List<String> files) throws IOException {
        byte[] body = zipSkillFiles(name, root, files);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name + "-" + version + ".zip", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(body.length)
                .body(body);
    }

    private static byte[] zipSkillFiles(String skillName, String skillRoot, List<String> skillFiles) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String path : skillFiles) {
                ClassPathResource resource = new ClassPathResource(skillRoot + path);
                if (!resource.exists()) {
                    throw new IOException("Missing ReachAI skill resource: " + path);
                }
                zip.putNextEntry(new ZipEntry(skillName + "/" + path));
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
}
