package com.enterprise.ai.runtime.agent;

import java.time.LocalDateTime;

/** 查询已启用 Agent 的精确发布配置；不跟随当前配置指针，也不返回可变实体。 */
public interface RuntimeAgentPublishedConfigQuery {

    Target resolve(String agentId, Long versionId);

    record Target(String agentId, Long projectId, String projectCode, String keySlug, String name,
                  Long versionId, Integer versionNo, String runtimeType, LocalDateTime publishedAt) {
    }

    enum Reason { AGENT_UNAVAILABLE, VERSION_INVALID }

    final class LookupFailure extends IllegalStateException {
        private final Reason reason;

        public LookupFailure(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
