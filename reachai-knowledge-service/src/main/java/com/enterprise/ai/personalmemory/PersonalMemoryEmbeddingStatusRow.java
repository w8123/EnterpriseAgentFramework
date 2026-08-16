package com.enterprise.ai.personalmemory;

import lombok.Data;

/** Aggregate-only projection status; contains no tenant, owner, memory id, or text. */
@Data
public class PersonalMemoryEmbeddingStatusRow {
    private Long activeCount;
    private Long readyCount;
    private Long deadCount;
    private Long oldestPendingSeconds;
    private Long unsafeDeletedVectorCount;
}
