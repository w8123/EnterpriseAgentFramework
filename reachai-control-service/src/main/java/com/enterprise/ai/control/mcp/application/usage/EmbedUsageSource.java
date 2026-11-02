package com.enterprise.ai.control.mcp.application.usage;

import java.time.LocalDateTime;
import java.util.List;

/** Per-person counts of chats in the business system's embedded AI panel (owned by Runtime). */
public interface EmbedUsageSource {

    List<EmbedUserRow> embedUsers(String projectCode, int days);

    record EmbedUserRow(String appId, String projectCode, String externalUserId, long runs, long sessions,
                        long failed, LocalDateTime lastAt) {
    }
}
