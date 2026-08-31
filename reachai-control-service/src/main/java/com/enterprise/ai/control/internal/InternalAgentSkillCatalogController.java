package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.agentskill.AgentSkillCatalogService;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.BindingDescriptor;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import com.enterprise.ai.control.agentskill.AgentSkillException;
import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.List;
import java.util.Locale;

/** Runtime-facing package boundary. Runtime never reads Control-owned Skill tables directly. */
@RestController
@RequestMapping("/internal/control/agent-skills")
@RequiredArgsConstructor
public class InternalAgentSkillCatalogController {

    private static final Set<String> EXECUTABLE_STATUSES = Set.of("PUBLISHED", "DEPRECATED");
    private static final String APPLICATION_ZIP = "application/zip";

    private final AgentSkillCatalogService catalogService;
    private final ControlInternalServiceAuthVerifier authVerifier;
    private final ObjectMapper objectMapper;

    @PostMapping("/resolve-execution")
    public List<ExecutionResolution> resolveExecution(HttpServletRequest request,
                                                      @RequestBody byte[] exactBody) {
        authVerifier.requireRuntime(request, exactBody);
        List<ExecutionReference> input;
        try {
            input = objectMapper.readValue(exactBody,
                    new TypeReference<List<ExecutionReference>>() { });
        } catch (Exception exception) {
            throw AgentSkillException.invalidState("Agent Skill execution resolution body is invalid");
        }
        if (input == null) input = List.of();
        if (input.size() > 64) {
            throw AgentSkillException.invalidState("At most 64 Agent Skill versions can be resolved per execution");
        }
        return input.stream().map(this::resolveReference).toList();
    }

    private ExecutionResolution resolveReference(ExecutionReference reference) {
        if (reference == null
                || reference.skillId() == null || reference.skillId() < 1L
                || reference.skillVersionId() == null || reference.skillVersionId() < 1L
                || reference.sourceSha256() == null
                || !reference.sourceSha256().toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}")) {
            return new ExecutionResolution(
                    reference == null ? null : reference.skillId(),
                    reference == null ? null : reference.skillVersionId(),
                    "INVALID_REFERENCE", null, false, "REFERENCE_INVALID");
        }
        try {
            VersionView version = catalogService.version(reference.skillId(), reference.skillVersionId());
            boolean digestMatches = version.sourceSha256().equalsIgnoreCase(reference.sourceSha256());
            boolean executable = digestMatches && EXECUTABLE_STATUSES.contains(version.status());
            String reason = !digestMatches ? "DIGEST_MISMATCH"
                    : (executable ? "EXECUTABLE" : "STATUS_" + version.status());
            return new ExecutionResolution(reference.skillId(), reference.skillVersionId(),
                    version.status(), version.sourceSha256(), executable, reason);
        } catch (AgentSkillException exception) {
            if (!"SKILL_NOT_FOUND".equals(exception.code())) {
                throw exception;
            }
            return new ExecutionResolution(reference.skillId(), reference.skillVersionId(),
                    "MISSING", null, false, "NOT_FOUND");
        }
    }

    @GetMapping("/{skillId}/versions/{versionId}")
    public BindingDescriptor descriptor(HttpServletRequest request,
                                        @PathVariable Long skillId,
                                        @PathVariable Long versionId) {
        authVerifier.requireRuntime(request, new byte[0]);
        return executableDescriptor(skillId, versionId);
    }

    private BindingDescriptor executableDescriptor(Long skillId, Long versionId) {
        VersionView version = catalogService.version(skillId, versionId);
        if (!EXECUTABLE_STATUSES.contains(version.status())) {
            throw AgentSkillException.invalidState(
                    "Skill version is not executable from status " + version.status());
        }
        return new BindingDescriptor(catalogService.detail(skillId).skill(), version);
    }

    @GetMapping(value = "/{skillId}/versions/{versionId}/package", produces = APPLICATION_ZIP)
    public ResponseEntity<byte[]> packageBytes(HttpServletRequest request,
                                               @PathVariable Long skillId,
                                               @PathVariable Long versionId) {
        authVerifier.requireRuntime(request, new byte[0]);
        BindingDescriptor descriptor = executableDescriptor(skillId, versionId);
        byte[] body = catalogService.packageBytes(skillId, versionId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(body.length)
                .header("X-ReachAI-Skill-SHA256", descriptor.version().sourceSha256())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .body(body);
    }

    public record ExecutionReference(Long skillId, Long skillVersionId, String sourceSha256) {
    }

    public record ExecutionResolution(Long skillId,
                                      Long skillVersionId,
                                      String status,
                                      String sourceSha256,
                                      boolean executable,
                                      String reason) {
    }
}
