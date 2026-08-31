package com.enterprise.ai.control.skillmarket;

import com.enterprise.ai.control.agentskill.AgentSkillAccessPolicy;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ImportOrigin;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ImportRequest;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.MarketImportResult;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.MarketSource;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ProbeRequest;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ProbeResult;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.SearchResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skill-market")
@RequiredArgsConstructor
public class SkillMarketController {

    static final String READ_PERMISSION = "skill:read";
    static final String IMPORT_PERMISSION = "skill:import";

    private final SkillMarketService marketService;
    private final AgentSkillAccessPolicy accessPolicy;
    private final PlatformAuthAuditService auditService;

    @GetMapping("/search")
    public SearchResult search(HttpServletRequest request,
                               @RequestParam(required = false) String provider,
                               @RequestParam(required = false) String query,
                               @RequestParam(required = false) String view,
                               @RequestParam(required = false) String owner,
                               @RequestParam(defaultValue = "24") int limit) {
        require(request, READ_PERMISSION);
        return marketService.search(provider, query, view, owner, limit);
    }

    @GetMapping("/sources")
    public List<MarketSource> sources(HttpServletRequest request) {
        require(request, READ_PERMISSION);
        return marketService.sources();
    }

    @GetMapping("/imports")
    public List<ImportOrigin> imports(HttpServletRequest request,
                                      @RequestParam(defaultValue = "50") int limit) {
        PlatformAuthenticatedSession session = require(request, READ_PERMISSION);
        return marketService.imports(session, limit);
    }

    @PostMapping("/probes")
    public ProbeResult probe(HttpServletRequest request, @RequestBody ProbeRequest body) {
        require(request, IMPORT_PERMISSION);
        return marketService.probe(body);
    }

    @PostMapping("/imports")
    public MarketImportResult importSkill(HttpServletRequest request,
                                          @RequestBody ImportRequest body) {
        PlatformAuthenticatedSession session = require(request, IMPORT_PERMISSION);
        accessPolicy.requireImportScope(session, IMPORT_PERMISSION,
                body == null ? null : body.visibility(),
                body == null ? null : body.projectCode());
        MarketImportResult result = marketService.importFromMarket(body, session, actor(session));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("skillId", result.imported().skill().id());
        details.put("skillVersionId", result.imported().version().id());
        details.put("publisher", result.imported().skill().publisher());
        details.put("name", result.imported().skill().name());
        details.put("version", result.imported().version().version());
        details.put("sourceCommitSha", result.origin().sourceCommitSha());
        details.put("sourceRoot", result.origin().sourceRoot());
        details.put("providerKey", result.origin().providerKey());
        if (result.origin().marketplaceSkillId() != null) {
            details.put("marketplaceSkillId", result.origin().marketplaceSkillId());
        }
        details.put("sourceSha256", result.origin().selectedSourceSha256());
        details.put("created", result.imported().created());
        auditService.record(session, "AGENT_SKILL_MARKET_IMPORTED", "AGENT_SKILL_VERSION",
                result.imported().version().id().toString(), details);
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
        if (session == null || session.user() == null
                || !StringUtils.hasText(session.user().getUsername())) {
            return "SYSTEM";
        }
        return session.user().getUsername();
    }
}
