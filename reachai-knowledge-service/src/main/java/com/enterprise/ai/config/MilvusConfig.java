package com.enterprise.ai.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class MilvusConfig {

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    public MilvusConfig(@Value("${milvus.host}") String host,
                        @Value("${milvus.port}") int port,
                        @Value("${milvus.username:}") String username,
                        @Value("${milvus.password:}") String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        return new MilvusServiceClient(createConnectParam());
    }

    ConnectParam createConnectParam() {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port);
        boolean hasUsername = StringUtils.hasText(username);
        boolean hasPassword = StringUtils.hasText(password);
        if (hasUsername != hasPassword) {
            throw new IllegalStateException("Milvus username and password must be configured together");
        }
        if (hasUsername) {
            builder.withAuthorization(username, password);
        }
        return builder.build();
    }
}
