package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestHeaders;
import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestSigner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffers employee operations in memory and reports them to ReachAI in signed batches. The request thread only
 * offers to a bounded queue: when ReachAI is slow or down, events are dropped instead of slowing the business system.
 */
public class ReachAiActivityRecorder implements DisposableBean {

    static final String SDK = "java-starter";
    static final long PAUSE_WHEN_DISABLED_MS = 10 * 60 * 1000L;
    private static final long WARN_INTERVAL_MS = 5 * 60 * 1000L;
    private static final Logger log = LoggerFactory.getLogger(ReachAiActivityRecorder.class);

    private final ReachAiRegistryProperties properties;
    private final ReachAiRegistryTransport transport;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LinkedBlockingQueue<Map<String, Object>> queue;
    private final AtomicLong dropped = new AtomicLong();
    private volatile long pausedUntil;
    private volatile boolean captureSearch = true;
    private volatile long lastWarnAt;

    public ReachAiActivityRecorder(ReachAiRegistryProperties properties, ReachAiRegistryTransport transport) {
        this.properties = properties;
        this.transport = transport;
        this.queue = new LinkedBlockingQueue<Map<String, Object>>(properties.getActivity().getMaxQueueSize());
    }

    /** Never blocks and never throws. */
    public void record(Map<String, Object> event) {
        if (event == null || System.currentTimeMillis() < pausedUntil) return;
        if (!captureSearch) event.remove("keyword");
        if (!queue.offer(event)) dropped.incrementAndGet();
    }

    public int pending() {
        return queue.size();
    }

    public long dropped() {
        return dropped.get();
    }

    /** Called by the scheduler; sends at most ten batches per tick. */
    public void flush() {
        for (int round = 0; round < 10 && !queue.isEmpty(); round++) {
            List<Map<String, Object>> batch = new ArrayList<Map<String, Object>>();
            queue.drainTo(batch, properties.getActivity().getBatchSize());
            if (batch.isEmpty()) return;
            if (!send(batch)) return;
        }
    }

    boolean send(List<Map<String, Object>> batch) {
        String baseUrl = trimTrailingSlash(properties.getRegistry().getUrl());
        String projectCode = properties.getProject().getCode();
        String appKey = properties.getRegistry().getAppKey();
        String appSecret = properties.getRegistry().getAppSecret();
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(projectCode)
                || !StringUtils.hasText(appKey) || !StringUtils.hasText(appSecret)) {
            warn("ReachAI project code, registry URL, app key and app secret are required to report activity", null);
            return false;
        }
        String code = projectCode.trim().toLowerCase(java.util.Locale.ROOT);
        String path = "/api/activity-ingress/projects/" + code + "/events";
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("sdk", SDK);
            body.put("events", batch);
            byte[] exact = objectMapper.writeValueAsBytes(body);
            ReachAiProjectRequestHeaders signed = ReachAiProjectRequestSigner.sign(appKey, appSecret, code, "POST", path, exact);
            String response = transport.exchange("POST", baseUrl + path, signed.toHttpHeaders(), exact);
            applyResponse(response);
            return true;
        } catch (Exception failure) {
            warn("ReachAI activity report failed; " + batch.size() + " event(s) dropped", failure);
            return false;
        }
    }

    void applyResponse(String response) {
        if (!StringUtils.hasText(response)) return;
        try {
            JsonNode node = objectMapper.readTree(response);
            if (node.has("data") && node.get("data").isObject()) node = node.get("data");
            if (node.path("enabled").isBoolean() && !node.path("enabled").asBoolean()) {
                pausedUntil = System.currentTimeMillis() + PAUSE_WHEN_DISABLED_MS;
                queue.clear();
                log.info("[ReachAI Activity] collection is switched off in ReachAI for project={}; pausing",
                        properties.getProject().getCode());
            }
            if (node.path("captureSearch").isBoolean()) captureSearch = node.path("captureSearch").asBoolean();
        } catch (Exception ignored) {
            // an unexpected answer must not stop reporting
        }
    }

    private void warn(String message, Exception failure) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < WARN_INTERVAL_MS) return;
        lastWarnAt = now;
        log.warn("[ReachAI Activity] {} project={} error={}", message, properties.getProject().getCode(),
                failure == null ? "-" : failure.toString());
    }

    @Override
    public void destroy() {
        try {
            flush();
        } catch (Exception ignored) {
            // shutting down
        }
    }

    private static String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }
}
