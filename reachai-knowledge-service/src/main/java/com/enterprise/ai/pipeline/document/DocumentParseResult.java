package com.enterprise.ai.pipeline.document;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseResult {

    private DocumentProviderType providerType;
    private DocumentFormat format;
    private String providerVersion;
    private String normalizedText;
    private String markdown;
    private String rawProviderJson;

    @Builder.Default
    private List<ParsedDocumentElement> elements = new ArrayList<>();

    @Builder.Default
    private List<DocumentParseWarning> warnings = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
