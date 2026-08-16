package com.enterprise.ai.personalmemory;

import lombok.Data;

import java.time.LocalDateTime;

/** Owner-scoped aggregate proof. It intentionally contains no raw owner or memory identity. */
@Data
public class PersonalMemoryOwnerProjectionStatusRow {
    private Long totalCount;
    private Long activeCount;
    private Long deletedCount;
    private Long unsafeDeletedVectorCount;
    private Long maxSourceVersion;
    private LocalDateTime latestUpdatedAt;
}
