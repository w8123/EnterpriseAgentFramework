package com.enterprise.ai.control.platform.sso;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Bounded, process-local password counters. Admission reserves both keys before any password is checked. */
final class PasswordLoginAttempts {
    private final int capacity;
    private final int maxFailures;
    private final Duration window;
    private final Map<String, Counter> counters = new HashMap<>();

    PasswordLoginAttempts(int capacity, int maxFailures, Duration window) {
        this.capacity = capacity;
        this.maxFailures = maxFailures;
        this.window = window;
    }

    synchronized Permit acquire(String accountKey, String ipKey, Instant now) {
        Counter account = current(accountKey, now);
        Counter ip = current(ipKey, now);
        if (locked(account) || locked(ip)) {
            throw new BusinessSsoException("试错次数太多了，请 10 分钟后再试");
        }
        int needed = (account == null ? 1 : 0) + (ip == null ? 1 : 0);
        if (counters.size() + needed > capacity) {
            counters.values().removeIf(counter -> counter.pending == 0 && !now.isBefore(counter.resetAt));
            if (counters.size() + needed > capacity) {
                throw new BusinessSsoException("登录的人太多了，请稍后再试");
            }
        }
        if (account == null) {
            account = new Counter(now.plus(window));
            counters.put(accountKey, account);
        }
        if (ip == null) {
            ip = new Counter(now.plus(window));
            counters.put(ipKey, ip);
        }
        account.pending++;
        ip.pending++;
        return new Permit(accountKey, ipKey, account, ip);
    }

    synchronized void finish(Permit permit, boolean failed, boolean succeeded, Instant now) {
        finish(permit.accountKey(), permit.account(), failed, succeeded, now);
        // A successful login must not erase earlier password spraying from the same address.
        finish(permit.ipKey(), permit.ip(), failed, false, now);
    }

    private void finish(String key, Counter counter, boolean failed, boolean succeeded, Instant now) {
        counter.pending--;
        if (failed) {
            if (!now.isBefore(counter.resetAt)) {
                counter.failures = 0;
                counter.resetAt = now.plus(window);
            }
            counter.failures++;
        } else if (succeeded) {
            counter.failures = 0;
        }
        if (counter.failures == 0 && counter.pending == 0) counters.remove(key, counter);
    }

    private Counter current(String key, Instant now) {
        Counter counter = counters.get(key);
        if (counter != null && !now.isBefore(counter.resetAt)) {
            if (counter.pending == 0) {
                counters.remove(key);
                return null;
            }
            counter.failures = 0;
            counter.resetAt = now.plus(window);
        }
        return counter;
    }

    private boolean locked(Counter counter) {
        return counter != null && counter.failures + counter.pending >= maxFailures;
    }

    record Permit(String accountKey, String ipKey, Counter account, Counter ip) {
    }

    private static final class Counter {
        private int failures;
        private int pending;
        private Instant resetAt;

        private Counter(Instant resetAt) {
            this.resetAt = resetAt;
        }
    }
}
