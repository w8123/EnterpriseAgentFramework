package com.enterprise.ai.agent.platform.control.context.runtime;

import com.enterprise.ai.agent.platform.control.context.ContextComposerService;
import com.enterprise.ai.agent.platform.control.context.ContextPackageComposeRequest;
import com.enterprise.ai.agent.platform.control.context.ContextPackageResponse;
import com.enterprise.ai.agent.platform.control.context.ContextQueryRequest;
import com.enterprise.ai.agent.platform.control.context.ContextSearchResult;
import com.enterprise.ai.agent.platform.control.context.MemoryLane;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenClaims;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeContextPackageService {

    private final ContextComposerService composerService;
    private final RuntimeContextPromptFormatter promptFormatter;
    private final RuntimeContextProperties properties;

    public RuntimeContextInjectionResult injectForEmbedAgent(EmbedSessionEntity session,
                                                             EmbedTokenClaims claims,
                                                             Map<String, Object> metadata,
                                                             AgentEntryEntity agent,
                                                             AgentRuntimeProfile profile) {
        RuntimeContextIdentity identity = buildIdentity(session, claims, metadata);
        if (agent != null) {
            identity.setAgentId(agent.getId());
            identity.setAgentKey(agent.getKeySlug());
        }
        if (profile != null) {
            identity.setProjectId(profile.getProjectId());
            if (!StringUtils.hasText(identity.getProjectCode())) {
                identity.setProjectCode(profile.getProjectCode());
            }
            identity.setRuntimePlacement(profile.getRuntimePlacement());
        }
        return inject(identity);
    }

    public RuntimeContextInjectionResult injectForEmbedWorkflow(EmbedSessionEntity session,
                                                                EmbedTokenClaims claims,
                                                                Map<String, Object> metadata,
                                                                AgentEntryEntity agent,
                                                                WorkflowDefinitionEntity workflow,
                                                                AgentRuntimeProfile profile) {
        RuntimeContextIdentity identity = buildIdentity(session, claims, metadata);
        if (agent != null) {
            identity.setAgentId(agent.getId());
            identity.setAgentKey(agent.getKeySlug());
        }
        if (workflow != null) {
            identity.setWorkflowId(workflow.getId());
            identity.setWorkflowKey(workflow.getKeySlug());
        }
        if (profile != null) {
            identity.setProjectId(profile.getProjectId());
            if (!StringUtils.hasText(identity.getProjectCode())) {
                identity.setProjectCode(profile.getProjectCode());
            }
            identity.setRuntimePlacement(profile.getRuntimePlacement());
        }
        return inject(identity);
    }

    RuntimeContextInjectionResult inject(RuntimeContextIdentity identity) {
        if (identity == null) {
            return skipped(null, "missing-identity");
        }
        if (!properties.isEnabled()) {
            return skipped(identity, "disabled");
        }
        String placement = normalizePlacement(identity.getRuntimePlacement());
        if ("EMBEDDED".equals(placement)) {
            return skipped(identity, "embedded-placement");
        }
        if ("HYBRID".equals(placement)) {
            return skipped(identity, "hybrid-placement-deferred");
        }
        if (!StringUtils.hasText(identity.getTenantId())) {
            return skipped(identity, "missing-tenant-id");
        }

        try {
            ContextPackageComposeRequest composeRequest = buildComposeRequest(identity);
            ContextPackageResponse pkg = composerService.compose(composeRequest);
            String prompt = promptFormatter.format(pkg);
            int itemCount = countInjectedItems(pkg);
            if (!StringUtils.hasText(prompt) || itemCount <= 0) {
                return skipped(identity, "empty-package");
            }
            return RuntimeContextInjectionResult.builder()
                    .enabled(true)
                    .identity(identity)
                    .packageResponse(pkg)
                    .promptSection(prompt)
                    .itemCount(itemCount)
                    .truncatedCount(pkg.getTruncatedCount())
                    .build();
        } catch (Exception ex) {
            log.warn("[RuntimeContext] compose failed, skip injection: tenantId={}, sessionId={}, reason={}",
                    identity.getTenantId(), identity.getSessionId(), ex.getMessage());
            return skipped(identity, "context-error:" + ex.getMessage());
        }
    }

    public RuntimeContextInjectionResult injectForCentralFallback(RuntimeContextInjectionResult deferred) {
        if (deferred == null || deferred.getIdentity() == null) {
            return skipped(null, "missing-identity");
        }
        RuntimeContextIdentity identity = copyForCentralFallback(deferred.getIdentity());
        return inject(identity);
    }

    ContextPackageComposeRequest buildComposeRequest(RuntimeContextIdentity identity) {
        int maxItems = Math.max(1, properties.getMaxItems());
        ContextQueryRequest query = new ContextQueryRequest();
        query.setMemoryLane(MemoryLane.RUNTIME_USER.name());
        query.setTenantId(identity.getTenantId().trim());
        query.setProjectCode(identity.getProjectCode());
        query.setProjectId(identity.getProjectId());
        query.setUserId(resolveUserId(identity));
        query.setSessionId(identity.getSessionId());
        query.setAgentId(firstText(identity.getAgentId(), identity.getAgentKey()));
        query.setWorkflowId(firstText(identity.getWorkflowId(), identity.getWorkflowKey()));
        query.setPageInstanceId(identity.getPageInstanceId());
        query.setQuery(identity.getQuery());
        query.setTraceId(identity.getTraceId());
        query.setActorType("USER");
        query.setActorId(resolveUserId(identity));
        query.setRetrievalMode("HYBRID");
        query.setTopK(maxItems);

        ContextPackageComposeRequest composeRequest = new ContextPackageComposeRequest();
        composeRequest.setQuery(query);
        composeRequest.setMaxItems(maxItems);
        composeRequest.setTokenBudget(properties.getTokenBudget());
        return composeRequest;
    }

    RuntimeContextIdentity buildIdentity(EmbedSessionEntity session,
                                         EmbedTokenClaims claims,
                                         Map<String, Object> metadata) {
        RuntimeContextIdentity identity = RuntimeContextIdentity.builder()
                .tenantId(firstText(
                        stringValue(metadata == null ? null : metadata.get("tenantId")),
                        session == null ? null : session.getTenantId()))
                .projectCode(firstText(
                        stringValue(metadata == null ? null : metadata.get("projectCode")),
                        session == null ? null : session.getProjectCode()))
                .appId(firstText(
                        stringValue(metadata == null ? null : metadata.get("appId")),
                        session == null ? null : session.getAppId()))
                .externalUserId(firstText(
                        stringValue(metadata == null ? null : metadata.get("externalUserId")),
                        claims == null ? null : claims.getExternalUserId(),
                        session == null ? null : session.getExternalUserId()))
                .globalUserId(firstText(
                        stringValue(metadata == null ? null : metadata.get("globalUserId")),
                        claims == null ? null : claims.getGlobalUserId(),
                        session == null ? null : session.getGlobalUserId()))
                .sessionId(session == null ? null : session.getSessionId())
                .pageInstanceId(firstText(
                        stringValue(metadata == null ? null : metadata.get("pageInstanceId")),
                        session == null ? null : session.getPageInstanceId()))
                .query(firstText(
                        stringValue(metadata == null ? null : metadata.get("runtimeContextQuery")),
                        stringValue(metadata == null ? null : metadata.get("message"))))
                .origin(firstText(
                        stringValue(metadata == null ? null : metadata.get("origin")),
                        session == null ? null : session.getOrigin()))
                .traceId(stringValue(metadata == null ? null : metadata.get("traceId")))
                .roles(claims == null ? List.of() : claims.getRoles())
                .build();
        identity.setUserId(resolveUserId(identity));
        return identity;
    }

    String resolveUserId(RuntimeContextIdentity identity) {
        if (identity == null) {
            return null;
        }
        return firstText(identity.getGlobalUserId(), identity.getExternalUserId(), identity.getUserId());
    }

    private RuntimeContextInjectionResult skipped(RuntimeContextIdentity identity, String reason) {
        return RuntimeContextInjectionResult.builder()
                .enabled(false)
                .skippedReason(reason)
                .identity(identity)
                .itemCount(0)
                .truncatedCount(0)
                .build();
    }

    private int countInjectedItems(ContextPackageResponse pkg) {
        if (pkg == null) {
            return 0;
        }
        int count = 0;
        count += sizeOf(pkg.getUserMemory());
        count += sizeOf(pkg.getPageContext());
        count += sizeOf(pkg.getWorkflowContext());
        count += sizeOf(pkg.getApiContext());
        count += sizeOf(pkg.getRules());
        return count;
    }

    private int sizeOf(List<ContextSearchResult> hits) {
        return hits == null ? 0 : hits.size();
    }

    private String normalizePlacement(String placement) {
        if (!StringUtils.hasText(placement)) {
            return "CENTRAL";
        }
        return placement.trim().toUpperCase(Locale.ROOT);
    }

    private RuntimeContextIdentity copyForCentralFallback(RuntimeContextIdentity source) {
        return RuntimeContextIdentity.builder()
                .tenantId(source.getTenantId())
                .projectId(source.getProjectId())
                .projectCode(source.getProjectCode())
                .appId(source.getAppId())
                .userId(source.getUserId())
                .externalUserId(source.getExternalUserId())
                .globalUserId(source.getGlobalUserId())
                .sessionId(source.getSessionId())
                .agentId(source.getAgentId())
                .agentKey(source.getAgentKey())
                .workflowId(source.getWorkflowId())
                .workflowKey(source.getWorkflowKey())
                .pageInstanceId(source.getPageInstanceId())
                .query(source.getQuery())
                .traceId(source.getTraceId())
                .origin(source.getOrigin())
                .runtimePlacement("CENTRAL")
                .roles(source.getRoles())
                .build();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
