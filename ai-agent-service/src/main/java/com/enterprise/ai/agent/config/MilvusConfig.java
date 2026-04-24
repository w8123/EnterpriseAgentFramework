package com.enterprise.ai.agent.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 客户端配置（Tool Retrieval 使用）。
 * <p>
 * ai-agent-service 直连 Milvus，避免每次召回多走一跳 HTTP 进 ai-skills-service；
 * 配置项与 ai-skills-service 对齐，指向同一个集群。
 */
@Slf4j
@Configuration
public class MilvusConfig {

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        log.info("[MilvusConfig] 连接 Milvus: {}:{}", host, port);
        ConnectParam param = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build();
        return new MilvusServiceClient(param);
    }
}
