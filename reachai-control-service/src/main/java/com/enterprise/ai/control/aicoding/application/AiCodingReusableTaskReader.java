package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.application.port.AiCodingReusableTaskQuery;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiCodingReusableTaskReader implements AiCodingReusableTaskQuery {
    private static final List<String> REUSABLE_STATUSES = List.of(
            "READY", "RUNNING", "WAITING_USER", "RESULT_SUBMITTED", "RESULT_APPLIED", "ACCEPTANCE_READY");
    private final AiCodingTaskMapper tasks;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> latestForPrimaryTarget(String taskKind, String executorProvider, String targetType, String targetKey) {
        if (!StringUtils.hasText(taskKind) || !StringUtils.hasText(executorProvider)
                || !StringUtils.hasText(targetType) || !StringUtils.hasText(targetKey)) {
            throw new IllegalArgumentException("task reuse scope is required");
        }
        return Optional.ofNullable(tasks.findLatestReusableTaskId(taskKind,
                executorProvider.trim().toUpperCase(Locale.ROOT), targetType, targetKey, REUSABLE_STATUSES));
    }
}
