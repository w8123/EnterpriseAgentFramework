package com.jishi.ai.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jishi.ai.agent.client.BusinessSystemClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 业务 API 调用工具
 * <p>
 * 调用下游业务系统的 REST 接口，获取或操作业务数据。
 * 所有调用均经 BusinessSystemClient 中转，确保鉴权和审计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessApiTool implements AiTool {

    private final BusinessSystemClient businessSystemClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "call_business_api";
    }

    @Override
    public String description() {
        return "调用业务系统 REST API。当需要获取或操作业务数据（人员信息、项目状态、审批记录等）时使用。参数：api_path（API路径）、params_json（JSON格式参数）。";
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String apiPath = (String) args.get("api_path");
        String paramsJson = (String) args.get("params_json");
        log.info("[BusinessApiTool] 调用: path={}", apiPath);

        try {
            Map<String, Object> params = objectMapper.readValue(
                    paramsJson, new TypeReference<>() {});
            return businessSystemClient.queryBusinessApi(apiPath, params);
        } catch (Exception e) {
            log.error("[BusinessApiTool] 调用失败", e);
            return "{\"error\": \"业务API调用失败: " + e.getMessage() + "\"}";
        }
    }
}
