package com.enterprise.ai.runtime.supervisor;


import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionWriter;
import com.enterprise.ai.runtime.supervisor.SupervisorApprovalInteractionService.ApprovalRequest;
import com.enterprise.ai.runtime.trace.WorkflowTraceSanitizer;
import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort.PolicyApprovalGrant;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter.RemoteAgentBinding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Slf4j
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
    private static final Pattern WRITE_INTENT = Pattern.compile(
            "(?i)\\b(?:create|add|modify|update|edit|delete|remove|cancel|submit|save|pay|ship|"
                    + "enable|disable|activate|deactivate|archive|unarchive|restore)\\b");

    private final RuntimeGuardDecisionWriter guardDecisions;
    private final SupervisorApprovalInteractionService approvalService;
    private final ObjectMapper objectMapper;

    boolean requiresWorkflowConfirmation(RuntimeAgentConfigSnapshot config, RuntimeAgentWorkflowToolSnapshot tool) {
        return requiresWorkflowConfirmation(normalizeRisk(tool), policy(config.getConfigJson()).values());
    }

    private boolean requiresWorkflowConfirmation(String riskLevel, Map<String, Object> policy) {
        return "WRITE".equals(riskLevel) || "IRREVERSIBLE".equals(riskLevel)
                || Boolean.TRUE.equals(policy.get("confirmPageActions")) && "PAGE_ACTION".equals(riskLevel);
    }

    public PolicyDecision evaluate(SupervisorExecutionTraceService.TraceHandle trace,
                                   RuntimeAgentView agent,
                                   RuntimeAgentConfigSnapshot config,
                                   RuntimeAgentWorkflowToolSnapshot tool,
                                   Map<String, Object> input,
                                   Map<String, Object> args,
                                   PolicyApprovalGrant approvalGrant) {
        return evaluate(trace, agent, config, tool, input, args, approvalGrant, null);
    }

    public PolicyDecision evaluate(SupervisorExecutionTraceService.TraceHandle trace,
                                   RuntimeAgentView agent,
                                   RuntimeAgentConfigSnapshot config,
                                   RuntimeAgentWorkflowToolSnapshot tool,
                                   Map<String, Object> input,
                                   Map<String, Object> args,
                                   PolicyApprovalGrant approvalGrant,
                                   WorkflowExecutionIdentity trustedIdentity) {
        return evaluate(trace, agent, config, tool, input, args, approvalGrant,
                trustedIdentity, RuntimeEvalExecutionContext.none());
    }

    public PolicyDecision evaluate(SupervisorExecutionTraceService.TraceHandle trace,
                                   RuntimeAgentView agent,
                                   RuntimeAgentConfigSnapshot config,
                                   RuntimeAgentWorkflowToolSnapshot tool,
                                   Map<String, Object> input,
                                   Map<String, Object> args,
                                   PolicyApprovalGrant approvalGrant,
                                   WorkflowExecutionIdentity trustedIdentity,
                                   RuntimeEvalExecutionContext evalContext) {
        String riskLevel = normalizeRisk(tool);
        String permissionKey = text(tool.getPermissionKey());
        String profile = firstText(config.getPolicyProfile(), "STANDARD").toUpperCase(Locale.ROOT);
        ParsedPolicy parsedPolicy = policy(config.getConfigJson());
        Map<String, Object> policy = parsedPolicy.values();
        Map<String, Object> metadata = metadata(profile, riskLevel, permissionKey, tool, input, args);

        PolicyDecision structural = structuralChecks(agent, config, tool, input, profile, parsedPolicy, metadata);
        if (structural != null) {
            trace(structural, trace, agent, input, tool, metadata, trustedIdentity);
            return structural;
        }

        RuntimeEvalExecutionContext evaluation = evalContext == null
                ? RuntimeEvalExecutionContext.none() : evalContext;
        if (evaluation.isEvaluation()
                && !"READ".equals(riskLevel)
                && !"READ_ONLY".equals(riskLevel)) {
            metadata.put("evalMode", evaluation.mode().name());
            PolicyDecision denied = evalSideEffectDenied(
                    "Eval execution permits only READ Workflow tools; blocked " + riskLevel);
            trace(denied, trace, agent, input, tool, metadata, trustedIdentity);
            return denied;
        }

        if ("PAGE_ACTION".equals(riskLevel) && !explicitPageIntent(input)) {
            PolicyDecision denied = deny("PAGE_ACTION requires an explicit user request to open, navigate, or operate a page");
            trace(denied, trace, agent, input, tool, metadata, trustedIdentity);
            return denied;
        }

        if ("IRREVERSIBLE".equals(riskLevel)
                && !"CONFIRM".equalsIgnoreCase(text(policy.get("irreversibleMode")))) {
            PolicyDecision denied = deny("IRREVERSIBLE Workflow tools are denied by default");
            trace(denied, trace, agent, input, tool, metadata, trustedIdentity);
            return denied;
        }

        boolean confirmationRequired = requiresWorkflowConfirmation(riskLevel, policy);
        if (confirmationRequired && !approved(permissionKey, tool.getToolName(), args, approvalGrant)) {
            String reason = "执行该 " + riskLevel + " 操作前需要用户确认："
                    + firstText(tool.getToolName(), "Workflow Tool");
            ApprovalRequest approval = approvalService.create(
                    trace, agent, config, tool, input, args, reason, trustedIdentity);
            PolicyDecision required = new PolicyDecision(
                    false, true, "REQUIRE_CONFIRMATION", reason, approval.interactionId(), approval.uiRequest());
            trace(required, trace, agent, input, tool, metadata, trustedIdentity);
            return required;
        }

        String reason = approved(permissionKey, tool.getToolName(), args, approvalGrant)
                ? "One-time user approval matched the configured permission key"
                : "Configured Workflow tool passed project, tenant, role, permission, and risk policy checks";
        PolicyDecision allowed = new PolicyDecision(true, false, "ALLOW", reason, null, null);
        trace(allowed, trace, agent, input, tool, metadata, trustedIdentity);
        return allowed;
    }

    /** Applies the same Agent-level Guard contract without pretending an A2A binding is a Workflow. */
    public PolicyDecision evaluateA2a(
            SupervisorExecutionTraceService.TraceHandle trace,
            RuntimeAgentView agent,
            RuntimeAgentConfigSnapshot config,
            RemoteAgentBinding binding,
            Map<String, Object> input,
            Map<String, Object> args,
            PolicyApprovalGrant approvalGrant,
            WorkflowExecutionIdentity trustedIdentity) {
        return evaluateA2a(trace, agent, config, binding, input, args, approvalGrant,
                trustedIdentity, RuntimeEvalExecutionContext.none());
    }

    public PolicyDecision evaluateA2a(
            SupervisorExecutionTraceService.TraceHandle trace,
            RuntimeAgentView agent,
            RuntimeAgentConfigSnapshot config,
            RemoteAgentBinding binding,
            Map<String, Object> input,
            Map<String, Object> args,
            PolicyApprovalGrant approvalGrant,
            WorkflowExecutionIdentity trustedIdentity,
            RuntimeEvalExecutionContext evalContext) {
        String toolName = binding == null ? null : text(binding.getToolName());
        String permissionKey = binding == null ? null : text(binding.getPermissionKey());
        String riskLevel = binding == null ? null : text(binding.getRiskLevel());
        riskLevel = StringUtils.hasText(riskLevel) ? riskLevel.toUpperCase(Locale.ROOT) : "READ";
        String profile = firstText(config.getPolicyProfile(), "STANDARD").toUpperCase(Locale.ROOT);
        ParsedPolicy parsedPolicy = policy(config.getConfigJson());
        Map<String, Object> policy = parsedPolicy.values();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolKind", "A2A_REMOTE_AGENT");
        metadata.put("policyProfile", profile);
        metadata.put("permissionKey", permissionKey);
        metadata.put("riskLevel", riskLevel);
        metadata.put("projectCode", contextText(input, "projectCode"));
        metadata.put("tenantId", contextText(input, "tenantId"));
        metadata.put("roles", stringList(input == null ? null : input.get("roles")));
        metadata.put("remoteAgentKey", binding == null ? null : binding.getRemoteAgentKeySnapshot());
        metadata.put("protocolSkillId", text(args == null ? null : args.get("protocolSkillId")));
        Object outboundText = args == null ? null : args.get("text");
        metadata.put("textLength", outboundText == null ? 0 : String.valueOf(outboundText).length());

        RuntimeEvalExecutionContext evaluation = evalContext == null
                ? RuntimeEvalExecutionContext.none() : evalContext;
        if (evaluation.isEvaluation()) {
            metadata.put("evalMode", evaluation.mode().name());
            PolicyDecision denied = evalSideEffectDenied(
                    "Eval execution blocks A2A delegation until a dedicated sandbox adapter is configured");
            recordDecision(trace, agent, input, toolName,
                    denied.decision(), denied.reason(), metadata, "A2A_REMOTE_AGENT", trustedIdentity);
            return denied;
        }

        String deniedReason = null;
        if (binding == null || !parsedPolicy.valid()) {
            deniedReason = "Supervisor A2A policy configuration is invalid";
        } else if (!"ALLOW_LIST".equalsIgnoreCase(config.getToolCatalogMode())) {
            deniedReason = "Supervisor toolCatalogMode must be ALLOW_LIST";
        } else if (!Boolean.TRUE.equals(binding.getEnabled())) {
            deniedReason = "A2A remote Agent tool is disabled in the active Agent configuration";
        } else if (!Set.of("READ", "WRITE", "IRREVERSIBLE").contains(riskLevel)) {
            deniedReason = "A2A remote Agent risk level is invalid";
        } else {
            String requestProject = contextText(input, "projectCode");
            if (StringUtils.hasText(agent.projectCode()) && StringUtils.hasText(requestProject)
                    && !agent.projectCode().equalsIgnoreCase(requestProject)) {
                deniedReason = "A2A remote Agent project does not match the Agent project";
            } else if (!StringUtils.hasText(permissionKey) && !"DEV_ALLOW_ALL".equals(profile)) {
                deniedReason = "A2A remote Agent permissionKey is required by the active policy profile";
            } else {
                List<String> roles = stringList(input == null ? null : input.get("roles"));
                List<String> agentRoles = jsonStringList(agent.allowedRolesJson());
                if (!agentRoles.isEmpty() && disjoint(roles, agentRoles)) {
                    deniedReason = "Caller roles do not satisfy the Agent allowed role set";
                } else if (!"DEV_ALLOW_ALL".equals(profile)) {
                    String tenantId = contextText(input, "tenantId");
                    if (!StringUtils.hasText(tenantId)) {
                        deniedReason = "tenantId is required by the active policy profile";
                    } else {
                        List<String> allowedTenantIds = stringList(policy.get("allowedTenantIds"));
                        if (!allowedTenantIds.isEmpty()
                                && !containsIgnoreCase(allowedTenantIds, tenantId)) {
                            deniedReason = "tenantId is outside the Agent policy allowlist";
                        } else {
                            List<String> requiredRoles = stringList(
                                    mapValue(policy.get("permissionRoles")).get(permissionKey));
                            if (!requiredRoles.isEmpty() && disjoint(roles, requiredRoles)) {
                                deniedReason = "Caller roles do not satisfy permission " + permissionKey;
                            }
                        }
                    }
                } else {
                    metadata.put("developmentBypass", "tenant and permission-role checks only");
                }
            }
        }
        if (deniedReason != null) {
            PolicyDecision denied = deny(deniedReason);
            recordDecision(trace, agent, input, toolName, denied.decision(), denied.reason(), metadata, "A2A_REMOTE_AGENT", trustedIdentity);
            return denied;
        }
        if ("IRREVERSIBLE".equals(riskLevel)
                && !"CONFIRM".equalsIgnoreCase(text(policy.get("irreversibleMode")))) {
            PolicyDecision denied = deny("IRREVERSIBLE A2A delegation is denied by default");
            recordDecision(trace, agent, input, toolName, denied.decision(), denied.reason(), metadata, "A2A_REMOTE_AGENT", trustedIdentity);
            return denied;
        }
        boolean confirmationRequired = "WRITE".equals(riskLevel) || "IRREVERSIBLE".equals(riskLevel);
        if (confirmationRequired && !approved(permissionKey, toolName, args, approvalGrant)) {
            String reason = "执行该 " + riskLevel + " 跨 Agent 委派前需要用户确认：" + toolName;
            ApprovalRequest approval = approvalService.create(
                    trace, agent, config, "A2A_REMOTE_AGENT", toolName, permissionKey, riskLevel,
                    input, args, reason, trustedIdentity);
            PolicyDecision required = new PolicyDecision(
                    false, true, "REQUIRE_CONFIRMATION", reason,
                    approval.interactionId(), approval.uiRequest());
            metadata.put("interactionId", approval.interactionId());
            recordDecision(trace, agent, input, toolName,
                    required.decision(), required.reason(), metadata, "A2A_REMOTE_AGENT", trustedIdentity);
            return required;
        }
        String reason = approved(permissionKey, toolName, args, approvalGrant)
                ? "One-time user approval matched the A2A permission key"
                : "Configured A2A remote Agent passed project, tenant, role, permission, and risk checks";
        PolicyDecision allowed = new PolicyDecision(true, false, "ALLOW", reason, null, null);
        recordDecision(trace, agent, input, toolName,
                allowed.decision(), allowed.reason(), metadata, "A2A_REMOTE_AGENT", trustedIdentity);
        return allowed;
    }

    private PolicyDecision structuralChecks(RuntimeAgentView agent,
                                            RuntimeAgentConfigSnapshot config,
                                            RuntimeAgentWorkflowToolSnapshot tool,
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
                       RuntimeAgentWorkflowToolSnapshot tool,
                       Map<String, Object> metadata,
                       WorkflowExecutionIdentity trustedIdentity) {
        if (decision.interactionId() != null) metadata.put("interactionId", decision.interactionId());
        recordDecision(trace, agent, input, tool.getToolName(), decision.decision(), decision.reason(), metadata,
                "WORKFLOW_TOOL", trustedIdentity);
    }

    private void recordDecision(SupervisorExecutionTraceService.TraceHandle trace, RuntimeAgentView agent,
                                Map<String, Object> input, String targetName, String decision, String reason,
                                Map<String, Object> metadata, String targetKind, WorkflowExecutionIdentity identity) {
        try {
            String tenantId = identity != null && identity.projectTrusted() ? identity.tenantId() : null;
            Map<String, Object> safeMetadata = new LinkedHashMap<>(WorkflowTraceSanitizer.sanitizeGuardMetadata(metadata));
            if (StringUtils.hasText(agent.projectCode())) safeMetadata.put("projectCode", agent.projectCode());
            else safeMetadata.remove("projectCode");
            if (StringUtils.hasText(tenantId)) safeMetadata.put("tenantId", tenantId);
            else safeMetadata.remove("tenantId");
            guardDecisions.append(new RuntimeGuardDecisionWriter.Decision(
                    trace.traceId(), agent.projectId(), agent.projectCode(),
                    firstText(text(input == null ? null : input.get("environment")), "DEV"), tenantId,
                    "SUPERVISOR_TOOL_POLICY", targetKind, targetName, decision,
                    WorkflowTraceSanitizer.sanitizeAnswer(reason), objectMapper.writeValueAsString(safeMetadata), LocalDateTime.now()));
        } catch (Exception failure) {
            log.warn("Cannot persist Supervisor policy decision: {}", failure.getClass().getSimpleName());
        }
    }

    private Map<String, Object> metadata(String profile,
                                         String riskLevel,
                                         String permissionKey,
                                         RuntimeAgentWorkflowToolSnapshot tool,
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
        if (EXPLICIT_PAGE_INTENT.matcher(message).find()) {
            return true;
        }
        // An embedded page already supplies the page identity. In that context a natural-language
        // business request against the current page is explicit page intent even when the user
        // does not know the internal action name or repeat words such as "operate this page".
        // Write safety remains enforced by the published PAGE_ACTION confirmation contract.
        if (!StringUtils.hasText(contextText(input, "pageKey"))) {
            return false;
        }
        return containsAny(message, "查", "查询", "统计", "多少", "哪些", "列表", "筛选", "读取", "总数", "查看", "获取")
                || hasUnnegatedWriteIntent(message);
    }

    private boolean hasUnnegatedWriteIntent(String message) {
        String remaining = firstText(message, "");
        for (String negated : List.of(
                "不要修改", "不修改", "不要更新", "不更新", "不要删除", "不删除",
                "不要新增", "不新增", "不要创建", "不创建", "不要提交", "不提交",
                "不要保存", "不保存", "不要付款", "不付款", "不要发货", "不发货",
                "不要启用", "不启用", "不要停用", "不停用", "不要禁用", "不禁用",
                "不要恢复", "不恢复", "不要归档", "不归档")) {
            remaining = remaining.replace(negated, "");
        }
        return containsAny(remaining, "新增", "创建", "修改", "更新", "删除", "取消", "提交", "保存", "付款", "发货",
                "启用", "停用", "禁用", "恢复", "归档")
                || WRITE_INTENT.matcher(remaining).find();
    }

    private boolean containsAny(String value, String... candidates) {
        if (value == null || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && value.contains(candidate)) {
                return true;
            }
        }
        return false;
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

    private String normalizeRisk(RuntimeAgentWorkflowToolSnapshot tool) {
        String risk = text(tool.getRiskLevel());
        if (StringUtils.hasText(risk)) return risk.toUpperCase(Locale.ROOT);
        return Boolean.TRUE.equals(tool.getReadOnly()) ? "READ" : "WRITE";
    }

    private PolicyDecision deny(String reason) {
        return new PolicyDecision(false, false, "DENY", reason, null, null);
    }

    private PolicyDecision evalSideEffectDenied(String reason) {
        return new PolicyDecision(false, false, "EVAL_SIDE_EFFECT_BLOCKED", reason, null, null);
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
