package com.enterprise.ai.pipeline.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Thin client for Docling Serve's stable v1 file API.  It is deliberately the
 * sole parser for all non-plain-text formats; there is no local fallback.
 */
@Slf4j
@Component
public class DoclingDocumentParseProvider implements DocumentParseProvider {

    private final ObjectMapper objectMapper;
    private final DoclingProperties properties;
    private final DocumentParseProperties documentProperties;
    private final HttpClient httpClient;
    private final Semaphore requestPermits;

    public DoclingDocumentParseProvider(ObjectMapper objectMapper,
                                        DoclingProperties properties,
                                        DocumentParseProperties documentProperties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.documentProperties = documentProperties;
        this.httpClient = HttpClient.newBuilder()
                // Docling Serve/Uvicorn exposes HTTP/1.1. Avoid the JDK h2c
                // upgrade probe because a streaming multipart body may be read
                // as an invalid second request after the rejected upgrade.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        this.requestPermits = new Semaphore(Math.max(1, properties.getMaxConcurrentRequests()), true);
    }

    @Override
    public DocumentProviderType getProviderType() {
        return DocumentProviderType.DOCLING;
    }

    @Override
    public DocumentParseResult parse(DocumentParseRequest request) {
        if (request.getFormat() == null || request.getFormat().getProviderType() != DocumentProviderType.DOCLING) {
            throw new DocumentParseException(DocumentParseErrorCode.INTERNAL_ERROR, "Docling Provider 收到错误格式");
        }
        if (!properties.isEnabled()) {
            throw new DocumentParseException(DocumentParseErrorCode.DOCLING_DISABLED, "Docling 服务未启用");
        }
        if (request.getFileSize() > documentProperties.getMaxFileBytes()) {
            throw new DocumentParseException(DocumentParseErrorCode.FILE_TOO_LARGE,
                    "文件超过 Docling 允许大小: " + documentProperties.getMaxFileBytes());
        }

        boolean acquired = false;
        try {
            acquired = requestPermits.tryAcquire(Math.max(1, properties.getConcurrencyWaitTimeoutMs()),
                    TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new DocumentParseException(DocumentParseErrorCode.DOCLING_UNAVAILABLE,
                        "Docling 并发队列已满，请稍后重试");
            }
            return invokeDocling(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentParseException(DocumentParseErrorCode.DOCLING_UNAVAILABLE,
                    "等待 Docling 并发许可时被中断", e);
        } finally {
            if (acquired) {
                requestPermits.release();
            }
        }
    }

    private DocumentParseResult invokeDocling(DocumentParseRequest request) {
        String boundary = "ReachAiDocling-" + UUID.randomUUID();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + "/v1/convert/file"))
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Docling-Log-RequestID", requestId(request))
                .POST(multipartBody(boundary, request));
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.header("X-Api-Key", properties.getApiKey());
        }

        try {
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream responseBody = response.body()) {
                if (response.statusCode() >= 500) {
                    throw new DocumentParseException(DocumentParseErrorCode.DOCLING_UNAVAILABLE,
                            "Docling 服务异常: HTTP " + response.statusCode());
                }
                if (response.statusCode() >= 400) {
                    throw new DocumentParseException(DocumentParseErrorCode.DOCLING_REJECTED,
                            "Docling 拒绝文件: HTTP " + response.statusCode());
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new DocumentParseException(DocumentParseErrorCode.DOCLING_RESPONSE_INVALID,
                            "Docling 返回异常状态: HTTP " + response.statusCode());
                }
                return normalizeResponse(request,
                        new LimitedInputStream(responseBody, Math.max(1L, properties.getMaxResponseBytes())));
            }
        } catch (HttpTimeoutException e) {
            throw new DocumentParseException(DocumentParseErrorCode.DOCLING_TIMEOUT,
                    "Docling 解析超时", e);
        } catch (ConnectException e) {
            throw new DocumentParseException(DocumentParseErrorCode.DOCLING_UNAVAILABLE,
                    "无法连接 Docling 服务", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentParseException(DocumentParseErrorCode.DOCLING_UNAVAILABLE,
                    "等待 Docling 响应时被中断", e);
        } catch (IOException e) {
            throw new DocumentParseException(DocumentParseErrorCode.DOCLING_UNAVAILABLE,
                    "调用 Docling 服务失败", e);
        } catch (UncheckedIOException e) {
            throw new DocumentParseException(DocumentParseErrorCode.INTERNAL_ERROR,
                    "无法读取待解析文档", e.getCause());
        }
    }

    private HttpRequest.BodyPublisher multipartBody(String boundary, DocumentParseRequest request) {
        String contentType = request.getDeclaredContentType();
        String preamble = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\""
                + safeHeaderFileName(request.getFileName()) + "\"\r\n"
                + "Content-Type: " + safeHeaderValue(contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType) + "\r\n\r\n";
        byte[] prefix = preamble.getBytes(StandardCharsets.UTF_8);
        byte[] suffix = multipartSuffix(boundary, request).getBytes(StandardCharsets.UTF_8);
        long contentLength;
        try {
            contentLength = Math.addExact(Math.addExact((long) prefix.length, request.getFileSize()), suffix.length);
        } catch (ArithmeticException e) {
            throw new DocumentParseException(DocumentParseErrorCode.FILE_TOO_LARGE,
                    "Docling multipart 请求大小溢出", e);
        }
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(prefix),
                HttpRequest.BodyPublishers.ofInputStream(() -> openRequestStream(request)),
                HttpRequest.BodyPublishers.ofByteArray(suffix));
        return HttpRequest.BodyPublishers.fromPublisher(publisher, contentLength);
    }

    private String multipartSuffix(String boundary, DocumentParseRequest request) {
        StringBuilder output = new StringBuilder("\r\n");
        appendTextPart(output, boundary, "from_formats", doclingFormat(request.getFormat()));
        appendTextPart(output, boundary, "to_formats", "json");
        appendTextPart(output, boundary, "to_formats", "md");
        appendTextPart(output, boundary, "to_formats", "text");
        appendTextPart(output, boundary, "do_ocr", String.valueOf(properties.isDoOcr()));
        appendTextPart(output, boundary, "force_ocr", String.valueOf(properties.isForceOcr()));
        appendTextPart(output, boundary, "table_mode", properties.getTableMode());
        appendTextPart(output, boundary, "do_table_structure", String.valueOf(properties.isDoTableStructure()));
        appendTextPart(output, boundary, "do_pdf_heading_hierarchy",
                String.valueOf(properties.isDoPdfHeadingHierarchy()));
        appendTextPart(output, boundary, "abort_on_error", "true");
        appendTextPart(output, boundary, "image_export_mode", properties.getImageExportMode());
        appendTextPart(output, boundary, "document_timeout", String.valueOf(properties.getDocumentTimeoutSeconds()));
        if (properties.getOcrPreset() != null && !properties.getOcrPreset().isBlank()) {
            appendTextPart(output, boundary, "ocr_preset", properties.getOcrPreset());
        }
        for (String language : properties.getOcrLanguages()) {
            if (language != null && !language.isBlank()) {
                appendTextPart(output, boundary, "ocr_lang", language);
            }
        }
        output.append("--").append(boundary).append("--\r\n");
        return output.toString();
    }

    private static void appendTextPart(StringBuilder output, String boundary, String name, String value) {
        output.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value == null ? "" : value).append("\r\n");
    }

    private static InputStream openRequestStream(DocumentParseRequest request) {
        try {
            return request.openStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private DocumentParseResult normalizeResponse(DocumentParseRequest request, InputStream responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null) {
                throw new DocumentParseException(DocumentParseErrorCode.DOCLING_RESPONSE_INVALID,
                        "Docling 返回空响应");
            }
            String status = root.path("status").asText("").toLowerCase(Locale.ROOT);
            if ("partial_success".equals(status)) {
                throw new DocumentParseException(DocumentParseErrorCode.DOCLING_PARTIAL_SUCCESS,
                        "Docling 仅完成部分解析，拒绝写入正式知识库");
            }
            if (!"success".equals(status)) {
                throw new DocumentParseException(DocumentParseErrorCode.DOCLING_REJECTED,
                        "Docling 解析失败: " + (status.isBlank() ? "未知状态" : status));
            }

            JsonNode document = root.path("document");
            JsonNode jsonContent = document.path("json_content");
            if (jsonContent.isMissingNode() || jsonContent.isNull() || jsonContent.isEmpty()) {
                throw new DocumentParseException(DocumentParseErrorCode.DOCLING_RESPONSE_INVALID,
                        "Docling 未返回结构化 JSON 结果");
            }
            String markdown = textValue(document.get("md_content"));
            String plainText = textValue(document.get("text_content"));
            List<ParsedDocumentElement> elements = extractElements(jsonContent);
            if (plainText.isBlank()) {
                plainText = elements.stream()
                        .map(ParsedDocumentElement::getText)
                        .filter(value -> value != null && !value.isBlank())
                        .reduce("", (left, right) -> left.isBlank() ? right : left + "\n" + right);
            }
            if (plainText.isBlank()) {
                plainText = indexableMarkdown(markdown);
            }
            if (plainText == null || plainText.isBlank()) {
                throw new DocumentParseException(DocumentParseErrorCode.DOCLING_EMPTY_OUTPUT,
                        "Docling 未提取到可索引文本");
            }
            if (elements.isEmpty()) {
                elements.add(ParsedDocumentElement.builder()
                        .order(0)
                        .type("PARAGRAPH")
                        .text(plainText)
                        .build());
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("providerStatus", status);
            if (root.has("processing_time")) {
                metadata.put("processingTimeSeconds", root.path("processing_time").asDouble());
            }
            if (root.has("timings")) {
                metadata.put("timings", objectMapper.convertValue(root.path("timings"), Map.class));
            }
            metadata.put("elementCount", elements.size());

            return DocumentParseResult.builder()
                    .providerType(DocumentProviderType.DOCLING)
                    .format(request.getFormat())
                    .providerVersion(properties.getExpectedVersion())
                    .normalizedText(plainText)
                    .markdown(markdown)
                    .elements(elements)
                    .warnings(extractWarnings(root.path("errors")))
                    .metadata(metadata)
                    .build();
        } catch (DocumentParseException e) {
            throw e;
        } catch (ResponseTooLargeIOException e) {
            throw new DocumentParseException(DocumentParseErrorCode.DOCLING_RESPONSE_TOO_LARGE,
                    "Docling 响应超过允许大小: " + properties.getMaxResponseBytes(), e);
        } catch (IOException e) {
            throw new DocumentParseException(DocumentParseErrorCode.DOCLING_RESPONSE_INVALID,
                    "无法解析 Docling 响应", e);
        }
    }

    private List<ParsedDocumentElement> extractElements(JsonNode document) {
        Map<String, JsonNode> nodesByReference = new LinkedHashMap<>();
        collectReferenceNodes(document, nodesByReference, new HashSet<>());

        LinkedHashMap<String, ElementCandidate> candidates = new LinkedHashMap<>();
        collectCandidates(document.path("texts"), "PARAGRAPH", candidates);
        collectCandidates(document.path("tables"), "TABLE", candidates);
        collectCandidates(document.path("pictures"), "PICTURE", candidates);

        List<ElementCandidate> ordered = new ArrayList<>();
        Set<String> visitedReferences = new LinkedHashSet<>();
        appendDocumentOrder(document.path("body"), nodesByReference, candidates, ordered, visitedReferences, 0);
        // Some exporters retain captions or notes under furniture; append only
        // elements not already present in the canonical body reading order.
        for (ElementCandidate candidate : candidates.values()) {
            if (visitedReferences.add(candidate.reference())) {
                ordered.add(candidate);
            }
        }

        List<ParsedDocumentElement> elements = new ArrayList<>();
        String currentSection = null;
        for (ElementCandidate candidate : ordered) {
            String text = elementText(candidate.item(), candidate.defaultType());
            if (text.isBlank()) {
                continue;
            }
            String type = elementType(candidate.defaultType(), textValue(candidate.item().get("label")));
            if ("HEADING".equals(type)) {
                currentSection = compactSection(text);
            }
            elements.add(ParsedDocumentElement.builder()
                    .order(elements.size())
                    .type(type)
                    .text(text)
                    .sectionPath(currentSection)
                    .sourceLocator(sourceLocator(candidate.item()))
                    .metadata(elementMetadata(candidate.item()))
                    .build());
        }
        return elements;
    }

    private static void collectCandidates(JsonNode nodes, String defaultType,
                                          Map<String, ElementCandidate> target) {
        if (!nodes.isArray()) {
            return;
        }
        int index = 0;
        for (JsonNode item : nodes) {
            String reference = firstNonBlank(textValue(item.get("self_ref")), defaultType + ":" + index++);
            target.putIfAbsent(reference, new ElementCandidate(reference, item, defaultType));
        }
    }

    private static void collectReferenceNodes(JsonNode node, Map<String, JsonNode> target, Set<JsonNode> seen) {
        if (node == null || node.isMissingNode() || !seen.add(node)) {
            return;
        }
        if (node.isObject()) {
            String selfReference = textValue(node.get("self_ref"));
            if (!selfReference.isBlank()) {
                target.putIfAbsent(selfReference, node);
            }
            node.elements().forEachRemaining(child -> collectReferenceNodes(child, target, seen));
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectReferenceNodes(child, target, seen));
        }
    }

    private static void appendDocumentOrder(JsonNode node, Map<String, JsonNode> nodesByReference,
                                            Map<String, ElementCandidate> candidates,
                                            List<ElementCandidate> ordered, Set<String> visitedReferences, int depth) {
        if (node == null || node.isMissingNode() || depth > 64) {
            return;
        }
        String reference = firstNonBlank(textValue(node.get("$ref")), textValue(node.get("self_ref")));
        JsonNode resolved = !reference.isBlank() ? nodesByReference.get(reference) : node;
        if (resolved == null) {
            return;
        }
        String resolvedReference = firstNonBlank(textValue(resolved.get("self_ref")), reference);
        ElementCandidate candidate = candidates.get(resolvedReference);
        if (candidate != null) {
            if (visitedReferences.add(candidate.reference())) {
                ordered.add(candidate);
            }
            return;
        }
        JsonNode children = resolved.path("children");
        if (children.isArray()) {
            for (JsonNode child : children) {
                appendDocumentOrder(child, nodesByReference, candidates, ordered, visitedReferences, depth + 1);
            }
        }
    }

    private static String compactSection(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1_000 ? normalized : normalized.substring(0, 1_000);
    }

    private static String indexableMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        // A pure Docling image placeholder is not useful retrieval content.
        // Do not silently index it when OCR did not recognize any text.
        return markdown.replaceAll("(?is)<!--\\s*image\\s*-->", "").trim();
    }

    private String elementText(JsonNode item, String defaultType) {
        String direct = firstNonBlank(textValue(item.get("text")), textValue(item.get("orig")),
                textValue(item.get("caption_text")), textValue(item.get("content")));
        if (!direct.isBlank()) {
            return direct;
        }
        if ("TABLE".equals(defaultType)) {
            List<String> cells = new ArrayList<>();
            collectNamedText(item.path("data"), cells, 0);
            return String.join(" | ", cells);
        }
        return "";
    }

    private void collectNamedText(JsonNode node, List<String> output, int depth) {
        if (node == null || node.isMissingNode() || depth > 16 || output.size() >= 2_000) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectNamedText(child, output, depth + 1);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey().toLowerCase(Locale.ROOT);
            JsonNode value = field.getValue();
            if (("text".equals(name) || "content".equals(name) || "orig".equals(name)) && value.isTextual()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    output.add(text);
                }
            } else {
                collectNamedText(value, output, depth + 1);
            }
        }
    }

    private Map<String, Object> elementMetadata(JsonNode item) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String selfRef = textValue(item.get("self_ref"));
        if (!selfRef.isBlank()) {
            metadata.put("selfRef", selfRef);
        }
        String label = textValue(item.get("label"));
        if (!label.isBlank()) {
            metadata.put("label", label);
        }
        return metadata;
    }

    private DocumentSourceLocator sourceLocator(JsonNode item) {
        JsonNode provenance = item.path("prov");
        if (provenance.isArray() && !provenance.isEmpty()) {
            provenance = provenance.get(0);
        }
        if (!provenance.isObject()) {
            return DocumentSourceLocator.builder()
                    .providerReference(firstNonBlank(textValue(item.get("self_ref")), textValue(item.get("parent"))))
                    .build();
        }
        try {
            return DocumentSourceLocator.builder()
                    .pageStart(provenance.has("page_no") ? provenance.path("page_no").asInt() : null)
                    .pageEnd(provenance.has("page_no") ? provenance.path("page_no").asInt() : null)
                    .boundingBoxJson(provenance.has("bbox") ? objectMapper.writeValueAsString(provenance.path("bbox")) : null)
                    .providerReference(firstNonBlank(textValue(item.get("self_ref")), textValue(item.get("parent"))))
                    .build();
        } catch (IOException e) {
            throw new DocumentParseException(DocumentParseErrorCode.DOCLING_RESPONSE_INVALID,
                    "无法读取 Docling 来源定位", e);
        }
    }

    private List<DocumentParseWarning> extractWarnings(JsonNode errors) {
        List<DocumentParseWarning> warnings = new ArrayList<>();
        if (!errors.isArray()) {
            return warnings;
        }
        for (JsonNode error : errors) {
            warnings.add(DocumentParseWarning.builder()
                    .code(firstNonBlank(textValue(error.get("code")), "DOCLING_WARNING"))
                    .message(firstNonBlank(textValue(error.get("message")), error.asText()))
                    .build());
        }
        return warnings;
    }

    private static String elementType(String defaultType, String label) {
        String normalized = label == null ? "" : label.toUpperCase(Locale.ROOT);
        if (normalized.contains("SECTION_HEADER") || normalized.contains("TITLE")) {
            return "HEADING";
        }
        if (normalized.contains("LIST_ITEM")) {
            return "LIST_ITEM";
        }
        if (normalized.contains("TABLE")) {
            return "TABLE";
        }
        return defaultType;
    }

    private record ElementCandidate(String reference, JsonNode item, String defaultType) {
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private static String trimTrailingSlash(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String safeHeaderFileName(String fileName) {
        return (fileName == null ? "document" : fileName)
                .replace('\\', '_')
                .replace('"', '_')
                .replace('\r', '_')
                .replace('\n', '_');
    }

    private static String safeHeaderValue(String value) {
        return value.replace('\r', '_').replace('\n', '_');
    }

    private static String requestId(DocumentParseRequest request) {
        Object importId = request.getOptions() == null ? null : request.getOptions().get("importId");
        return importId == null ? UUID.randomUUID().toString() : String.valueOf(importId);
    }

    private static String doclingFormat(DocumentFormat format) {
        return switch (format) {
            case DOC -> "doc";
            case DOCX -> "docx";
            case PDF -> "pdf";
            case PPTX -> "pptx";
            case XLSX -> "xlsx";
            case PNG, JPEG, TIFF, BMP, WEBP -> "image";
            default -> throw new DocumentParseException(DocumentParseErrorCode.INTERNAL_ERROR,
                    "Docling 不应处理格式: " + format);
        };
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        private LimitedInputStream(InputStream input, long maximumBytes) {
            super(input);
            this.remaining = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return readPastLimit();
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return readPastLimit();
            }
            int allowed = (int) Math.min((long) length, remaining);
            int count = super.read(bytes, offset, allowed);
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }

        private int readPastLimit() throws IOException {
            if (super.read() < 0) {
                return -1;
            }
            throw new ResponseTooLargeIOException();
        }
    }

    private static final class ResponseTooLargeIOException extends IOException {
    }
}
