package com.enterprise.ai.agent.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.ai.agent.config.JishiAgentProperties;
import com.enterprise.ai.agent.config.JishiAgentProperties.AgentDefinition;
import com.enterprise.ai.agent.model.jishi.JishiAppInfo;
import com.enterprise.ai.agent.model.jishi.JishiFileUploadResult;
import com.enterprise.ai.agent.model.jishi.JishiHistoryMessage;
import com.enterprise.ai.agent.model.jishi.JishiSessionInfo;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 极视角平台业务工具客户端
 * <p>
 * 仅保留极视角平台的<b>业务工具能力</b>（会话管理、文件上传、工作流交互等）。
 * <p>
 * LLM 对话能力已迁移至 ai-model-service（JishiProvider），
 * RAG 检索已迁移至 ai-skills-service。
 * <p>
 * API 路径模式：/console/api/app_ability/public/{appId}/...
 *
 * @see JishiAgentProperties
 */
@Slf4j
@Component
public class JishiAgentClient {

    private static final String API_PATH_PREFIX = "/console/api/app_ability/public/";

    private final RestClient restClient;
    private final JishiAgentProperties properties;
    private final ObjectMapper objectMapper;

    public JishiAgentClient(JishiAgentProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set(HttpHeaders.AUTHORIZATION, properties.getApiKey());
                    request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return execution.execute(request, body);
                })
                .build();
    }

    @PostConstruct
    public void init() {
        String apiKey = properties.getApiKey();
        String baseUrl = properties.getBaseUrl();
        int agentCount = properties.getAgents().size();

        if (apiKey == null || apiKey.isBlank()) {
            log.error("[极视角客户端] API Key 未配置! 请检查 jishi.platform.api-key");
        } else {
            String masked = apiKey.substring(0, Math.min(6, apiKey.length())) + "***";
            log.info("[极视角客户端] 初始化完成: baseUrl={}, apiKey={}, 已注册智能体={}个",
                    baseUrl, masked, agentCount);
        }

        properties.getAgents().forEach((key, def) ->
                log.info("[极视角客户端] 智能体注册: key={}, appId={}, name={}",
                        key, def.getAppId(), def.getName()));
    }

    // ==================== 会话管理 ====================

    /**
     * 获取用户的会话列表
     */
    public List<JishiSessionInfo> getSessions(String agentKey, String user) {
        String appId = resolveAppId(agentKey);
        log.debug("获取会话列表: agent={}, user={}", agentKey, user);

        try {
            String responseBody = restClient.get()
                    .uri(agentPath(appId) + "/sessions?user=" + user)
                    .retrieve()
                    .body(String.class);

            JsonNode dataNode = objectMapper.readTree(responseBody).path("data");
            return objectMapper.readValue(
                    dataNode.toString(),
                    new TypeReference<List<JishiSessionInfo>>() {});

        } catch (Exception e) {
            log.error("获取会话列表失败: agent={}", agentKey, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取会话历史消息
     */
    public List<JishiHistoryMessage> getHistory(String agentKey, String sessionId) {
        String appId = resolveAppId(agentKey);
        log.debug("获取会话历史: agent={}, sessionId={}", agentKey, sessionId);

        try {
            String responseBody = restClient.get()
                    .uri(agentPath(appId) + "/history?session_id=" + sessionId)
                    .retrieve()
                    .body(String.class);

            JsonNode dataNode = objectMapper.readTree(responseBody).path("data");
            return objectMapper.readValue(
                    dataNode.toString(),
                    new TypeReference<List<JishiHistoryMessage>>() {});

        } catch (Exception e) {
            log.error("获取会话历史失败: agent={}, sessionId={}", agentKey, sessionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 删除会话
     */
    public boolean deleteSession(String agentKey, String sessionId) {
        String appId = resolveAppId(agentKey);
        log.info("删除会话: agent={}, sessionId={}", agentKey, sessionId);

        try {
            restClient.delete()
                    .uri(agentPath(appId) + "/sessions/" + sessionId)
                    .retrieve()
                    .body(String.class);
            return true;

        } catch (Exception e) {
            log.error("删除会话失败: agent={}, sessionId={}", agentKey, sessionId, e);
            return false;
        }
    }

    // ==================== 控制 ====================

    /**
     * 停止响应
     */
    public boolean stopResponse(String agentKey, String sessionId) {
        String appId = resolveAppId(agentKey);
        log.info("停止响应: agent={}, sessionId={}", agentKey, sessionId);

        try {
            restClient.post()
                    .uri(agentPath(appId) + "/sessions/" + sessionId + "/stop")
                    .retrieve()
                    .body(String.class);
            return true;

        } catch (Exception e) {
            log.error("停止响应失败: agent={}, sessionId={}", agentKey, sessionId, e);
            return false;
        }
    }

    // ==================== 文件 ====================

    /**
     * 上传文件到指定会话
     */
    public JishiFileUploadResult uploadFile(String agentKey, String sessionId,
                                            byte[] fileContent, String fileName) {
        String appId = resolveAppId(agentKey);
        log.info("上传文件: agent={}, sessionId={}, fileName={}", agentKey, sessionId, fileName);

        try {
            String responseBody = restClient.post()
                    .uri(agentPath(appId) + "/files/" + sessionId + "/upload")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(Map.of("file", fileContent))
                    .retrieve()
                    .body(String.class);

            return objectMapper.readValue(responseBody, JishiFileUploadResult.class);

        } catch (Exception e) {
            log.error("文件上传失败: agent={}, sessionId={}", agentKey, sessionId, e);
            return null;
        }
    }

    // ==================== 应用信息 ====================

    /**
     * 获取应用参数和基本信息
     */
    public JishiAppInfo getAppInfo(String agentKey) {
        String appId = resolveAppId(agentKey);
        log.debug("获取应用信息: agent={}, appId={}", agentKey, appId);

        try {
            String responseBody = restClient.get()
                    .uri(agentPath(appId) + "/info")
                    .retrieve()
                    .body(String.class);

            return objectMapper.readValue(responseBody, JishiAppInfo.class);

        } catch (Exception e) {
            log.error("获取应用信息失败: agent={}", agentKey, e);
            return null;
        }
    }

    /**
     * 提交用户输入（用于工作流中的交互式节点）
     */
    public boolean submitUserInput(String agentKey, String requestId, Map<String, Object> userInputs) {
        String appId = resolveAppId(agentKey);
        log.info("提交用户输入: agent={}, requestId={}", agentKey, requestId);

        try {
            restClient.post()
                    .uri(agentPath(appId) + "/user_input")
                    .body(Map.of("request_id", requestId, "user_inputs", userInputs))
                    .retrieve()
                    .body(String.class);
            return true;

        } catch (Exception e) {
            log.error("提交用户输入失败: agent={}, requestId={}", agentKey, requestId, e);
            return false;
        }
    }

    // ==================== 智能体注册表 ====================

    /**
     * 列出所有已注册的极视角智能体
     */
    public Map<String, AgentDefinition> listRegisteredAgents() {
        return Collections.unmodifiableMap(properties.getAgents());
    }

    /**
     * 根据 agentKey 获取智能体定义
     */
    public AgentDefinition getAgentDefinition(String agentKey) {
        AgentDefinition def = properties.getAgents().get(agentKey);
        if (def == null) {
            throw new IllegalArgumentException("未注册的极视角智能体: " + agentKey
                    + "，已注册: " + properties.getAgents().keySet());
        }
        return def;
    }

    // ==================== 内部方法 ====================

    private String resolveAppId(String agentKey) {
        return getAgentDefinition(agentKey).getAppId();
    }

    private String agentPath(String appId) {
        return API_PATH_PREFIX + appId;
    }
}
