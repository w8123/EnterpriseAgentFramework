package com.jishi.ai.agent.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jishi.ai.agent.config.JishiAgentProperties;
import com.jishi.ai.agent.config.JishiAgentProperties.AgentDefinition;
import com.jishi.ai.agent.model.jishi.*;
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
 * 极视角平台统一客户端
 * <p>
 * 对接极视角平台的完整对话应用API，支持注册多个智能体。
 * 所有方法的第一个参数 agentKey 对应 application.yml 中 jishi.platform.agents 下的 Key。
 * <p>
 * 新增智能体只需两步：
 * 1. 在 application.yml 的 jishi.platform.agents 下添加一组配置
 * 2. 在业务代码中通过 agentKey 调用
 * <p>
 * API路径模式：/console/api/app_ability/public/{appId}/...
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

    // ==================== 对话 ====================

    /**
     * 发送对话消息（blocking模式）
     *
     * @param agentKey  智能体标识（yml中的Key）
     * @param query     用户提问
     * @param user      用户标识
     * @param sessionId 会话ID（可为null，首次对话由平台生成）
     * @return 对话结果（含answer和sessionId）
     */
    public JishiChatResult chat(String agentKey, String query, String user, String sessionId) {
        JishiChatRequest request = JishiChatRequest.builder()
                .query(query)
                .user(user)
                .sessionId(sessionId)
                .responseMode("blocking")
                .build();
        return chat(agentKey, request);
    }

    /**
     * 发送对话消息（完整参数）
     * <p>
     * 自动兼容两种响应格式：
     * - blocking JSON：{"status":0, "result":{"answer":"...", "session_id":"..."}}
     * - SSE流式：多行 data: {...} 事件，最终答案在 tts_message_end 事件中
     */
    public JishiChatResult chat(String agentKey, JishiChatRequest request) {
        String appId = resolveAppId(agentKey);
        log.info("极视角对话请求: agent={}, appId={}, query={}", agentKey, appId, request.getQuery());

        try {
            String responseBody = restClient.post()
                    .uri(agentPath(appId) + "/chat")
                    .body(request)
                    .retrieve()
                    .body(String.class);

            return parseChatResponse(responseBody);

        } catch (Exception e) {
            log.error("极视角对话请求失败: agent={}", agentKey, e);
            JishiChatResult errorResult = new JishiChatResult();
            errorResult.setAnswer("极视角平台调用失败: " + e.getMessage());
            return errorResult;
        }
    }

    /**
     * 对话重试
     */
    public JishiChatResult retry(String agentKey, String sessionId, String user) {
        String appId = resolveAppId(agentKey);
        log.info("极视角对话重试: agent={}, sessionId={}", agentKey, sessionId);

        try {
            String responseBody = restClient.post()
                    .uri(agentPath(appId) + "/sessions/" + sessionId + "/retry")
                    .body(Map.of("user", user))
                    .retrieve()
                    .body(String.class);

            return parseChatResponse(responseBody);

        } catch (Exception e) {
            log.error("极视角对话重试失败: agent={}, sessionId={}", agentKey, sessionId, e);
            JishiChatResult errorResult = new JishiChatResult();
            errorResult.setAnswer("重试失败: " + e.getMessage());
            return errorResult;
        }
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

    /**
     * 统一解析对话响应，自动识别 blocking JSON 和 SSE 流式两种格式
     */
    private JishiChatResult parseChatResponse(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            JishiChatResult empty = new JishiChatResult();
            empty.setAnswer("极视角平台返回空响应");
            return empty;
        }

        String trimmed = responseBody.trim();

        if (trimmed.startsWith("{")) {
            return parseBlockingJson(trimmed);
        }

        if (trimmed.contains("data:")) {
            return parseSSEResponse(trimmed);
        }

        log.warn("无法识别的极视角响应格式，原文前200字符: {}",
                trimmed.substring(0, Math.min(200, trimmed.length())));
        JishiChatResult fallback = new JishiChatResult();
        fallback.setAnswer(trimmed);
        return fallback;
    }

    /**
     * 解析 blocking 模式的标准JSON响应
     * 格式：{"status":0, "message":"Success", "result":{"answer":"...", "session_id":"..."}}
     */
    private JishiChatResult parseBlockingJson(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode resultNode = root.path("result");
        if (!resultNode.isMissingNode()) {
            return objectMapper.treeToValue(resultNode, JishiChatResult.class);
        }
        if (root.has("answer")) {
            return objectMapper.treeToValue(root, JishiChatResult.class);
        }
        JishiChatResult fallback = new JishiChatResult();
        fallback.setAnswer(root.toString());
        return fallback;
    }

    /**
     * 解析 SSE 流式响应，从多个 data: 事件中提取最终答案
     * <p>
     * 优先级：tts_message_end > message_end > node_finished(含answer) > 最后一个事件
     */
    private JishiChatResult parseSSEResponse(String sseBody) {
        log.debug("解析SSE响应，长度={}", sseBody.length());

        JishiChatResult result = new JishiChatResult();
        String[] lines = sseBody.split("\n");

        for (String line : lines) {
            String trimmedLine = line.trim();
            if (!trimmedLine.startsWith("data:")) {
                continue;
            }

            String jsonStr = trimmedLine.substring(trimmedLine.indexOf(':') + 1).trim();
            if (jsonStr.isEmpty() || jsonStr.equals("[DONE]")) {
                continue;
            }

            try {
                JsonNode event = objectMapper.readTree(jsonStr);
                String eventType = event.path("event").asText("");

                if (event.has("session_id")) {
                    result.setSessionId(event.get("session_id").asText());
                }

                switch (eventType) {
                    case "tts_message_end", "message_end" -> {
                        extractAnswer(event, result);
                        log.debug("SSE最终事件[{}]: answer={}", eventType,
                                result.getAnswer() != null
                                        ? result.getAnswer().substring(0, Math.min(50, result.getAnswer().length()))
                                        : "null");
                        return result;
                    }
                    case "node_finished" -> {
                        if (event.path("data").has("outputs")) {
                            JsonNode outputs = event.path("data").path("outputs");
                            if (outputs.has("answer")) {
                                result.setAnswer(outputs.get("answer").asText());
                            }
                        }
                    }
                    default -> { }
                }
            } catch (Exception e) {
                log.trace("跳过无法解析的SSE行: {}", trimmedLine);
            }
        }

        if (result.getAnswer() == null) {
            result.setAnswer("极视角平台未返回有效回答");
            log.warn("SSE响应中未找到answer字段");
        }
        return result;
    }

    /**
     * 从SSE结束事件中提取answer，兼容多种嵌套结构
     * - answer.output（chatflow类型）
     * - answer（字符串）
     * - result.answer
     */
    private void extractAnswer(JsonNode event, JishiChatResult result) {
        JsonNode answerNode = event.path("answer");

        if (answerNode.isObject() && answerNode.has("output")) {
            result.setAnswer(answerNode.get("output").asText());
        } else if (answerNode.isTextual()) {
            result.setAnswer(answerNode.asText());
        } else if (event.path("result").has("answer")) {
            result.setAnswer(event.path("result").get("answer").asText());
        }
    }

    private String resolveAppId(String agentKey) {
        return getAgentDefinition(agentKey).getAppId();
    }

    private String agentPath(String appId) {
        return API_PATH_PREFIX + appId;
    }
}
