package com.enterprise.ai.runtime.runops;

import java.util.Optional;

/** RunOps 所有的根运行事实；调用方不读取实体或业务输入快照。 */
public interface RuntimeRunQuery {

    Optional<Run> findByTraceId(String traceId);

    record Run(String traceId, String projectCode, String sessionId,
               String pageInstanceId, String entryType, String status) {
    }
}
