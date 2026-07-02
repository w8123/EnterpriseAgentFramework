package com.enterprise.ai.runtime.execution;

import java.util.List;
import java.util.Map;

public record RuntimeGraphSpecExecutionResult(boolean success,
                                              String code,
                                              String answer,
                                              String nodeId,
                                              String nodeType,
                                              List<Map<String, Object>> steps,
                                              Map<String, Object> metadata) {
}
