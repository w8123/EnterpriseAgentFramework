package com.enterprise.ai.runtime.managed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedWorkerTokenServiceTest {

    private final ManagedWorkerTokenService tokens = new ManagedWorkerTokenService();

    @Test
    void generatesExecutionScopedHighEntropyTokensAndStoresOnlyDigests() {
        String first = tokens.generate();
        String second = tokens.generate();

        assertThat(first).hasSizeGreaterThanOrEqualTo(40).isNotEqualTo(second);
        assertThat(tokens.digest(first)).matches("[a-f0-9]{64}").isNotEqualTo(first);
        assertThat(tokens.matches(first, tokens.digest(first))).isTrue();
        assertThat(tokens.matches(second, tokens.digest(first))).isFalse();
        assertThat(tokens.matches("short", tokens.digest(first))).isFalse();
    }
}
