package com.enterprise.ai.skill.biz.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 业务系统 API 客户端
 * <p>
 * 封装对下游业务系统的 REST 调用。AI Tool 通过此客户端访问业务数据，
 * 而非直接操作数据库，确保权限隔离和接口契约稳定。
 */
@Slf4j
@Component
public class BusinessSystemClient {

    private final RestClient restClient;

    public BusinessSystemClient(
            @Value("${business.system.base-url:http://localhost:8082}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String queryBusinessApi(String apiPath, Map<String, Object> queryParams) {
        log.debug("调用业务系统API: path={}, params={}", apiPath, queryParams);
        try {
            return restClient.post()
                    .uri(apiPath)
                    .body(queryParams)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("业务系统API调用失败: path={}", apiPath, e);
            return "{\"error\": \"业务系统调用失败: " + e.getMessage() + "\"}";
        }
    }

    public String executeSqlQuery(String sql) {
        log.debug("执行SQL查询（经业务层）: sql={}", sql);
        try {
            return restClient.post()
                    .uri("/api/data/query")
                    .body(Map.of("sql", sql))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("SQL查询执行失败", e);
            return "{\"error\": \"数据查询失败: " + e.getMessage() + "\"}";
        }
    }
}
