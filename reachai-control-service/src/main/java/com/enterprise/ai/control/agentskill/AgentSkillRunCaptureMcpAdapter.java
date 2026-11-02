package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCapturePort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/** Delegates run capture to the run center handlers so permissions, project scope and audit stay identical. */
@Component
@RequiredArgsConstructor
final class AgentSkillRunCaptureMcpAdapter implements McpPlatformSkillCapturePort {
    private final AgentSkillRunCaptureController captures;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> preview(PlatformAuthenticatedSession actor, String source, List<Long> callLogIds, String traceId, String goal) {
        return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(captures.preview(request,
                new AgentSkillRunCaptureService.PreviewRequest(source, callLogIds, traceId, goal)))));
    }

    @Override
    public ResponseEntity<?> create(PlatformAuthenticatedSession actor, String projectCode, String displayName, String skillMd,
                                    String visibility, String version, String sourceRef) {
        try {
            return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(captures.create(request,
                    new AgentSkillRunCaptureService.CreateRequest(projectCode, displayName, skillMd, visibility, version, sourceRef)))));
        } catch (org.springframework.web.server.ResponseStatusException refused) {
            throw refused;
        } catch (RuntimeException lost) {
            // The version may or may not exist now; saving again could create a duplicate, so the caller looks first.
            return ResponseEntity.accepted().body(java.util.Map.of("status", "UNKNOWN",
                    "message", "Skill 是否已保存未确认，请先用 reachai_skills_list 查找；确认没有后再保存。"));
        }
    }

    private ResponseEntity<?> invoke(java.util.function.Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (AgentSkillException failure) {
            // Owner messages can quote run arguments; only the status crosses the boundary.
            return ResponseEntity.status(failure.status()).build();
        }
    }
}
