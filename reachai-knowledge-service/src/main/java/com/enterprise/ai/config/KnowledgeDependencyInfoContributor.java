package com.enterprise.ai.config;

import com.enterprise.ai.pipeline.document.DoclingProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports whether Knowledge's optional dependencies are reachable, for Control's system
 * check page. Kept out of /actuator/health on purpose: a missing Milvus or Docling must not
 * mark Knowledge as down, because Knowledge runs without them.
 */
@Component
public class KnowledgeDependencyInfoContributor implements InfoContributor {

    static final Duration PROBE_TIMEOUT = Duration.ofMillis(600);
    static final Duration CACHE_TTL = Duration.ofSeconds(15);

    private final String milvusHost;
    private final int milvusPort;
    private final DoclingProperties docling;
    private volatile Map<String, Object> cached;
    private volatile long cachedAtNanos;

    public KnowledgeDependencyInfoContributor(@Value("${milvus.host}") String milvusHost,
                                              @Value("${milvus.port}") int milvusPort,
                                              DoclingProperties docling) {
        this.milvusHost = milvusHost;
        this.milvusPort = milvusPort;
        this.docling = docling;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("dependencies", dependencies());
    }

    public Map<String, Object> dependencies() {
        Map<String, Object> current = cached;
        if (current != null && System.nanoTime() - cachedAtNanos < CACHE_TTL.toNanos()) {
            return current;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("milvus", Map.of(
                "enabled", true,
                "address", milvusHost + ":" + milvusPort,
                "reachable", reachable(milvusHost, milvusPort)));
        Map<String, Object> doclingState = new LinkedHashMap<>();
        doclingState.put("enabled", docling.isEnabled());
        doclingState.put("address", docling.getBaseUrl());
        doclingState.put("reachable", docling.isEnabled() && reachable(docling.getBaseUrl()));
        result.put("docling", doclingState);
        cached = result;
        cachedAtNanos = System.nanoTime();
        return result;
    }

    private static boolean reachable(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            return uri.getHost() != null && reachable(uri.getHost(), port);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) PROBE_TIMEOUT.toMillis());
            return true;
        } catch (IOException | IllegalArgumentException unreachable) {
            return false;
        }
    }
}
