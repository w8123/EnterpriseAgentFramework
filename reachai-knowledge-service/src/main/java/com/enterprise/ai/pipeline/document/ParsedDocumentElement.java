package com.enterprise.ai.pipeline.document;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedDocumentElement {

    private int order;
    private String type;
    private String text;
    private String sectionPath;
    private DocumentSourceLocator sourceLocator;

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
