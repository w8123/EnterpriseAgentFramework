package com.enterprise.ai.runtime.trace;

import java.util.List;
import java.util.Map;

/** Trace 所有的只读记录查询；不向调用方暴露持久化对象。 */
public interface RuntimeTraceRecordQuery {

    int MAX_BATCH_TRACE_IDS = 100;

    /** 分别按开始时间和创建时间排序，同一时间按记录 ID 排序。 */
    RuntimeTraceRecords findRecords(String traceId);

    /** 最多 100 个不同 Trace；忽略空 ID，保留请求顺序，无记录者返回空视图。 */
    Map<String, RuntimeTraceRecords> findRecordsByTraceIds(List<String> traceIds);

    /** 只查询当前 Trace 的 Span，保持时间和 ID 顺序，不加载 Tool 调用内容。 */
    List<RuntimeTraceRecords.Span> findSpans(String traceId);
}
