package com.enterprise.ai.model.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ModelCatalogHttpFetcher {

    private static final String USER_AGENT = "ReachAI-Model-Catalog/1.0";

    private final ModelCatalogSyncProperties properties;
    private final ObjectMapper objectMapper;

    public FetchResult fetch(ModelCatalogSourceEntity source) {
        URI uri = ModelCatalogSourcePolicy.validate(source);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(Math.max(5, properties.getRequestTimeoutSeconds())))
                .header("Accept", acceptHeader(source.getContentFormat()))
                .header("User-Agent", USER_AGENT);
        if (StringUtils.hasText(source.getLastHttpEtag())) {
            request.header("If-None-Match", source.getLastHttpEtag().trim());
        }
        if (StringUtils.hasText(source.getLastHttpModified())) {
            request.header("If-Modified-Since", source.getLastHttpModified().trim());
        }
        applyAuthentication(request, source.getAuthType());

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, properties.getRequestTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String etag = response.headers().firstValue("ETag").orElse(null);
            String lastModified = response.headers().firstValue("Last-Modified").orElse(null);
            String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            if (status == 304) {
                closeQuietly(response.body());
                return new FetchResult(status, true, null, null, 0, contentType,
                        etag, lastModified, headersJson(response), LocalDateTime.now());
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                boolean retriable = status == 408 || status == 429 || status >= 500;
                throw new ModelCatalogSyncException(
                        "MODEL_CATALOG_SOURCE_HTTP_" + status,
                        "Official source returned HTTP " + status,
                        retriable);
            }
            int maxBytes = Math.max(4_096, properties.getMaxContentBytes());
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredLength > maxBytes) {
                closeQuietly(response.body());
                throw new ModelCatalogSyncException(
                        "MODEL_CATALOG_SOURCE_TOO_LARGE",
                        "Official source exceeds the configured content limit",
                        false);
            }
            byte[] bytes;
            try (InputStream body = response.body()) {
                bytes = body.readNBytes(maxBytes + 1);
            }
            if (bytes.length > maxBytes) {
                throw new ModelCatalogSyncException(
                        "MODEL_CATALOG_SOURCE_TOO_LARGE",
                        "Official source exceeds the configured content limit",
                        false);
            }
            String normalized = normalize(source.getContentFormat(), bytes);
            if (!StringUtils.hasText(normalized)) {
                throw new ModelCatalogSyncException(
                        "MODEL_CATALOG_SOURCE_EMPTY",
                        "Official source returned no usable content",
                        true);
            }
            return new FetchResult(status, false, normalized, sha256(normalized), bytes.length, contentType,
                    etag, lastModified, headersJson(response), LocalDateTime.now());
        } catch (ModelCatalogSyncException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_SOURCE_INTERRUPTED", "Official source fetch was interrupted", true, ex);
        } catch (Exception ex) {
            throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_SOURCE_FETCH_FAILED", safeMessage(ex), true, ex);
        }
    }

    private void applyAuthentication(HttpRequest.Builder request, String authType) {
        String normalized = authType == null ? "NONE" : authType.trim().toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "NONE" -> {
            }
            case "OPENAI_API_KEY" -> bearer(request, "OPENAI_API_KEY");
            case "DEEPSEEK_API_KEY" -> bearer(request, "DEEPSEEK_API_KEY");
            case "DASHSCOPE_API_KEY" -> bearer(request, "DASHSCOPE_API_KEY");
            case "ANTHROPIC_API_KEY" -> {
                request.header("x-api-key", requiredEnvironment("ANTHROPIC_API_KEY"));
                request.header("anthropic-version", "2023-06-01");
            }
            case "GEMINI_API_KEY" -> request.header("x-goog-api-key", requiredEnvironment("GEMINI_API_KEY"));
            default -> throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_AUTH_TYPE_INVALID", "Official source auth type is not supported", false);
        }
    }

    private void bearer(HttpRequest.Builder request, String environmentName) {
        request.header("Authorization", "Bearer " + requiredEnvironment(environmentName));
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (!StringUtils.hasText(value)) {
            throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_SOURCE_CREDENTIAL_MISSING",
                    "Required official source credential is not configured",
                    false);
        }
        return value.trim();
    }

    private String normalize(String configuredFormat, byte[] bytes) throws Exception {
        String format = configuredFormat == null ? "HTML" : configuredFormat.trim().toUpperCase(Locale.ROOT);
        String raw = new String(bytes, StandardCharsets.UTF_8).replace('\u0000', ' ');
        return switch (format) {
            case "JSON" -> objectMapper.readTree(raw).toString();
            case "MARKDOWN", "TEXT" -> normalizeWhitespace(raw);
            case "HTML" -> normalizeHtml(bytes);
            default -> throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_CONTENT_FORMAT_INVALID", "Official source content format is not supported", false);
        };
    }

    private String normalizeHtml(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder(Math.min(bytes.length, 256_000));
        new ParserDelegator().parse(
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8),
                new HTMLEditorKit.ParserCallback() {
                    private int ignoredDepth;

                    @Override
                    public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                        if (isIgnored(tag)) ignoredDepth++;
                        if (ignoredDepth == 0 && isBlock(tag)) text.append('\n');
                    }

                    @Override
                    public void handleEndTag(HTML.Tag tag, int position) {
                        if (isIgnored(tag) && ignoredDepth > 0) {
                            ignoredDepth--;
                            return;
                        }
                        if (ignoredDepth == 0 && isBlock(tag)) text.append('\n');
                    }

                    @Override
                    public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                        if (ignoredDepth == 0 && (tag == HTML.Tag.BR || tag == HTML.Tag.HR)) text.append('\n');
                    }

                    @Override
                    public void handleText(char[] data, int position) {
                        if (ignoredDepth == 0) text.append(data).append(' ');
                    }
                },
                true);
        return normalizeWhitespace(text.toString());
    }

    private boolean isIgnored(HTML.Tag tag) {
        String name = tag == null ? "" : tag.toString().toLowerCase(Locale.ROOT);
        return "script".equals(name) || "style".equals(name) || "noscript".equals(name)
                || "svg".equals(name) || "template".equals(name);
    }

    private boolean isBlock(HTML.Tag tag) {
        if (tag == null) return false;
        return tag == HTML.Tag.P || tag == HTML.Tag.DIV || tag == HTML.Tag.LI || tag == HTML.Tag.TR
                || tag == HTML.Tag.H1 || tag == HTML.Tag.H2 || tag == HTML.Tag.H3
                || tag == HTML.Tag.H4 || tag == HTML.Tag.H5 || tag == HTML.Tag.H6
                || tag == HTML.Tag.TABLE || tag == HTML.Tag.UL || tag == HTML.Tag.OL;
    }

    private String normalizeWhitespace(String value) {
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String acceptHeader(String format) {
        if ("JSON".equalsIgnoreCase(format)) return "application/json";
        if ("MARKDOWN".equalsIgnoreCase(format)) return "text/markdown,text/plain;q=0.9";
        return "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.8";
    }

    private String headersJson(HttpResponse<?> response) {
        Map<String, Object> safe = new LinkedHashMap<>();
        response.headers().firstValue("ETag").ifPresent(value -> safe.put("etag", value));
        response.headers().firstValue("Last-Modified").ifPresent(value -> safe.put("lastModified", value));
        response.headers().firstValue("Content-Type").ifPresent(value -> safe.put("contentType", value));
        response.headers().firstValue("Content-Length").ifPresent(value -> safe.put("contentLength", value));
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (Exception ignored) {
        }
    }

    private String safeMessage(Exception error) {
        String value = error.getMessage();
        if (!StringUtils.hasText(value)) value = error.getClass().getSimpleName();
        value = value.replaceAll("(?i)(api[_-]?key|authorization|bearer)\\s*[:=]?\\s*[^\\s,;]+", "$1=[REDACTED]");
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    public record FetchResult(
            int httpStatus,
            boolean notModified,
            String normalizedContent,
            String contentSha256,
            int contentSizeBytes,
            String contentType,
            String etag,
            String lastModified,
            String responseHeadersJson,
            LocalDateTime fetchedAt) {
    }
}
