package com.enterprise.ai.pipeline.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoclingDocumentParseProviderTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>("");
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<String> contentLength = new AtomicReference<>();
    private final AtomicReference<String> requestProtocol = new AtomicReference<>();
    private final AtomicInteger responseDelayMillis = new AtomicInteger();
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final AtomicInteger maximumActiveRequests = new AtomicInteger();
    private ExecutorService serverExecutor;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert/file", exchange -> {
            int active = activeRequests.incrementAndGet();
            maximumActiveRequests.accumulateAndGet(active, Math::max);
            try {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                apiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
                contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
                requestProtocol.set(exchange.getProtocol());
                if (responseDelayMillis.get() > 0) {
                    Thread.sleep(responseDelayMillis.get());
                }
                byte[] response = responseBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                activeRequests.decrementAndGet();
                exchange.close();
            }
        });
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void sendsPinnedV1MultipartContractAndPreservesDoclingBodyOrder() {
        responseBody.set(successResponse());
        AtomicInteger sourceOpenCount = new AtomicInteger();
        DocumentParseResult result = provider().parse(pdfRequest(sourceOpenCount));

        assertEquals(DocumentProviderType.DOCLING, result.getProviderType());
        assertEquals(3, result.getElements().size());
        assertEquals("HEADING", result.getElements().get(0).getType());
        assertEquals("Heading", result.getElements().get(0).getText());
        assertEquals("TABLE", result.getElements().get(1).getType());
        assertEquals("Cell A", result.getElements().get(1).getText());
        assertEquals("After table", result.getElements().get(2).getText());
        assertEquals("Heading", result.getElements().get(2).getSectionPath());
        assertEquals(1, result.getElements().get(1).getSourceLocator().getPageStart());
        assertEquals("unit-test-key", apiKey.get());
        assertEquals(1, sourceOpenCount.get());
        assertEquals("HTTP/1.1", requestProtocol.get());

        String body = requestBody.get();
        assertEquals(body.getBytes(StandardCharsets.ISO_8859_1).length,
                Integer.parseInt(contentLength.get()));
        assertTrue(body.contains("name=\"files\"; filename=\"contract.pdf\""));
        assertTrue(body.contains("name=\"from_formats\"\r\n\r\npdf"));
        assertTrue(body.contains("name=\"to_formats\"\r\n\r\njson"));
        assertTrue(body.contains("name=\"to_formats\"\r\n\r\nmd"));
        assertTrue(body.contains("name=\"to_formats\"\r\n\r\ntext"));
        assertTrue(body.contains("name=\"do_table_structure\"\r\n\r\ntrue"));
        assertTrue(body.contains("name=\"do_pdf_heading_hierarchy\"\r\n\r\ntrue"));
    }

    @Test
    void rejectsImagePlaceholderWhenOcrProducedNoIndexableText() {
        responseBody.set("""
                {"status":"success","document":{"md_content":"<!-- image -->","text_content":"",
                "json_content":{"body":{"self_ref":"#/body","children":[{"$ref":"#/pictures/0"}]},
                "texts":[],"tables":[],"pictures":[{"self_ref":"#/pictures/0","label":"picture","prov":[{"page_no":1}]}]}}}
                """);

        DocumentParseException error = assertThrows(DocumentParseException.class,
                () -> provider().parse(pdfRequest()));

        assertEquals(DocumentParseErrorCode.DOCLING_EMPTY_OUTPUT, error.getErrorCode());
    }

    @Test
    void rejectsAResponseBeyondTheConfiguredStreamingLimit() {
        responseBody.set(successResponse());
        DoclingProperties properties = properties();
        properties.setMaxResponseBytes(64);

        DocumentParseException error = assertThrows(DocumentParseException.class,
                () -> provider(properties).parse(pdfRequest()));

        assertEquals(DocumentParseErrorCode.DOCLING_RESPONSE_TOO_LARGE, error.getErrorCode());
    }

    @Test
    void limitsConcurrentResponseNormalization() throws Exception {
        responseBody.set(successResponse());
        responseDelayMillis.set(100);
        DoclingProperties properties = properties();
        properties.setMaxConcurrentRequests(1);
        properties.setConcurrencyWaitTimeoutMs(5_000);
        DoclingDocumentParseProvider provider = provider(properties);
        ExecutorService callers = Executors.newFixedThreadPool(3);
        try {
            List<Future<DocumentParseResult>> results = List.of(
                    callers.submit(() -> provider.parse(pdfRequest())),
                    callers.submit(() -> provider.parse(pdfRequest())),
                    callers.submit(() -> provider.parse(pdfRequest())));
            for (Future<DocumentParseResult> result : results) {
                assertEquals(DocumentProviderType.DOCLING, result.get(5, TimeUnit.SECONDS).getProviderType());
            }
        } finally {
            callers.shutdownNow();
        }

        assertEquals(1, maximumActiveRequests.get());
    }

    private DoclingDocumentParseProvider provider() {
        return provider(properties());
    }

    private DoclingDocumentParseProvider provider(DoclingProperties properties) {
        return new DoclingDocumentParseProvider(new ObjectMapper(), properties, new DocumentParseProperties());
    }

    private DoclingProperties properties() {
        DoclingProperties properties = new DoclingProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("unit-test-key");
        properties.setRequestTimeoutMs(5_000);
        properties.setDocumentTimeoutSeconds(10);
        return properties;
    }

    private static DocumentParseRequest pdfRequest() {
        return pdfRequest(new AtomicInteger());
    }

    private static DocumentParseRequest pdfRequest(AtomicInteger sourceOpenCount) {
        byte[] content = "%PDF-1.7 test".getBytes(StandardCharsets.US_ASCII);
        return DocumentParseRequest.builder()
                .fileName("contract.pdf")
                .declaredContentType("application/pdf")
                .fileSize(content.length)
                .format(DocumentFormat.PDF)
                .content(() -> {
                    sourceOpenCount.incrementAndGet();
                    return new ByteArrayInputStream(content);
                })
                .build();
    }

    private static String successResponse() {
        return """
                {"status":"success","processing_time":0.12,"document":{
                "md_content":"# Heading\\n\\nCell A\\n\\nAfter table",
                "text_content":"Heading\\nCell A\\nAfter table",
                "json_content":{
                  "body":{"self_ref":"#/body","children":[{"$ref":"#/texts/1"},{"$ref":"#/tables/0"},{"$ref":"#/texts/0"}]},
                  "texts":[
                    {"self_ref":"#/texts/0","label":"text","orig":"After table","prov":[{"page_no":1}]},
                    {"self_ref":"#/texts/1","label":"section_header","orig":"Heading","prov":[{"page_no":1}]}
                  ],
                  "tables":[{"self_ref":"#/tables/0","label":"table","data":{"table_cells":[{"text":"Cell A"}]},"prov":[{"page_no":1}]}],
                  "pictures":[]
                }
                }}
                """;
    }
}
