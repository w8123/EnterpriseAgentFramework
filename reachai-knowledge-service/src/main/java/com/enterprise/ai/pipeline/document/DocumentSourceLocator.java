package com.enterprise.ai.pipeline.document;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Format-neutral citation anchor.  Fields that are not applicable to a given
 * format stay null rather than inventing a page number.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSourceLocator {

    private Integer pageStart;
    private Integer pageEnd;
    private Integer slideNumber;
    private String sheetName;
    private String cellRange;
    private Integer lineStart;
    private Integer lineEnd;
    private String boundingBoxJson;
    private String providerReference;
}
