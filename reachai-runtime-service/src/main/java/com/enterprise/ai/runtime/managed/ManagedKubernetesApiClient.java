package com.enterprise.ai.runtime.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;

@Component
@ConditionalOnProperty(prefix = "reachai.runtime.managed-executor", name = "sandbox-backend",
        havingValue = "kubernetes")
public class ManagedKubernetesApiClient {

    private static final int MAX_TOKEN_BYTES = 16 * 1024;
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final ManagedKubernetesSandboxProperties properties;
    private final ObjectMapper objectMapper;
    private final URI apiServer;
    private final HttpClient httpClient;

    public ManagedKubernetesApiClient(ManagedKubernetesSandboxProperties properties,
                                      ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.apiServer = validateApiServer(properties.getApiServer());
        this.httpClient = HttpClient.newBuilder()
                .sslContext(buildSslContext(properties.getClusterCaFile()))
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void create(String resourcePath, JsonNode body) {
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(body);
        } catch (IOException failure) {
            throw new KubernetesApiException(0, "KUBERNETES_REQUEST_INVALID");
        }
        if (payload.length > MAX_REQUEST_BYTES) {
            throw new KubernetesApiException(0, "KUBERNETES_REQUEST_TOO_LARGE");
        }
        HttpResponse<InputStream> response = send(request(resourcePath)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build());
        consume(response);
        if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 202) {
            throw new KubernetesApiException(response.statusCode(),
                    response.statusCode() == 409 ? "KUBERNETES_RESOURCE_CONFLICT" : "KUBERNETES_CREATE_FAILED");
        }
    }

    public boolean exists(String resourcePath) {
        HttpResponse<InputStream> response = send(request(resourcePath).GET().build());
        consume(response);
        if (response.statusCode() == 200) return true;
        if (response.statusCode() == 404) return false;
        throw new KubernetesApiException(response.statusCode(), "KUBERNETES_READ_FAILED");
    }

    public void delete(String resourcePath) {
        HttpResponse<InputStream> response = send(request(resourcePath)
                .DELETE()
                .build());
        consume(response);
        if (response.statusCode() != 200 && response.statusCode() != 202 && response.statusCode() != 404) {
            throw new KubernetesApiException(response.statusCode(), "KUBERNETES_DELETE_FAILED");
        }
    }

    private HttpRequest.Builder request(String resourcePath) {
        if (resourcePath == null || !resourcePath.startsWith("/")
                || resourcePath.contains("..") || resourcePath.contains("?") || resourcePath.contains("#")) {
            throw new KubernetesApiException(0, "KUBERNETES_PATH_INVALID");
        }
        return HttpRequest.newBuilder(apiServer.resolve(resourcePath))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + readServiceAccountToken());
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new KubernetesTransportException();
        } catch (IOException failure) {
            throw new KubernetesTransportException();
        }
    }

    private void consume(HttpResponse<InputStream> response) {
        try (InputStream input = response.body()) {
            if (input.readNBytes(MAX_RESPONSE_BYTES + 1).length > MAX_RESPONSE_BYTES) {
                throw new KubernetesApiException(response.statusCode(), "KUBERNETES_RESPONSE_TOO_LARGE");
            }
        } catch (IOException failure) {
            throw new KubernetesTransportException();
        }
    }

    private String readServiceAccountToken() {
        try {
            Path path = Path.of(properties.getServiceAccountTokenFile()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_TOKEN_BYTES) {
                throw new KubernetesTransportException();
            }
            String token = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (token.length() < 32 || token.chars().anyMatch(Character::isWhitespace)) {
                throw new KubernetesTransportException();
            }
            return token;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof KubernetesTransportException transport) throw transport;
            throw new KubernetesTransportException();
        }
    }

    private URI validateApiServer(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            String normalized = uri.toASCIIString();
            return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Managed Kubernetes API server must be credential-free HTTPS");
        }
    }

    private SSLContext buildSslContext(String caFile) {
        try (InputStream input = Files.newInputStream(Path.of(caFile).toAbsolutePath().normalize())) {
            Certificate certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("kubernetes-cluster-ca", certificate);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(keyStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot initialize Managed Kubernetes cluster trust");
        }
    }

    public static class KubernetesApiException extends RuntimeException {
        private final int status;
        private final String code;

        KubernetesApiException(int status, String code) {
            super("Managed Kubernetes API request failed (" + status + ", " + code + ")");
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    public static class KubernetesTransportException extends RuntimeException {
        KubernetesTransportException() {
            super("Managed Kubernetes API transport failed");
        }
    }
}
