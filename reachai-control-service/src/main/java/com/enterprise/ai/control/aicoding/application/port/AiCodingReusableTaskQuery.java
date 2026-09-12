package com.enterprise.ai.control.aicoding.application.port;

import java.util.Optional;

/** AI Coding owns reuse eligibility and lookup; consumers receive a task identity, never persistence rows. */
public interface AiCodingReusableTaskQuery {
    Optional<String> latestForPrimaryTarget(String taskKind, String executorProvider, String targetType, String targetKey);
}
