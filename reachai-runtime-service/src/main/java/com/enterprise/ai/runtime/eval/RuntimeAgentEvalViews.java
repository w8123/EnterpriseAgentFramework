package com.enterprise.ai.runtime.eval;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

record RuntimeAgentEvalDatasetView(Long id,
                                   String agentId,
                                   String agentName,
                                   String name,
                                   String description,
                                   String source,
                                   Integer caseCount,
                                   LocalDateTime createTime,
                                   LocalDateTime updateTime) {
}

record RuntimeAgentEvalCaseView(Long id,
                                Long datasetId,
                                String caseNo,
                                String message,
                                String inputParamsJson,
                                String expectedJson,
                                String judgeConfigJson,
                                String tags,
                                Boolean enabled) {
}

record RuntimeAgentEvalRunDetail(Long id,
                                 Long datasetId,
                                 String agentId,
                                 String agentName,
                                 String runName,
                                 Integer repeatCount,
                                 String status,
                                 String summaryJson,
                                 String suggestionJson,
                                 LocalDateTime startedAt,
                                 LocalDateTime finishedAt) {
}

record RuntimeAgentEvalCaseResultView(Long id,
                                      Long runId,
                                      Long datasetId,
                                      Long caseId,
                                      String caseNo,
                                      Integer roundNo,
                                      String status,
                                      Boolean runtimeSuccess,
                                      Boolean assertionPassed,
                                      Double semanticScore,
                                      Double score,
                                      Integer elapsedMs,
                                      String answer,
                                      String traceId,
                                      String errorCode,
                                      String errorMessage) {
}

record RuntimeAgentEvalRunView(RuntimeAgentEvalRunDetail run,
                               Map<String, Object> summary,
                               Map<String, Object> suggestion,
                               List<RuntimeAgentEvalCaseResultView> results) {
}
