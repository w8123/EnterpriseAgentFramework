package com.enterprise.ai.pipeline.document;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseWarning {

    private String code;
    private String message;
    private DocumentSourceLocator sourceLocator;
}
