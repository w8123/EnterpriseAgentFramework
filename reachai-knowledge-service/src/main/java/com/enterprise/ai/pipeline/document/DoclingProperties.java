package com.enterprise.ai.pipeline.document;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Configuration for the internal Docling dependency, never a browser endpoint. */
@Data
@ConfigurationProperties(prefix = "reachai.knowledge.docling")
public class DoclingProperties {

    private boolean enabled = true;
    private String baseUrl = "http://localhost:5001";
    private String apiKey;
    private String expectedVersion = "v1.30.0";
    private int connectTimeoutMs = 5_000;
    private int requestTimeoutMs = 900_000;
    private int documentTimeoutSeconds = 900;
    private int maxConcurrentRequests = 1;
    private int concurrencyWaitTimeoutMs = 30_000;
    private long maxResponseBytes = 67_108_864L;
    private String ocrPreset;
    private List<String> ocrLanguages = new ArrayList<>(List.of("ch", "en"));
    private String tableMode = "accurate";
    private String imageExportMode = "placeholder";
    private boolean doOcr = true;
    private boolean forceOcr = false;
    private boolean doTableStructure = true;
    private boolean doPdfHeadingHierarchy = true;
}
