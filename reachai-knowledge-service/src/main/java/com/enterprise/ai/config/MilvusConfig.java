package com.enterprise.ai.config;

import com.enterprise.ai.common.exception.BizException;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

@Slf4j
@Configuration
public class MilvusConfig {

    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(1500);

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final LazyMilvusTargetSource lazyTarget = new LazyMilvusTargetSource(this);

    public MilvusConfig(@Value("${milvus.host}") String host,
                        @Value("${milvus.port}") int port,
                        @Value("${milvus.username:}") String username,
                        @Value("${milvus.password:}") String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    /**
     * Milvus is optional for starting Knowledge: the client connects on first use, so the
     * service (and every non-vector feature) runs without it, and vector features report a
     * clear error until Milvus is reachable. A failed connection is retried on the next call.
     */
    @Bean(destroyMethod = "")
    public MilvusServiceClient milvusServiceClient() {
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.setTargetClass(MilvusServiceClient.class);
        proxyFactory.setTargetSource(lazyTarget);
        return (MilvusServiceClient) proxyFactory.getProxy(MilvusServiceClient.class.getClassLoader());
    }

    @jakarta.annotation.PreDestroy
    void closeMilvus() {
        lazyTarget.close();
    }

    MilvusServiceClient connect() {
        // 先用短超时探一下端口：Milvus 没装时直接给出提示，不用等 gRPC 十秒超时再抛一串底层细节。
        if (!reachable(host, port)) {
            throw new BizException(503, notConnectedMessage());
        }
        try {
            return new MilvusServiceClient(createConnectParam());
        } catch (RuntimeException unreachable) {
            log.warn("Milvus connection to {}:{} failed", host, port, unreachable);
            throw new BizException(503, notConnectedMessage());
        }
    }

    String notConnectedMessage() {
        return "向量库 Milvus（" + host + ":" + port + "）还没连上，知识库暂时不能入库和检索。"
                + "本机开发可运行 docker compose -f deploy/docker-compose.infra.yml --profile milvus up -d；"
                + "已有 Milvus 就在 reachai.env 里填写 MILVUS_HOST / MILVUS_PORT，然后重启知识库服务。";
    }

    static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) PROBE_TIMEOUT.toMillis());
            return true;
        } catch (IOException | IllegalArgumentException unreachable) {
            return false;
        }
    }

    static final class LazyMilvusTargetSource implements TargetSource {

        private final MilvusConfig config;
        private volatile MilvusServiceClient client;

        LazyMilvusTargetSource(MilvusConfig config) {
            this.config = config;
        }

        @Override
        public Class<?> getTargetClass() {
            return MilvusServiceClient.class;
        }

        @Override
        public boolean isStatic() {
            return false;
        }

        @Override
        public Object getTarget() {
            MilvusServiceClient current = client;
            if (current == null) {
                synchronized (this) {
                    current = client;
                    if (current == null) {
                        current = config.connect();
                        client = current;
                    }
                }
            }
            return current;
        }

        @Override
        public void releaseTarget(Object target) {
        }

        void close() {
            MilvusServiceClient current = client;
            if (current != null) {
                current.close();
            }
        }
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
