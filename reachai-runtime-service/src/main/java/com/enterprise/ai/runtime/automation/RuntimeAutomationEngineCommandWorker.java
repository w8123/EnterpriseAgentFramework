package com.enterprise.ai.runtime.automation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "reachai.runtime.automation.enabled", havingValue = "true")
final class RuntimeAutomationEngineCommandWorker {

    private final RuntimeAutomationEngineCommandMapper commandMapper;
    private final RuntimeAutomationMapper automationMapper;
    private final RuntimeAutomationVersionMapper versionMapper;
    private final RuntimeAutomationEnginePort engine;
    private final String workerId = "automation-clock-command-" + ProcessHandle.current().pid()
            + "-" + UUID.randomUUID().toString().substring(0, 8);

    @Scheduled(
            initialDelayString = "${reachai.runtime.automation.command-initial-delay-ms:3000}",
            fixedDelayString = "${reachai.runtime.automation.command-poll-delay-ms:1000}")
    void processCommands() {
        for (int processed = 0; processed < 20; processed++) {
            RuntimeAutomationEngineCommandEntity command = claim();
            if (command == null) return;
            process(command);
        }
    }

    private RuntimeAutomationEngineCommandEntity claim() {
        for (int collision = 0; collision < 10; collision++) {
            Long id = commandMapper.findCandidateId();
            if (id == null) return null;
            String token = UUID.randomUUID().toString();
            if (commandMapper.claim(id, workerId, token,
                    LocalDateTime.now(java.time.Clock.systemUTC()).plusMinutes(2)) == 1) {
                RuntimeAutomationEngineCommandEntity command = commandMapper.selectById(id);
                if (command != null && token.equals(command.getLeaseToken())) return command;
            }
        }
        return null;
    }

    private void process(RuntimeAutomationEngineCommandEntity command) {
        try {
            RuntimeAutomationEntity automation = automationMapper.selectById(command.getAutomationId());
            RuntimeAutomationVersionEntity version = command.getAutomationVersionId() == null
                    ? null : versionMapper.selectById(command.getAutomationVersionId());
            if (automation == null) {
                complete(command);
                return;
            }
            if ("UPSERT".equals(command.getCommandType())) {
                if ("ACTIVE".equals(automation.getStatus()) && version != null
                        && version.getId().equals(automation.getCurrentVersionId())) {
                    engine.upsert(automation, version);
                }
            } else if (!"ACTIVE".equals(automation.getStatus())) {
                engine.cancel(automation.getAutomationKey());
            }
            complete(command);
        } catch (Exception failure) {
            int attempts = command.getAttemptCount() == null ? 1 : command.getAttemptCount();
            boolean exhausted = attempts >= 20;
            long delay = Math.min(300, 2L << Math.min(7, Math.max(0, attempts - 1)));
            commandMapper.release(command.getId(), command.getLeaseToken(), exhausted ? "DEAD" : "RETRY",
                    LocalDateTime.now(java.time.Clock.systemUTC()).plusSeconds(delay),
                    "AUTOMATION_ENGINE_SYNC_FAILED", safe(failure));
            log.warn("Automation engine command {} failed: {}", command.getId(), safe(failure));
        }
    }

    private void complete(RuntimeAutomationEngineCommandEntity command) {
        commandMapper.complete(command.getId(), command.getLeaseToken());
    }

    private String safe(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
