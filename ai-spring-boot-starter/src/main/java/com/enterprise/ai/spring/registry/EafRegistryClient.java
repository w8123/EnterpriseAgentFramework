package com.enterprise.ai.spring.registry;

import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EafRegistryClient {

    private final EafRegistryProperties properties;

    private final EafCapabilityScanner scanner;

    private final RestClient restClient;

    private final String instanceId;

    public EafRegistryClient(EafRegistryProperties properties, EafCapabilityScanner scanner) {
        this.properties = properties;
        this.scanner = scanner;
        this.restClient = RestClient.builder().baseUrl(trimTrailingSlash(properties.getRegistry().getUrl())).build();
        this.instanceId = resolveInstanceId();
    }

    public void registerAndSync() {
        if (!isConfigured()) {
            return;
        }
        try {
            registerProject();
            heartbeat();
            if (properties.getCapability().isSyncOnStartup()) {
                syncCapabilities(scanner.scan());
            }
        } catch (Exception ex) {
            // Starter 不能因为注册中心临时不可用拖垮业务系统启动；后续心跳会继续重试。
        }
    }

    public void heartbeat() {
        if (!isConfigured()) {
            return;
        }
        Map<String, Object> body = Map.of(
                "instanceId", instanceId,
                "baseUrl", defaultString(properties.getProject().getBaseUrl(), ""),
                "host", hostName(),
                "sdkVersion", EafRegistryClient.class.getPackage().getImplementationVersion() == null
                        ? "dev"
                        : EafRegistryClient.class.getPackage().getImplementationVersion()
        );
        signedPost("/api/registry/projects/{projectCode}/instances/heartbeat", body);
    }

    public void offline() {
        if (!isConfigured()) {
            return;
        }
        try {
            signedPost("/api/registry/projects/{projectCode}/instances/offline", Map.of("instanceId", instanceId));
        } catch (Exception ignored) {
            // shutdown hook 中失败不影响业务进程退出
        }
    }

    public List<EafCapabilityDescriptor> capabilities() {
        return scanner.scan();
    }

    private void registerProject() {
        Map<String, Object> body = Map.of(
                "projectCode", properties.getProject().getCode(),
                "name", defaultString(properties.getProject().getName(), properties.getProject().getCode()),
                "environment", defaultString(properties.getProject().getEnvironment(), "default"),
                "owner", defaultString(properties.getProject().getOwner(), ""),
                "visibility", defaultString(properties.getProject().getVisibility(), "PRIVATE"),
                "baseUrl", defaultString(properties.getProject().getBaseUrl(), ""),
                "contextPath", defaultString(properties.getProject().getContextPath(), ""),
                "appKey", defaultString(properties.getRegistry().getAppKey(), "")
        );
        signedPost("/api/registry/projects/register", body);
    }

    private void syncCapabilities(List<EafCapabilityDescriptor> capabilities) {
        Map<String, Object> body = Map.of(
                "syncId", UUID.randomUUID().toString(),
                "source", "SDK",
                "apply", true,
                "capabilities", capabilities
        );
        signedPost("/api/registry/projects/{projectCode}/capabilities/sync", body);
    }

    private boolean isConfigured() {
        return properties.getRegistry().isEnabled()
                && StringUtils.hasText(properties.getRegistry().getUrl())
                && StringUtils.hasText(properties.getProject().getCode());
    }

    private String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void signedPost(String uri, Object body) {
        SignatureHeaders headers = signatureHeaders();
        restClient.post()
                .uri(uri, properties.getProject().getCode())
                .header("X-EAF-App-Key", headers.appKey())
                .header("X-EAF-Timestamp", headers.timestamp())
                .header("X-EAF-Nonce", headers.nonce())
                .header("X-EAF-Signature", headers.signature())
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private SignatureHeaders signatureHeaders() {
        String appKey = defaultString(properties.getRegistry().getAppKey(), properties.getProject().getCode());
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String message = properties.getProject().getCode() + "\n" + timestamp + "\n" + nonce;
        String signature = StringUtils.hasText(properties.getRegistry().getAppSecret())
                ? hmacSha256Hex(properties.getRegistry().getAppSecret(), message)
                : "";
        return new SignatureHeaders(appKey, timestamp, nonce, signature);
    }

    private String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private String resolveInstanceId() {
        return hostName() + "-" + ManagementFactory.getRuntimeMXBean().getName();
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private record SignatureHeaders(String appKey, String timestamp, String nonce, String signature) {
    }
}
