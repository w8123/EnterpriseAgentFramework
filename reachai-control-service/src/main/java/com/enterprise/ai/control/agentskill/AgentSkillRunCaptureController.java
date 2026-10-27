package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportResult;
import com.enterprise.ai.control.agentskill.AgentSkillRunCaptureService.CreateRequest;
import com.enterprise.ai.control.agentskill.AgentSkillRunCaptureService.Draft;
import com.enterprise.ai.control.agentskill.AgentSkillRunCaptureService.PreviewRequest;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 「沉淀成 Skill」: a draft from employee MCP calls or a RunOps trace, then the edited SKILL.md
 * enters the normal Skill review flow. Reading the source records needs the permission that
 * already guards them (MCP Hub read / RunOps read) on top of Skill import for that system.
 */
@RestController
@RequestMapping("/api/skills/captures")
public class AgentSkillRunCaptureController {

    static final String MCP_READ_PERMISSION = "mcp-hub:read";
    static final String RUNOPS_READ_PERMISSION = "runops:read";

    private final AgentSkillRunCaptureService captureService;
    private final AgentSkillAccessPolicy accessPolicy;
    private final PlatformAuthAuditService auditService;

    public AgentSkillRunCaptureController(AgentSkillRunCaptureService captureService,
                                          AgentSkillAccessPolicy accessPolicy,
                                          PlatformAuthAuditService auditService) {
        this.captureService = captureService;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
    }

    @PostMapping("/preview")
    public Draft preview(HttpServletRequest request, @RequestBody PreviewRequest body) {
        PlatformAuthenticatedSession session = require(request);
        String source = body == null || body.source() == null ? "" : body.source().trim().toUpperCase(Locale.ROOT);
        accessPolicy.requirePermission(session, AgentSkillRunCaptureService.SOURCE_RUN.equals(source)
                ? RUNOPS_READ_PERMISSION : MCP_READ_PERMISSION);
        Draft draft = captureService.preview(body);
        // Nothing is written by a preview; the draft is only returned to someone who may import for that system.
        accessPolicy.requireImportScope(session, AgentSkillController.IMPORT_PERMISSION, "PROJECT", draft.projectCode());
        return draft;
    }

    @PostMapping
    public ImportResult create(HttpServletRequest request, @RequestBody CreateRequest body) {
        PlatformAuthenticatedSession session = require(request);
        String visibility = body == null || !StringUtils.hasText(body.visibility()) ? "PROJECT" : body.visibility();
        accessPolicy.requireImportScope(session, AgentSkillController.IMPORT_PERMISSION, visibility,
                body == null ? null : body.projectCode());
        ImportResult result = captureService.create(body, actor(session),
                session.user() == null ? null : session.user().getId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("skillId", result.skill().id());
        details.put("name", result.skill().name());
        details.put("publisher", result.skill().publisher());
        details.put("version", result.version().version());
        details.put("projectCode", body.projectCode());
        details.put("sourceRef", result.version().sourceRef());
        details.put("created", result.created());
        auditService.record(session, "AGENT_SKILL_CAPTURED", "AGENT_SKILL_VERSION",
                result.version().id().toString(), details);
        return result;
    }

    private PlatformAuthenticatedSession require(HttpServletRequest request) {
        Object candidate = request == null ? null
                : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "live ReachAI platform login is required");
        }
        accessPolicy.requirePermission(session, AgentSkillController.IMPORT_PERMISSION);
        return session;
    }

    private static String actor(PlatformAuthenticatedSession session) {
        return session.user() == null || !StringUtils.hasText(session.user().getUsername())
                ? "SYSTEM" : session.user().getUsername();
    }
}
