package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportCommand;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportResult;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.FilePreview;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ReviewCommand;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ReviewView;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillDetail;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class AgentSkillController {

    static final String READ_PERMISSION = "skill:read";
    static final String IMPORT_PERMISSION = "skill:import";
    static final String REVIEW_PERMISSION = "skill:review";
    static final String PUBLISH_PERMISSION = "skill:publish";

    private final AgentSkillCatalogService catalogService;
    private final AgentSkillAccessPolicy accessPolicy;
    private final AgentSkillPackageInspector packageInspector;
    private final PlatformAuthAuditService auditService;

    @GetMapping
    public List<SkillSummary> list(HttpServletRequest request,
                                   @RequestParam(required = false) String search,
                                   @RequestParam(required = false) String status) {
        PlatformAuthenticatedSession session = require(request, READ_PERMISSION);
        return catalogService.list(search, status).stream()
                .filter(skill -> accessPolicy.canAccess(session, READ_PERMISSION, skill))
                .toList();
    }

    @GetMapping("/access")
    public AgentSkillContracts.SkillAccessCapabilities access(HttpServletRequest request) {
        PlatformAuthenticatedSession session = require(request, READ_PERMISSION);
        boolean canImport = session.permissions() != null
                && (session.permissions().contains(IMPORT_PERMISSION) || session.permissions().contains("*"));
        AgentSkillPackageInspector.PackageLimits limits = packageInspector.limits();
        return new AgentSkillContracts.SkillAccessCapabilities(
                canImport,
                canImport && session.hasGlobalPermission(IMPORT_PERMISSION),
                limits.maxPackageBytes(),
                limits.maxExpandedBytes(),
                limits.maxSingleFileBytes(),
                limits.maxFiles(),
                limits.maxInstructionBytes(),
                false);
    }

    @GetMapping("/bindable-versions")
    public List<AgentSkillContracts.BindingDescriptor> bindableVersions(
            HttpServletRequest request,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String agentProjectCode) {
        PlatformAuthenticatedSession session = require(request, "skill:bind");
        return catalogService.list(search, AgentSkillCatalogService.STATUS_PUBLISHED).stream()
                .filter(skill -> accessPolicy.canAccess(session, "skill:bind", skill))
                .filter(skill -> accessPolicy.canBindToAgentProject(skill, agentProjectCode))
                .flatMap(skill -> catalogService.detail(skill.id()).versions().stream()
                        .filter(version -> AgentSkillCatalogService.STATUS_PUBLISHED.equals(version.status()))
                        .map(version -> new AgentSkillContracts.BindingDescriptor(skill, version)))
                .toList();
    }

    @GetMapping("/{skillId}")
    public SkillDetail detail(HttpServletRequest request, @PathVariable Long skillId) {
        PlatformAuthenticatedSession session = require(request, READ_PERMISSION);
        SkillDetail detail = catalogService.detail(skillId);
        accessPolicy.requireAccess(session, READ_PERMISSION, detail.skill());
        return new SkillDetail(detail.skill(), detail.versions(),
                new AgentSkillContracts.SkillActions(
                        accessPolicy.canAccess(session, REVIEW_PERMISSION, detail.skill()),
                        accessPolicy.canAccess(session, PUBLISH_PERMISSION, detail.skill()),
                        accessPolicy.canAccess(session, "skill:bind", detail.skill())));
    }

    @GetMapping("/{skillId}/versions/{versionId}")
    public VersionView version(HttpServletRequest request,
                               @PathVariable Long skillId,
                               @PathVariable Long versionId) {
        PlatformAuthenticatedSession session = require(request, READ_PERMISSION);
        accessPolicy.requireAccess(session, READ_PERMISSION, catalogService.detail(skillId).skill());
        return catalogService.version(skillId, versionId);
    }

    @GetMapping("/{skillId}/versions/{versionId}/reviews")
    public List<ReviewView> reviews(HttpServletRequest request,
                                    @PathVariable Long skillId,
                                    @PathVariable Long versionId) {
        PlatformAuthenticatedSession session = require(request, READ_PERMISSION);
        accessPolicy.requireAccess(session, READ_PERMISSION, catalogService.detail(skillId).skill());
        return catalogService.reviews(skillId, versionId);
    }

    @PostMapping(value = "/discoveries", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AgentSkillPackageInspector.BundleDiscovery discoverBundle(
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file) throws IOException {
        require(request, IMPORT_PERMISSION);
        if (file == null || file.isEmpty()) {
            throw AgentSkillException.invalidPackage("Skill ZIP is required");
        }
        return packageInspector.discoverBundle(file.getBytes());
    }

    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importPackage(
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String sourceRoot) throws IOException {
        PlatformAuthenticatedSession actor = require(request, IMPORT_PERMISSION);
        accessPolicy.requireImportScope(actor, IMPORT_PERMISSION, visibility, projectCode);
        if (file == null || file.isEmpty()) {
            throw AgentSkillException.invalidPackage("Skill ZIP is required");
        }
        byte[] uploadedArchive = file.getBytes();
        byte[] importArchive = uploadedArchive;
        AgentSkillPackageInspector.SelectedPackage selected = null;
        if (StringUtils.hasText(sourceRoot)) {
            selected = packageInspector.selectFromBundle(uploadedArchive, sourceRoot);
            importArchive = selected.archive();
        }
        String selectedRoot = selected == null ? null : selected.sourceRoot();
        String bundleSourceSha256 = selected == null
                ? AgentSkillPackageInspector.sha256(uploadedArchive)
                : selected.bundleSourceSha256();
        ImportResult result = catalogService.importPackage(importArchive, new ImportCommand(
                publisher,
                version,
                displayName,
                visibility,
                "UPLOAD",
                safeSourceRef(file.getOriginalFilename(), selectedRoot),
                actor(actor),
                actor.user() == null ? null : actor.user().getId(),
                projectCode));
        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("skillId", result.skill().id());
        auditDetails.put("name", result.skill().name());
        auditDetails.put("publisher", result.skill().publisher());
        auditDetails.put("version", result.version().version());
        auditDetails.put("sourceSha256", result.version().sourceSha256());
        auditDetails.put("bundleSourceSha256", bundleSourceSha256);
        if (selectedRoot != null) {
            auditDetails.put("bundleSkillRoot", selectedRoot);
        }
        auditDetails.put("created", result.created());
        auditDetails.put("artifactSize", result.version().artifactSize());
        auditService.record(actor, "AGENT_SKILL_IMPORTED", "AGENT_SKILL_VERSION",
                result.version().id().toString(),
                auditDetails);
        return result;
    }

    @PostMapping("/{skillId}/versions/{versionId}/reviews")
    public VersionView review(HttpServletRequest request,
                              @PathVariable Long skillId,
                              @PathVariable Long versionId,
                              @RequestBody ReviewRequest body) {
        PlatformAuthenticatedSession actor = require(request, REVIEW_PERMISSION);
        accessPolicy.requireAccess(actor, REVIEW_PERMISSION, catalogService.detail(skillId).skill());
        VersionView result = catalogService.review(skillId, versionId,
                new ReviewCommand(body == null ? null : body.decision(),
                        body == null ? null : body.comment(),
                        body == null ? null : body.findings(),
                        actor(actor)));
        auditService.record(actor, "AGENT_SKILL_REVIEWED", "AGENT_SKILL_VERSION", versionId.toString(),
                Map.of("skillId", skillId, "decision", body.decision(), "status", result.status()));
        return result;
    }

    @PostMapping("/{skillId}/versions/{versionId}/publish")
    public VersionView publish(HttpServletRequest request,
                               @PathVariable Long skillId,
                               @PathVariable Long versionId) {
        return transition(request, skillId, versionId, "AGENT_SKILL_PUBLISHED",
                actor -> catalogService.publish(skillId, versionId, actor));
    }

    @PostMapping("/{skillId}/versions/{versionId}/default")
    public VersionView setDefault(HttpServletRequest request,
                                  @PathVariable Long skillId,
                                  @PathVariable Long versionId) {
        return transition(request, skillId, versionId, "AGENT_SKILL_DEFAULT_CHANGED",
                actor -> catalogService.setDefault(skillId, versionId, actor));
    }

    @PostMapping("/{skillId}/versions/{versionId}/deprecate")
    public VersionView deprecate(HttpServletRequest request,
                                 @PathVariable Long skillId,
                                 @PathVariable Long versionId) {
        return transition(request, skillId, versionId, "AGENT_SKILL_DEPRECATED",
                actor -> catalogService.deprecate(skillId, versionId, actor));
    }

    @PostMapping("/{skillId}/versions/{versionId}/revoke")
    public VersionView revoke(HttpServletRequest request,
                              @PathVariable Long skillId,
                              @PathVariable Long versionId) {
        return transition(request, skillId, versionId, "AGENT_SKILL_REVOKED",
                actor -> catalogService.revoke(skillId, versionId, actor));
    }

    @GetMapping(value = "/{skillId}/versions/{versionId}/package", produces = "application/zip")
    public ResponseEntity<byte[]> download(HttpServletRequest request,
                                           @PathVariable Long skillId,
                                           @PathVariable Long versionId) {
        SkillDetail skill = catalogService.detail(skillId);
        PlatformAuthenticatedSession actor = require(request, READ_PERMISSION);
        accessPolicy.requireAccess(actor, READ_PERMISSION, skill.skill());
        VersionView version = catalogService.version(skillId, versionId);
        byte[] body = catalogService.packageBytes(skillId, versionId);
        String filename = skill.skill().name() + "-" + version.version() + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(body.length)
                .header("X-ReachAI-Skill-SHA256", version.sourceSha256())
                .body(body);
    }

    @GetMapping("/{skillId}/versions/{versionId}/files")
    public FilePreview previewFile(HttpServletRequest request,
                                   @PathVariable Long skillId,
                                   @PathVariable Long versionId,
                                   @RequestParam String path) {
        PlatformAuthenticatedSession actor = require(request, READ_PERMISSION);
        accessPolicy.requireAccess(actor, READ_PERMISSION, catalogService.detail(skillId).skill());
        return catalogService.previewFile(skillId, versionId, path);
    }

    private VersionView transition(HttpServletRequest request,
                                   Long skillId,
                                   Long versionId,
                                   String eventType,
                                   Transition transition) {
        PlatformAuthenticatedSession session = require(request, PUBLISH_PERMISSION);
        accessPolicy.requireAccess(session, PUBLISH_PERMISSION, catalogService.detail(skillId).skill());
        VersionView result = transition.apply(actor(session));
        auditService.record(session, eventType, "AGENT_SKILL_VERSION", versionId.toString(),
                Map.of("skillId", skillId, "status", result.status(), "version", result.version()));
        return result;
    }

    private PlatformAuthenticatedSession require(HttpServletRequest request, String permission) {
        Object candidate = request == null ? null
                : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "live ReachAI platform login is required");
        }
        accessPolicy.requirePermission(session, permission);
        return session;
    }

    private String actor(PlatformAuthenticatedSession session) {
        if (session == null || session.user() == null || !StringUtils.hasText(session.user().getUsername())) {
            return "SYSTEM";
        }
        return session.user().getUsername();
    }

    private String safeFilename(String value) {
        if (!StringUtils.hasText(value)) {
            return "uploaded-skill.zip";
        }
        String normalized = value.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        return normalized.length() > 240 ? normalized.substring(normalized.length() - 240) : normalized;
    }

    private String safeSourceRef(String filename, String sourceRoot) {
        String safeFilename = safeFilename(filename);
        if (!StringUtils.hasText(sourceRoot)) {
            return safeFilename;
        }
        int rootBudget = 512 - safeFilename.length() - 1;
        String safeRoot = sourceRoot;
        if (safeRoot.length() > rootBudget) {
            safeRoot = "~" + safeRoot.substring(safeRoot.length() - rootBudget + 1);
        }
        return safeFilename + "#" + safeRoot;
    }

    @FunctionalInterface
    private interface Transition {
        VersionView apply(String actor);
    }

    public record ReviewRequest(String decision, String comment, JsonNode findings) {
    }
}
