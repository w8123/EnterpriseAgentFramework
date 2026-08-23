package com.enterprise.ai.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Immutable authorization target exposed only through the signed Control BFF. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentImportResourceScope {

    private String workspaceId;
    private String projectCode;
    private String scope;
}
