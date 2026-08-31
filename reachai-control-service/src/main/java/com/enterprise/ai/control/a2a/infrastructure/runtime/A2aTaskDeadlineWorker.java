package com.enterprise.ai.control.a2a.infrastructure.runtime;

import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository;
import com.enterprise.ai.control.a2a.application.task.A2aTaskDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** Converts overdue non-terminal Tasks to a truthful FAILED projection and requests Runtime cleanup. */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reachai.a2a-hub", name = "enabled", havingValue = "true")
public class A2aTaskDeadlineWorker {

    private final A2aTaskRepository repository;
    private final A2aTaskDispatchService tasks;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${reachai.a2a-hub.deadline-scan-delay:5s}")
    public void expireDueTasks() {
        for (String executionId : repository.findDueExecutionIds(LocalDateTime.now(clock), 100)) {
            try {
                tasks.expireTask(executionId);
            } catch (RuntimeException failure) {
                log.warn("[A2ADeadline] could not expire {}: {}", executionId, safe(failure.getMessage()));
            }
        }
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "deadline projection failed";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
}
