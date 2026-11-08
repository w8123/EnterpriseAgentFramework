package com.enterprise.ai.control.aicoding.application;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Serializes onboarding admission across console and MCP instances until transaction completion. */
@Component
@RequiredArgsConstructor
public class ProjectOnboardingCreationLock {
    private final JdbcTemplate jdbc;
    private final Object resourceKey = new Object();

    @SuppressWarnings("unchecked")
    public void acquire(String projectCode) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("project onboarding requires a transaction");
        }
        String lockName;
        try {
            lockName = "reachai-onboard-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(projectCode.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))).substring(0, 40);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        Set<String> locks = (Set<String>) TransactionSynchronizationManager.getResource(resourceKey);
        if (locks != null && locks.contains(lockName)) return;
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 0)", Integer.class, lockName);
        if (!Integer.valueOf(1).equals(acquired)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "项目接入任务正在启动，请稍后查询任务状态");
        }
        if (locks == null) {
            locks = new LinkedHashSet<>();
            Set<String> heldLocks = locks;
            TransactionSynchronizationManager.bindResource(resourceKey, locks);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    try {
                        for (String held : heldLocks) jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, held);
                    } finally {
                        TransactionSynchronizationManager.unbindResourceIfPossible(resourceKey);
                    }
                }
            });
        }
        locks.add(lockName);
    }
}
