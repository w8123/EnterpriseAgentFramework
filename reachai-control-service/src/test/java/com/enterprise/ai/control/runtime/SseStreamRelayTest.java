package com.enterprise.ai.control.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseStreamRelayTest {

    @Test
    void forwardsHeartbeatCommentsAndFlushesWithoutEnteringFrameHandler() throws Exception {
        ByteArrayInputStream upstream = new ByteArrayInputStream(
                (": heartbeat\n\n"
                        + "event: message.delta\n"
                        + "data: {\"text\":\"hi\"}\n\n"
                        + ": heartbeat\n\n")
                        .getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream downstream = new ByteArrayOutputStream();
        List<String> handledEvents = new ArrayList<>();

        SseStreamRelay.relay(upstream, downstream, (eventName, data, out) -> {
            handledEvents.add(eventName);
            SseStreamRelay.writeFrame(out, eventName, data);
        });

        String relayed = downstream.toString(StandardCharsets.UTF_8);
        assertTrue(relayed.contains(": heartbeat"));
        assertTrue(relayed.contains("event: message.delta"));
        assertEquals(List.of("message.delta"), handledEvents,
                "heartbeat must not enter FrameHandler / business events");
    }

    @Test
    void heartbeatWriteFailurePropagatesAsIoException() {
        ByteArrayInputStream upstream = new ByteArrayInputStream(
                ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
        AtomicInteger writes = new AtomicInteger();
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                writes.incrementAndGet();
                throw new IOException("Broken pipe");
            }
        };

        IOException ex = assertThrows(IOException.class,
                () -> SseStreamRelay.relay(upstream, failing, SseStreamRelay.passthrough()));
        assertTrue(ex.getMessage().toLowerCase().contains("broken pipe"));
        assertTrue(writes.get() > 0);
    }
}
