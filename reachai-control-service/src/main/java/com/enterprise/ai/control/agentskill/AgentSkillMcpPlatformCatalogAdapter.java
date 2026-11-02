package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillGovernancePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillPackagePort;
import com.enterprise.ai.control.identity.PlatformUploadedFile;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Shares human catalog visibility and package-path validation with personal AI delegates. */
@Component
@RequiredArgsConstructor
final class AgentSkillMcpPlatformCatalogAdapter implements McpPlatformSkillCatalogPort, McpPlatformSkillGovernancePort, McpPlatformSkillPackagePort {
    private final AgentSkillController skills;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> list(PlatformAuthenticatedSession actor, String search, String status, int offset, int limit) {
        return invoke(() -> scope.run(actor, request -> page(skills.list(request, search, status), offset, limit)));
    }

    @Override
    public ResponseEntity<?> access(PlatformAuthenticatedSession actor) {
        return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(skills.access(request))));
    }

    @Override
    public ResponseEntity<?> get(PlatformAuthenticatedSession actor, long skillId) {
        return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(skills.detail(request, skillId))));
    }

    @Override
    public ResponseEntity<?> version(PlatformAuthenticatedSession actor, long skillId, long versionId) {
        return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(skills.version(request, skillId, versionId))));
    }

    @Override
    public ResponseEntity<?> bindableVersions(PlatformAuthenticatedSession actor, String search, String agentProjectCode,
                                              int offset, int limit) {
        return invoke(() -> scope.run(actor, request -> page(
                skills.bindableVersions(request, search, agentProjectCode), offset, limit)));
    }

    @Override
    public ResponseEntity<?> reviews(PlatformAuthenticatedSession actor, long skillId, long versionId) {
        return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(skills.reviews(request, skillId, versionId))));
    }

    @Override
    public ResponseEntity<?> file(PlatformAuthenticatedSession actor, long skillId, long versionId, String path) {
        return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(skills.previewFile(request, skillId, versionId, path))));
    }

    @Override
    public ResponseEntity<?> change(PlatformAuthenticatedSession actor, long skillId, long versionId, Operation operation,
                                    String decision, String comment, JsonNode findings) {
        return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(switch (operation) {
            case REVIEW -> skills.review(request, skillId, versionId, new AgentSkillController.ReviewRequest(decision, comment, findings));
            case PUBLISH -> skills.publish(request, skillId, versionId);
            case SET_DEFAULT -> skills.setDefault(request, skillId, versionId);
            case DEPRECATE -> skills.deprecate(request, skillId, versionId);
            case REVOKE -> skills.revoke(request, skillId, versionId);
        })));
    }

    @Override
    public ResponseEntity<?> discover(PlatformAuthenticatedSession actor, byte[] archive) {
        return invoke(() -> scope.run(actor, request -> {
            try { return ResponseEntity.ok(skills.discoverBundle(request, new PlatformUploadedFile("skill.zip", archive))); }
            catch (java.io.IOException unreadable) { throw AgentSkillException.invalidPackage("Skill ZIP is unreadable"); }
        }));
    }

    @Override
    public ResponseEntity<?> importPackage(PlatformAuthenticatedSession actor, String fileName, byte[] archive, String publisher, String version,
                                           String displayName, String visibility, String projectCode, String sourceRoot) {
        return invoke(() -> scope.run(actor, request -> {
            try {
                return ResponseEntity.ok(skills.importPackage(request, new PlatformUploadedFile(fileName, archive), publisher, version,
                        displayName, visibility, projectCode, sourceRoot));
            } catch (java.io.IOException unreadable) { throw AgentSkillException.invalidPackage("Skill ZIP is unreadable"); }
        }));
    }

    private ResponseEntity<?> page(List<?> visible, int offset, int limit) {
        return ResponseEntity.ok(Map.of("items", visible.stream().skip(offset).limit(limit).toList(),
                "offset", offset, "limit", limit, "total", visible.size()));
    }

    private ResponseEntity<?> invoke(Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (AgentSkillException failure) {
            // Do not serialize owner exceptions or include private package names in protocol errors.
            return ResponseEntity.status(failure.status()).build();
        }
    }
}
