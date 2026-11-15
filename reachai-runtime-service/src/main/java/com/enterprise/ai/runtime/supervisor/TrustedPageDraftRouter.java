package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Only Control's signed internal response may supply an approved declaration. */
final class TrustedPageDraftRouter {
    private final RuntimeControlCatalogClient control;
    private final ObjectMapper mapper;
    private final TrustedPageActionWorkflowGuard guard = new TrustedPageActionWorkflowGuard();

    TrustedPageDraftRouter(RuntimeControlCatalogClient control, ObjectMapper mapper) {
        this.control = control;
        this.mapper = mapper;
    }

    record Assessment(boolean reviewed, Optional<Map<String, Object>> pin) { }

    Assessment assess(RuntimeResolvedWorkflowTarget target, Map<String, Object> args,
                      String message, WorkflowExecutionIdentity identity) {
        boolean marked;
        try {
            if (target == null || target.version() == null || target.version().getGraphSpecSnapshotJson() == null)
                return new Assessment(true, Optional.empty());
            var graph = mapper.readTree(target.version().getGraphSpecSnapshotJson());
            var env = graph == null ? null : graph.path("env");
            marked = env != null && env.isObject() && env.has("reachaiTrustedPageDraft");
            if (!marked) return new Assessment(false, Optional.empty());
            if (!env.path("reachaiTrustedPageDraft").isBoolean() || !env.path("reachaiTrustedPageDraft").booleanValue())
                return new Assessment(true, Optional.empty());
        } catch (Exception invalidGraph) {
            // A malformed published graph cannot safely be classified as an ordinary legacy workflow.
            return new Assessment(true, Optional.empty());
        }
        if (control == null || identity == null || identity.source() != WorkflowExecutionIdentity.Source.EMBED_SESSION
                || !identity.canResolveUserAcl()) return new Assessment(true, Optional.empty());
        boolean reviewed = true;
        try {
            var candidate = guard.candidate(target).orElse(null);
            if (candidate == null || !candidate.projectCode().equals(identity.projectCode())) return new Assessment(reviewed, Optional.empty());
            var approved = control.resolvePageEffect(new RuntimeControlCatalogClient.TrustedPageEffectRequest(
                    candidate.projectCode(), candidate.pageKey(), candidate.actionKey()));
            if (approved == null) return new Assessment(reviewed, Optional.empty());
            reviewed = true;
            if (!TrustedPageDraftIntent.matches(message, List.of(approved.targetAlias()))) return new Assessment(true, Optional.empty());
            String schema = approved.inputSchema() instanceof String s ? s : mapper.writeValueAsString(approved.inputSchema());
            var declaration = new TrustedPageActionWorkflowGuard.TrustedActionDeclaration(
                    approved.approvalId(), approved.revision(), approved.pageId(), approved.actionId(),
                    approved.projectCode(), approved.pageKey(), approved.actionKey(), approved.effect(),
                    approved.catalogHash(), approved.implementationVersion(), approved.targetAlias(),
                    approved.actionTitle(), approved.actionDescription(), approved.routePattern(), schema, approved.fieldLabels(), approved.active());
            var pin = guard.pin(target, declaration).orElse(null);
            if (pin == null || !guard.argumentsMatch(pin, args)
                    || !TrustedPageDraftIntent.valuesAppearInMessage(message, args, approved.fieldLabels())) return new Assessment(true, Optional.empty());
            return new Assessment(true, Optional.of(mapper.convertValue(pin, new TypeReference<Map<String, Object>>() { })));
        } catch (Exception unavailableOrInvalid) {
            // Marked workflows always fail closed when their review cannot be resolved.
            return new Assessment(reviewed, Optional.empty());
        }
    }
}
