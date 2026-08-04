package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.supervisor.SupervisorApprovalInteractionService.ApprovalRequest;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter.PolicyApprovalGrant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SupervisorToolPolicyService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final Pattern EXPLICIT_PAGE_INTENT = Pattern.compile(
            "(?i)(打开|跳转|进入|切换到|导航到|在.{0,20}(页面|界面)|操作.{0,20}(页面|界面)|页面上|界面上|"
                    + "(读取|查看|查询|获取).{0,20}(当前)?(页面|界面).{0,20}(状态|筛选|内容|数据|表格|行)|"
                    + "open\\s+(the\\s+)?page|navigate\\s+to|go\\s+to|on\\s+the\\s+page|"
                    + "(read|inspect|query|get).{0,30}(current\\s+)?(page|screen).{0,30}(state|filter|content|data|table|row))");

    private final SupervisorExecutionTraceService traceService;
    private final SupervisorApprovalInteractionService approvalService;
    private final ObjectMapper objectMapper;

    public PolicyDecision evaluate(SupervisorExecutionTraceService.TraceHandle trace,
                                   RuntimeAgentView agent,
                                   RuntimeAgentConfigVersionEntity config,
                                   RuntimeAgentWorkflowToolEntity tool,
                                   Map<String, Object> input,
                                   Map<String, Object> args,
                                   PolicyApprovalGrant approvalGrant) {
        String riskLevel = normalizeRisk(tool);
        String permissionKey = text(tool.getPermissionKey());
        String profile = firstText(config.getPolicyProfile(), "STANDARD").toUpperCase(Locale.ROOT);
        ParsedPolicy parsedPolicy = policy(config.getConfigJson());
        Map<String, Object> policy = parsedPolicy.values();
        Map<String, Object> metadata = metadata(profile, riskLevel, permissionKey, tool, input, args);

        PolicyDecision structural = structuralChecks(agent, config, tool, input, profile, parsedPolicy, metadata);
        if (structural != null) {
            trace(structural, trace, agent, input, tool, metadata);
            return structural;
        }

        if ("PAGE_ACTION".equals(riskLevel) && !explicitPageIntent(input)) {
            PolicyDecision denied = deny("PAGE_ACTION requires an explicit user request to open, navigate, or operate a page");
            trace(denied, trace, agent, input, tool, metadata);
            return denied;
        }

        if ("IRREVERSIBLE".equals(riskLevel)
                && !"CONFIRM".equalsIgnoreCase(text(policy.get("irreversibleMode")))) {
            PolicyDecision denied = deny("IRREVERSIBLE Workflow tools are denied by default");
            trace(denied, trace, agent, input, tool, metadata);
            return denied;
        }

        boolean confirmationRequired = "WRITE".equals(riskLevel)
                || "IRREVERSIBLE".equals(riskLevel)
                || Boolean.TRUE.equals(policy.get("confirmPageActions")) && "PAGE_ACTION".equals(riskLevel);
        if (confirmationRequired && !approved(permissionKey, tool.getToolName(), args, approvalGrant)) {
            String reason = "执行该 " + riskLevel + " 操作前需要用户确认："
                    + firstText(tool.getToolName(), "Workflow Tool");
            ApprovalRequest approval = approvalService.create(trace, agent, config, tool, input, args, reason);
            PolicyDecision required = new PolicyDecision(
                    false, true, "REQUIRE_CONFIRMATION", reason, approval.interactionId(), approval.uiRequest());
            trace(required, trace, agent, input, tool, metadata);
            return required;
        }

        String reason = approved(permissionKey, tool.getToolName(), args, approvalGrant)
                ? "One-time user approval matched the configured permission key"
                : "Configured Workflow tool passed project, tenant, role, permission, and risk policy checks";
        PolicyDecision allowed = new PolicyDecision(true, false, "ALLOW", reason, null, null);
        trace(allowed, trace, agent, input, tool, metadata);
        return allowed;
    }

    private PolicyDecision structuralChecks(RuntimeAgentView agent,
                                            RuntimeAgentConfigVersionEntity config,
                                            RuntimeAgentWorkflowToolEntity tool,
                                            Map<String, Object> input,
                                            String profile,
                                            ParsedPolicy parsedPolicy,
                                            Map<String, Object> metadata) {
        if (!parsedPolicy.valid()) {
            return deny("Supervisor policy configuration is invalid");
        }
        Map<String, Object> policy = parsedPolicy.values();
        if (!"ALLOW_LIST".equalsIgnoreCase(config.getToolCatalogMode())) {
            return deny("Supervisor toolCatalogMode must be ALLOW_LIST");
        }
        if (!Boolean.TRUE.equals(tool.getEnabled())) {
            return deny("Workflow tool is disabled in the active Agent configuration");
        }
        String requestProject = contextText(input, "projectCode");
        if (StringUtils.hasText(agent.projectCode()) && StringUtils.hasText(requestProject)
                && !agent.projectCode().equalsIgnoreCase(requestProject)) {
            return deny("Workflow tool project does not match the Agent project");
        }
        if (!StringUtils.hasText(tool.getPermissionKey()) && !"DEV_ALLOW_ALL".equals(profile)) {
            return deny("Workflow tool permissionKey is required by the active policy profile");
        }

        List<String> roles = stringList(input.get("roles"));
        List<String> agentRoles = jsonStringList(agent.allowedRolesJson());
        if (!agentRoles.isEmpty() && disjoint(roles, agentRoles)) {
            return deny("Caller roles do not satisfy the Agent allowed role set");
        }

        if (!"DEV_ALLOW_ALL".equals(profile)) {
            String tenantId = contextText(input, "tenantId");
            if (!StringUtils.hasText(tenantId)) {
                return deny("tenantId is required by the active policy profile");
            }
            List<String> allowedTenantIds = stringList(policy.get("allowedTenantIds"));
            if (!allowedTenantIds.isEmpty() && !containsIgnoreCase(allowedTenantIds, tenantId)) {
                return deny("tenantId is outside the Agent policy allowlist");
            }
            Map<String, Object> permissionRoles = mapValue(policy.get("permissionRoles"));
            List<String> requiredRoles = stringList(permissionRoles.get(tool.getPermissionKey()));
            if (!requiredRoles.isEmpty() && disjoint(roles, requiredRoles)) {
                return deny("Caller roles do not satisfy permission " + tool.getPermissionKey());
            }
        } else {
            metadata.put("developmentBypass", "tenant and permission-role checks only");
        }
        return null;
    }

    private void trace(PolicyDecision decision,
                       SupervisorExecutionTraceService.TraceHandle trace,
                       RuntimeAgentView agent,
                       Map<String, Object> input,
                       RuntimeAgentWorkflowToolEntity tool,
                       Map<String, Object> metadata) {
        if (decision.interactionId() != null) metadata.put("interactionId", decision.interactionId());
        traceService.guard(trace, agent, input, tool.getToolName(), decision.decision(), decision.reason(), metadata);
    }

    private Map<String, Object> metadata(String profile,
                                         String riskLevel,
                                         String permissionKey,
                                         RuntimeAgentWorkflowToolEntity tool,
                                         Map<String, Object> input,
                                         Map<String, Object> args) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("policyProfile", profile);
        metadata.put("permissionKey", permissionKey);
        metadata.put("riskLevel", riskLevel);
        metadata.put("readOnly", tool.getReadOnly());
        metadata.put("projectCode", contextText(input, "projectCode"));
        metadata.put("tenantId", contextText(input, "tenantId"));
        metadata.put("roles", stringList(input.get("roles")));
        metadata.put("args", args == null ? Map.of() : args);
        return metadata;
    }

    private ParsedPolicy policy(String configJson) {
        if (!StringUtils.hasText(configJson)) return new ParsedPolicy(Map.of(), true);
        try {
            Map<String, Object> root = objectMapper.readValue(configJson, MAP_TYPE);
            Map<String, Object> nested = mapValue(root.get("policy"));
            return new ParsedPolicy(nested.isEmpty() ? root : nested, true);
        } catch (Exception ex) {
            return new ParsedPolicy(Map.of(), false);
        }
    }

    private List<String> jsonStringList(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE).stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?>)) return Map.of();
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private List<String> stringList(Object value) {
        if (value == null) return List.of();
        Collection<?> values = value instanceof Collection<?> collection ? collection : List.of(value);
        List<String> result = new ArrayList<>();
        for (Object item : values) if (item != null && StringUtils.hasText(String.valueOf(item))) {
            result.add(String.valueOf(item).trim());
        }
        return List.copyOf(result);
    }

    private String contextText(Map<String, Object> input, String key) {
        String direct = text(input == null ? null : input.get(key));
        if (StringUtils.hasText(direct)) return direct;
        Map<String, Object> metadata = mapValue(input == null ? null : input.get("metadata"));
        return text(metadata.get(key));
    }

    private boolean explicitPageIntent(Map<String, Object> input) {
        String message = firstText(
                text(input == null ? null : input.get("message")),
                text(input == null ? null : input.get("input")), "");
        return EXPLICIT_PAGE_INTENT.matcher(message).find();
    }

    private boolean approved(String permissionKey,
                             String toolName,
                             Map<String, Object> args,
                             PolicyApprovalGrant grant) {
        return grant != null && StringUtils.hasText(permissionKey)
                && permissionKey.equals(grant.permissionKey())
                && firstText(toolName, "").equals(firstText(grant.toolName(), ""))
                && (args == null ? Map.of() : args).equals(
                        grant.approvedArgs() == null ? Map.of() : grant.approvedArgs())
                && StringUtils.hasText(grant.interactionId());
    }

    private boolean disjoint(List<String> actual, List<String> required) {
        if (actual.isEmpty()) return true;
        Set<String> normalized = actual.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        return required.stream().filter(StringUtils::hasText)
                .map(value -> value.toLowerCase(Locale.ROOT)).noneMatch(normalized::contains);
    }

    private boolean containsIgnoreCase(List<String> values, String expected) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(expected));
    }

    private String normalizeRisk(RuntimeAgentWorkflowToolEntity tool) {
        String risk = text(tool.getRiskLevel());
        if (StringUtils.hasText(risk)) return risk.toUpperCase(Locale.ROOT);
        return Boolean.TRUE.equals(tool.getReadOnly()) ? "READ" : "WRITE";
    }

    private PolicyDecision deny(String reason) {
        return new PolicyDecision(false, false, "DENY", reason, null, null);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    public record PolicyDecision(boolean allowed,
                                 boolean confirmationRequired,
                                 String decision,
                                 String reason,
                                 String interactionId,
                                 Object uiRequest) {
    }

    private record ParsedPolicy(Map<String, Object> values, boolean valid) {
    }
}
