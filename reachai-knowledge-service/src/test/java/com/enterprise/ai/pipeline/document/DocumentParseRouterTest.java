package com.enterprise.ai.pipeline.document;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentParseRouterTest {

    @Test
    void routesUtf8TextToJavaFastOnly() {
        DocumentParseProperties properties = new DocumentParseProperties();
        JavaFastDocumentParseProvider javaFast = new JavaFastDocumentParseProvider(properties);
        RecordingProvider docling = new RecordingProvider(DocumentProviderType.DOCLING);
        DocumentParseRouter router = router(properties, javaFast, docling);

        DocumentParseResult result = router.parse(request("notes.md", "text/markdown",
                "# 标题\n正文".getBytes(StandardCharsets.UTF_8)));

        assertEquals(DocumentProviderType.JAVA_FAST, result.getProviderType());
        assertEquals(DocumentFormat.MARKDOWN, result.getFormat());
        assertEquals(0, docling.calls.get());
    }

    @Test
    void routesPdfToDoclingAndNeverFallsBack() {
        DocumentParseProperties properties = new DocumentParseProperties();
        RecordingProvider javaFast = new RecordingProvider(DocumentProviderType.JAVA_FAST);
        RecordingProvider docling = new RecordingProvider(DocumentProviderType.DOCLING);
        DocumentParseRouter router = router(properties, javaFast, docling);

        DocumentParseResult result = router.parse(request("contract.pdf", "application/pdf",
                "%PDF-1.7\nplaceholder".getBytes()));

        assertEquals(DocumentProviderType.DOCLING, result.getProviderType());
        assertEquals(DocumentFormat.PDF, result.getFormat());
        assertEquals(1, docling.calls.get());
        assertEquals(0, javaFast.calls.get());
    }

    @Test
    void doclingFailureDoesNotRetryWithJavaFast() {
        DocumentParseProperties properties = new DocumentParseProperties();
        RecordingProvider javaFast = new RecordingProvider(DocumentProviderType.JAVA_FAST);
        RecordingProvider docling = new RecordingProvider(DocumentProviderType.DOCLING);
        docling.failure = new DocumentParseException(DocumentParseErrorCode.DOCLING_TIMEOUT, "timeout");
        DocumentParseRouter router = router(properties, javaFast, docling);

        DocumentParseException error = assertThrows(DocumentParseException.class,
                () -> router.parse(request("scan.pdf", "application/pdf", "%PDF-1.7".getBytes())));

        assertEquals(DocumentParseErrorCode.DOCLING_TIMEOUT, error.getErrorCode());
        assertEquals(1, docling.calls.get());
        assertEquals(0, javaFast.calls.get());
    }

    @Test
    void rejectsExtensionAndMagicMismatchBeforeProvider() {
        DocumentParseProperties properties = new DocumentParseProperties();
        RecordingProvider javaFast = new RecordingProvider(DocumentProviderType.JAVA_FAST);
        RecordingProvider docling = new RecordingProvider(DocumentProviderType.DOCLING);
        DocumentParseRouter router = router(properties, javaFast, docling);

        DocumentParseException error = assertThrows(DocumentParseException.class,
                () -> router.parse(request("not-a-pdf.pdf", "application/pdf", "plain text".getBytes())));

        assertEquals(DocumentParseErrorCode.FORMAT_MISMATCH, error.getErrorCode());
        assertEquals(0, docling.calls.get());
        assertEquals(0, javaFast.calls.get());
    }

    @Test
    void routesTheEntireExplicitFormatAllowListWithoutLegacyFallback() {
        DocumentParseProperties properties = new DocumentParseProperties();
        RecordingProvider javaFast = new RecordingProvider(DocumentProviderType.JAVA_FAST);
        RecordingProvider docling = new RecordingProvider(DocumentProviderType.DOCLING);
        DocumentParseRouter router = router(properties, javaFast, docling);

        for (Map.Entry<DocumentFormat, byte[]> entry : supportedHeaders().entrySet()) {
            DocumentFormat format = entry.getKey();
            DocumentParseResult result = router.parse(request("source." + format.getExtension(),
                    "application/octet-stream", entry.getValue()));
            assertEquals(format, result.getFormat(), format.name());
            assertEquals(format.getProviderType(), result.getProviderType(), format.name());
        }

        assertEquals(3, javaFast.calls.get());
        assertEquals(10, docling.calls.get());
    }

    private static DocumentParseRouter router(DocumentParseProperties properties, DocumentParseProvider... providers) {
        return new DocumentParseRouter(new DocumentFormatDetector(properties), List.of(providers));
    }

    private static DocumentParseRequest request(String name, String contentType, byte[] bytes) {
        return DocumentParseRequest.builder()
                .fileName(name)
                .declaredContentType(contentType)
                .fileSize(bytes.length)
                .content(() -> new ByteArrayInputStream(bytes))
                .build();
    }

    private static Map<DocumentFormat, byte[]> supportedHeaders() {
        return Map.ofEntries(
                Map.entry(DocumentFormat.TXT, "plain text".getBytes(StandardCharsets.UTF_8)),
                Map.entry(DocumentFormat.MARKDOWN, "# heading".getBytes(StandardCharsets.UTF_8)),
                Map.entry(DocumentFormat.CSV, "name,value".getBytes(StandardCharsets.UTF_8)),
                Map.entry(DocumentFormat.DOC, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}),
                Map.entry(DocumentFormat.DOCX, new byte[]{'P', 'K', 3, 4}),
                Map.entry(DocumentFormat.PDF, "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)),
                Map.entry(DocumentFormat.PPTX, new byte[]{'P', 'K', 3, 4}),
                Map.entry(DocumentFormat.XLSX, new byte[]{'P', 'K', 3, 4}),
                Map.entry(DocumentFormat.PNG, new byte[]{(byte) 0x89, 'P', 'N', 'G'}),
                Map.entry(DocumentFormat.JPEG, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
                Map.entry(DocumentFormat.TIFF, new byte[]{'I', 'I', 0x2A, 0x00}),
                Map.entry(DocumentFormat.BMP, new byte[]{'B', 'M'}),
                Map.entry(DocumentFormat.WEBP, new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}));
    }

    private static final class RecordingProvider implements DocumentParseProvider {

        private final DocumentProviderType type;
        private final AtomicInteger calls = new AtomicInteger();
        private DocumentParseException failure;

        private RecordingProvider(DocumentProviderType type) {
            this.type = type;
        }

        @Override
        public DocumentProviderType getProviderType() {
            return type;
        }

        @Override
        public DocumentParseResult parse(DocumentParseRequest request) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return DocumentParseResult.builder()
                    .providerType(type)
                    .providerVersion("test")
                    .normalizedText("parsed")
                    .elements(List.of(ParsedDocumentElement.builder().order(0).type("TEXT").text("parsed").build()))
                    .metadata(Map.of())
                    .build();
        }
    }
}
