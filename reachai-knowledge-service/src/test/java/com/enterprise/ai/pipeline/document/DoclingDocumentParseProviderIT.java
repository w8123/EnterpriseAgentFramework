package com.enterprise.ai.pipeline.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in acceptance against an actual Docling Serve instance. */
@EnabledIfSystemProperty(named = "reachai.live.docling.url", matches = ".+")
class DoclingDocumentParseProviderIT {

    @Test
    void parsesTheRepositoryChineseScreenshotThroughTheRealService() throws Exception {
        Path sample = findSample();
        DoclingProperties properties = new DoclingProperties();
        properties.setBaseUrl(System.getProperty("reachai.live.docling.url"));
        properties.setApiKey(System.getProperty("reachai.live.docling.apiKey"));
        properties.setExpectedVersion("1.30.0");
        properties.setRequestTimeoutMs(180_000);
        properties.setDocumentTimeoutSeconds(180);
        properties.setMaxConcurrentRequests(1);
        DocumentParseProperties documentProperties = new DocumentParseProperties();
        documentProperties.setMaxFileBytes(52_428_800L);
        DoclingDocumentParseProvider provider = new DoclingDocumentParseProvider(
                new ObjectMapper(), properties, documentProperties);

        DocumentParseResult result = provider.parse(DocumentParseRequest.builder()
                .fileName(sample.getFileName().toString())
                .declaredContentType("image/png")
                .fileSize(Files.size(sample))
                .format(DocumentFormat.PNG)
                .content(() -> Files.newInputStream(sample))
                .build());

        assertEquals(DocumentProviderType.DOCLING, result.getProviderType());
        assertTrue(result.getNormalizedText().length() > 100);
        assertFalse(result.getElements().isEmpty());
        System.out.printf("LIVE_DOCLING textChars=%d elements=%d providerVersion=%s%n",
                result.getNormalizedText().length(), result.getElements().size(), result.getProviderVersion());
    }

    private static Path findSample() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 4 && current != null; depth++, current = current.getParent()) {
            Path candidate = current.resolve("docs")
                    .resolve("\u7cfb\u7edf\u622a\u56fe")
                    .resolve("\u9996\u9875.png");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Docling live acceptance sample was not found");
    }
}
