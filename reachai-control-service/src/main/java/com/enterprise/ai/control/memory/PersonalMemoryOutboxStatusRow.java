package com.enterprise.ai.control.memory;

import lombok.Data;

/** Aggregate-only outbox state; contains no owner, event id, payload, or memory id. */
@Data
public class PersonalMemoryOutboxStatusRow {
    private Long backlogCount;
    private Long deadCount;
    private Long oldestUnpublishedSeconds;
}
