package com.enterprise.ai.control.skillmarket;

import com.enterprise.ai.control.agentskill.AgentSkillException;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillMarketPort;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ImportRequest;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ProbeRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Imports land as REVIEW_PENDING versions pinned to the probed commit and package digest, so the AI imports directly;
 * review and publication stay behind the owner's approval.
 */
@Component
@RequiredArgsConstructor
final class SkillMarketMcpPlatformAdapter implements McpPlatformSkillMarketPort {
    private final SkillMarketController market;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> search(PlatformAuthenticatedSession actor, String provider, String query, String view, String owner, int limit) {
        return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(market.search(request, provider, query, view, owner, limit))));
    }

    @Override
    public ResponseEntity<?> sources(PlatformAuthenticatedSession actor) {
        return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(market.sources(request))));
    }

    @Override
    public ResponseEntity<?> imports(PlatformAuthenticatedSession actor, int limit) {
        return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(market.imports(request, limit))));
    }

    @Override
    public ResponseEntity<?> probe(PlatformAuthenticatedSession actor, Map<String, String> body) {
        var probe = new ProbeRequest(body.get("sourceUrl"), body.get("marketplaceProvider"), body.get("marketplaceSkillId"), body.get("expectedSkillName"));
        return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(market.probe(request, probe))));
    }

    @Override
    public ResponseEntity<?> importSkill(PlatformAuthenticatedSession actor, Map<String, String> body) {
        var command = new ImportRequest(body.get("sourceUrl"), body.get("commitSha"), body.get("sourceRoot"),
                body.get("expectedSelectedSourceSha256"), body.get("marketplaceProvider"), body.get("marketplaceSkillId"),
                body.get("publisher"), body.get("version"), body.get("displayName"), body.get("visibility"), body.get("projectCode"));
        try {
            return invoke(() -> scope.run(actor, request -> ResponseEntity.ok(market.importSkill(request, command))));
        } catch (RuntimeException lost) {
            if (lost instanceof org.springframework.web.server.ResponseStatusException denied && denied.getStatusCode().is4xxClientError()) throw denied;
            return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN",
                    "message", "导入结果未确认，请用 reachai_skill_market_imports 查看导入记录；不要直接再次导入。"));
        }
    }

    private ResponseEntity<?> invoke(Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (AgentSkillException failure) {
            // Only the stable code crosses the protocol boundary; upstream messages can carry private repository details.
            return ResponseEntity.status(failure.status()).body(Map.of("code", failure.code()));
        }
    }
}
