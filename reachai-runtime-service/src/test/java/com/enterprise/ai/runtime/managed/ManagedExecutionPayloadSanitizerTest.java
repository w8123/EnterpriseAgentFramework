package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.managed.ManagedExecutionPayloadSanitizer.SanitizedEvent;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerEventV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedExecutionPayloadSanitizerTest {

    private final ManagedExecutionPayloadSanitizer sanitizer = new ManagedExecutionPayloadSanitizer(
            new ObjectMapper(),
            ManagedExecutorPropertiesTest.properties(true, "*", "ANALYZE_READONLY", 1));

    @Test
    void independentlyRedactsSecretsAndExternalPathsBeforePersistence() {
        Instant now = Instant.now();
        WorkerEventV1 input = event(
                "COMMAND_COMPLETED",
                "RUNNING",
                "command C:\\outside\\secret.txt",
                Map.of(
                        "authorization", "Bearer abcdefghijklmnop",
                        "stdout", "password=super-secret /var/lib/private/file"));

        SanitizedEvent sanitized = sanitizer.sanitize("mex_event_1", input, now);

        assertThat(sanitized.message()).doesNotContain("C:\\outside").contains("<external-path>");
        assertThat(sanitized.dataJson())
                .contains("<redacted>")
                .doesNotContain("abcdefghijklmnop")
                .doesNotContain("super-secret")
                .doesNotContain("/var/lib/private/file");
        assertThat(sanitized.payloadSha256()).matches("[a-f0-9]{64}");
    }

    @Test
    void rejectsReasoningDeltaAndNonDeterministicEventIds() {
        assertThatThrownBy(() -> sanitizer.sanitize(
                "mex_event_1",
                event("item/reasoning/textDelta", "RUNNING", "hidden", Map.of()),
                Instant.now()))
                .isInstanceOf(ManagedExecutionException.class)
                .hasMessageContaining("event type");

        WorkerEventV1 mismatched = new WorkerEventV1(
                "reachai.managed-execution.event.v1",
                "mex_event_1",
                1,
                "different-id",
                Instant.now(),
                "TURN_STARTED",
                "RUNNING",
                "OPERATOR",
                "DURABLE",
                "started",
                Map.of());
        assertThatThrownBy(() -> sanitizer.sanitize("mex_event_1", mismatched, Instant.now()))
                .isInstanceOf(ManagedExecutionException.class)
                .hasMessageContaining("eventId");
    }

    private WorkerEventV1 event(String type, String phase, String message, Map<String, Object> data) {
        return new WorkerEventV1(
                "reachai.managed-execution.event.v1",
                "mex_event_1",
                1,
                "mex_event_1:1",
                Instant.now(),
                type,
                phase,
                "OPERATOR",
                "DURABLE",
                message,
                data);
    }
}
