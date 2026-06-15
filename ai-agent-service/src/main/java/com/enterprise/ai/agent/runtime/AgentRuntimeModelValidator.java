package com.enterprise.ai.agent.runtime;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AgentRuntimeModelValidator {

    private static final String ACTIVE = "ACTIVE";
    private static final int SUCCESS_CODE = 200;

    private final ModelServiceClient modelServiceClient;

    public ModelServiceClient.ModelInstanceData validate(AgentRuntimeRequest request,
                                                         AgentRuntimeCapability capability) {
        AgentDefinition definition = request == null ? null : request.getAgentDefinition();
        GraphRuntimeContext runtimeContext = request == null ? null : request.getGraphRuntimeContext();
        GraphSpec graphSpec = request == null ? null : request.getGraphSpec();
        if (graphSpec == null && definition != null) {
            graphSpec = definition.getGraphSpec();
        }
        String modelInstanceId = runtimeContext == null
                ? definition == null ? null : definition.getModelInstanceId()
                : runtimeContext.getModelInstanceId();
        if (modelInstanceId == null || modelInstanceId.isBlank()) {
            if (!requiresModelInstance(definition, graphSpec, runtimeContext)) {
                return null;
            }
            throw new IllegalStateException("modelInstanceId is required for runtime selection");
        }

        ModelServiceClient.ModelInstanceResult result = modelServiceClient.getModelInstance(modelInstanceId.trim());
        if (result == null || result.getCode() != SUCCESS_CODE || result.getData() == null) {
            String message = result == null ? "empty response" : result.getMessage();
            throw new IllegalStateException("模型实例查询失败: " + message);
        }
        ModelServiceClient.ModelInstanceData instance = result.getData();
        if (!ACTIVE.equalsIgnoreCase(nullToEmpty(instance.getStatus()))) {
            throw new IllegalStateException("模型实例不可用: " + modelInstanceId + "，status=" + instance.getStatus());
        }
        List<String> supported = capability == null ? null : capability.getSupportedModelTypes();
        if (supported != null && !supported.isEmpty()) {
            String modelType = normalize(instance.getModelType());
            boolean matched = supported.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(AgentRuntimeModelValidator::normalize)
                    .anyMatch(modelType::equals);
            if (!matched) {
                throw new IllegalStateException("模型实例类型不兼容: runtime="
                        + capability.getRuntimeType() + ", modelType=" + instance.getModelType());
            }
        }
        return instance;
    }

    private static String normalize(String value) {
        return nullToEmpty(value).trim().toUpperCase(Locale.ROOT);
    }

    private boolean requiresModelInstance(AgentDefinition definition,
                                         GraphSpec graphSpec,
                                         GraphRuntimeContext runtimeContext) {
        String runtimeType = runtimeContext == null
                ? normalize(definition == null ? null : definition.getRuntimeType())
                : normalize(runtimeContext.getRuntimeType());
        if (!AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE.equals(runtimeType)) {
            return true;
        }
        GraphSpec graph = graphSpec;
        if (graph == null && definition != null) {
            graph = definition.getGraphSpec();
        }
        List<GraphSpec.Node> nodes = graph == null || graph.getNodes() == null ? List.of() : graph.getNodes();
        return nodes.stream().anyMatch(this::nodeRequiresModel);
    }

    private boolean nodeRequiresModel(GraphSpec.Node node) {
        if (node == null) {
            return false;
        }
        String type = AgentGraphNodeType.normalize(node.getType());
        if ("LLM".equals(type)) {
            return true;
        }
        if ("PARAMETER_EXTRACT".equals(type)) {
            return "LLM".equals(normalize(text(config(node).get("extractMode"))));
        }
        return false;
    }

    private Map<String, Object> config(GraphSpec.Node node) {
        return node == null || node.getConfig() == null ? Map.of() : node.getConfig();
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : "";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
