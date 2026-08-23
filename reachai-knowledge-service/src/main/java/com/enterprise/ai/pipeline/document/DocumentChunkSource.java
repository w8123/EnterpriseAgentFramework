package com.enterprise.ai.pipeline.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Per-chunk provenance retained from the structured provider result. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunkSource {

    private String elementType;
    private String sectionPath;
    private DocumentSourceLocator sourceLocator;
}
