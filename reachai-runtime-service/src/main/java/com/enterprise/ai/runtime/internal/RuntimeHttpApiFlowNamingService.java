package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigService;
import com.enterprise.ai.runtime.agent.RuntimeAgentService;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chinese names for HTTP APIs about to become flows, written by the system's own assistant model from
 * each API's accepted contract, so a newly connected system is usable by employees without someone
 * naming hundreds of routes by hand. Nothing is created here; the admin still reviews the names.
 */
@Service
@RequiredArgsConstructor
public class RuntimeHttpApiFlowNamingService {

    static final int MAX_APIS = 50;
    private static final int CONTRACT_PREVIEW = 500;

    private final RuntimeCapabilityCatalogClient catalog;
    private final RuntimeAgentService agents;
    private final RuntimeAgentConfigService agentConfigs;
    private final RuntimeModelServiceClient models;
    private final ObjectMapper objectMapper;

    public Map<Long, String> suggest(NamingRequest request) {
        if (request == null || !StringUtils.hasText(request.projectCode())) {
            throw new IllegalArgumentException("projectCode 必填");
        }
        List<Long> ids = request.apiIds() == null ? List.of() : request.apiIds().stream()
                .filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) throw new IllegalArgumentException("请至少选择一个 API");
        if (ids.size() > MAX_APIS) throw new IllegalArgumentException("一次最多起名 " + MAX_APIS + " 个 API");
        String projectCode = request.projectCode().trim();
        StringBuilder list = new StringBuilder();
        for (Long id : ids) {
            HttpApiConsoleContracts.ExecutionContext owner;
            try {
                owner = catalog.getHttpApiExecutionContext(id, projectCode);
            } catch (RuntimeException unavailable) {
                continue;
            }
            if (owner == null || !projectCode.equals(owner.projectCode())) continue;
            list.append("- id: ").append(id).append('\n')
                    .append("  route: ").append(owner.httpMethod()).append(' ').append(owner.routeTemplate()).append('\n')
                    .append("  contract: ").append(preview(owner.acceptedContract() != null
                            ? owner.acceptedContract() : null)).append('\n');
        }
        if (list.isEmpty()) throw new IllegalArgumentException("这些 API 都读不到契约，无法起名");
        String model = StringUtils.hasText(request.modelInstanceId()) ? request.modelInstanceId().trim()
                : projectModel(projectCode);
        if (!StringUtils.hasText(model)) {
            throw new IllegalArgumentException("平台上还没有配置好模型的助手，请先在模型中心添加模型并创建一个助手");
        }
        ModelChatResult result = models.chat(ModelChatRequest.builder()
                .modelInstanceId(model)
                .messages(List.of(
                        ChatMessage.builder().role("system").content(
                                "你给企业业务系统的接口起中文名，员工和 AI 助手会按名字选用。"
                                        + "每个名字 4 到 14 个字，写成「动作 + 业务对象」，例如「查询销售订单」「新建客户」「删除商品」。"
                                        + "用员工的说法，不要写「分页」「列表接口」「精简」这类技术词，例如 /product/page 叫「查询产品」。"
                                        + "根据路由和契约里的 summary、description、参数判断，不要编造业务含义；看不出来就按路由直译。"
                                        + "只输出 JSON：{\"names\":[{\"id\":数字,\"name\":\"中文名\"}]}").build(),
                        ChatMessage.builder().role("user").content("接口列表：\n" + list).build()))
                .options(Map.of("temperature", 0.2))
                .build());
        if (result == null || result.getData() == null || !StringUtils.hasText(result.getData().getContent())) {
            throw new IllegalArgumentException("模型没有返回名称，请稍后再试");
        }
        return parse(result.getData().getContent(), ids);
    }

    Map<Long, String> parse(String content, List<Long> allowed) {
        Map<Long, String> names = new LinkedHashMap<>();
        int start = content == null ? -1 : content.indexOf('{');
        int end = content == null ? -1 : content.lastIndexOf('}');
        if (start < 0 || end <= start) return names;
        try {
            for (JsonNode item : objectMapper.readTree(content.substring(start, end + 1)).path("names")) {
                long id = item.path("id").asLong(-1);
                String name = item.path("name").asText("").trim();
                if (allowed.contains(id) && name.length() >= 2 && name.length() <= 30) names.put(id, name);
            }
        } catch (Exception invalid) {
            return Map.of();
        }
        return names;
    }

    /**
     * The model of the system's assistant, which is what the admin already chose and pays for. A system
     * connected only through HTTP APIs has no assistant yet, so it borrows the model most assistants on
     * the platform already use.
     */
    private String projectModel(String projectCode) {
        for (RuntimeAgentView agent : agents.list(null, projectCode)) {
            String model = activeModel(agent);
            if (StringUtils.hasText(model)) return model;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RuntimeAgentView agent : agents.list(null, null)) {
            String model = activeModel(agent);
            if (StringUtils.hasText(model)) counts.merge(model, 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private String activeModel(RuntimeAgentView agent) {
        if (agent == null || Boolean.FALSE.equals(agent.enabled())) return null;
        return agentConfigs.resolveActive(agent.id()).map(config -> config.getModelInstanceId()).orElse(null);
    }

    private String preview(JsonNode contract) {
        if (contract == null || contract.isNull()) return "{}";
        Map<String, Object> slim = new LinkedHashMap<>();
        for (String key : List.of("summary", "description", "operationId", "tags")) {
            if (contract.hasNonNull(key)) slim.put(key, contract.get(key));
        }
        List<String> parameters = new ArrayList<>();
        for (JsonNode parameter : contract.path("parameters")) {
            parameters.add(parameter.path("name").asText("") + ":" + parameter.path("description").asText(""));
        }
        if (!parameters.isEmpty()) slim.put("parameters", parameters);
        try {
            String text = objectMapper.writeValueAsString(slim);
            return text.length() <= CONTRACT_PREVIEW ? text : text.substring(0, CONTRACT_PREVIEW);
        } catch (Exception failure) {
            return "{}";
        }
    }

    public record NamingRequest(String projectCode, List<Long> apiIds, String modelInstanceId) {
    }
}
