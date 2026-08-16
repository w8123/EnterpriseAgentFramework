package com.enterprise.ai.control.context;

import lombok.Data;

/** Correlation-scoped delivery proof. It contains no owner, memory id, event id, or payload. */
@Data
public class PersonalMemoryErasureOutboxStatusRow {
    private Long totalCount;
    private Long publishedCount;
    private Long supersededCount;
    private Long pendingCount;
    private Long deadCount;
    private Long otherCount;
    private Long maxSourceVersion;
}
