package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter.RemoteAgentBinding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 已发布委派工具的模型协议适配；执行回调仍由 Supervisor 施加计划、授权及追踪约束。 */
final class SupervisorDelegationTools {
    private SupervisorDelegationTools() { }

    static AgentTool remoteAgent(
            RemoteAgentBinding binding, ObjectMapper objectMapper,
            Function<Map<String, Object>, Mono<ToolResultBlock>> execute) {
        return new AgentTool() {
            @Override public String getName() {
                return binding.getToolName();
            }

            @Override public String getDescription() {
                return StringUtils.hasText(binding.getDescriptionSnapshot())
                        ? binding.getDescriptionSnapshot().trim()
                        : ("Delegate a governed task to remote Agent " + binding.getRemoteAgentKeySnapshot()).trim();
            }

            @Override public Map<String, Object> getParameters() {
                List<String> skills = publishedList(objectMapper, binding.getAllowedSkillIdsJson());
                List<String> outputs = publishedList(objectMapper, binding.getOutputModesJson());
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("text", Map.of(
                        "type", "string",
                        "minLength", 1,
                        "maxLength", 262_144,
                        "description", "Complete task instruction for the remote Agent"));
                Map<String, Object> skill = new LinkedHashMap<>();
                skill.put("type", "string");
                skill.put("description", "Exact protocol AgentSkill id allowed by the fixed binding");
                if (!skills.isEmpty() && !skills.contains("*")) skill.put("enum", skills);
                properties.put("protocolSkillId", skill);
                Map<String, Object> outputModes = new LinkedHashMap<>();
                outputModes.put("type", "array");
                Map<String, Object> outputItem = new LinkedHashMap<>();
                outputItem.put("type", "string");
                if (!outputs.isEmpty() && !outputs.contains("*/*")) outputItem.put("enum", outputs);
                outputModes.put("items", outputItem);
                outputModes.put("description", "Optional accepted media types from the fixed Agent Card");
                properties.put("acceptedOutputModes", outputModes);
                properties.put("contextId", Map.of(
                        "type", "string",
                        "description", "ReachAI outbound context id from an earlier result, only for continuation"));
                properties.put("taskId", Map.of(
                        "type", "string",
                        "description", "ReachAI outbound task id from an INPUT_REQUIRED result, only for continuation"));
                return Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", List.of("text", "protocolSkillId"),
                        "additionalProperties", false);
            }

            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return execute.apply(param.getInput());
            }
        };
    }

    static AgentTool managedExecutor(String toolName,
            Function<Map<String, Object>, Mono<ToolResultBlock>> execute) {
        return new AgentTool() {
            @Override public String getName() {
                return toolName;
            }

            @Override public String getDescription() {
                return switch (toolName) {
                    case ManagedExecutorAgentDelegationService.START_TOOL ->
                            "Create one asynchronous, Runtime-governed coding execution in the fixed sandbox "
                                    + "profile for this Agent configuration. Returns immediately with a task card.";
                    case ManagedExecutorAgentDelegationService.STATUS_TOOL ->
                            "Read a sanitized status card for a Managed Execution created by this exact Agent "
                                    + "configuration and trusted user.";
                    case ManagedExecutorAgentDelegationService.READ_RESULT_TOOL ->
                            "Read verified result metadata and Artifact references for a successful Managed "
                                    + "Execution created by this exact Agent configuration and trusted user.";
                    default -> "Unavailable Managed Executor tool";
                };
            }

            @Override public Map<String, Object> getParameters() {
                if (ManagedExecutorAgentDelegationService.START_TOOL.equals(toolName)) {
                    return Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "objective", Map.of(
                                            "type", "string",
                                            "minLength", 1,
                                            "maxLength", 65_535,
                                            "description", "Bounded coding or repository-analysis objective; never include credentials")),
                            "required", List.of("objective"),
                            "additionalProperties", false);
                }
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "executionId", Map.of(
                                        "type", "string",
                                        "pattern", "^mex_[A-Za-z0-9._:-]+$",
                                        "maxLength", 128,
                                        "description", "Exact Managed Execution id returned by an earlier task card")),
                        "required", List.of("executionId"),
                        "additionalProperties", false);
            }

            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return execute.apply(param.getInput());
            }
        };
    }

    static List<String> publishedList(ObjectMapper objectMapper, String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            if (values == null) return List.of();
            return values.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
        } catch (Exception failure) {
            throw new IllegalStateException("Published A2A binding contains invalid list JSON", failure);
        }
    }

}
