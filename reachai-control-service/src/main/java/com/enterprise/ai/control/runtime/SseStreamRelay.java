package com.enterprise.ai.control.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Parses and relays Server-Sent Event frames without buffering the full upstream response. */
public final class SseStreamRelay {

    private SseStreamRelay() {
    }

    public static void relay(InputStream upstream, OutputStream downstream, FrameHandler handler) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8))) {
            List<String> dataLines = new ArrayList<>();
            String eventName = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (eventName != null || !dataLines.isEmpty()) {
                        handler.handle(eventName, String.join("\n", dataLines), downstream);
                    }
                    eventName = null;
                    dataLines.clear();
                    continue;
                }
                if (line.startsWith(":")) {
                    // Transport heartbeat / comment：原样转发并 flush，供静默期断开检测。
                    // 不进入 FrameHandler，避免被伪装成业务事件。
                    writeComment(downstream, line);
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                    continue;
                }
                if (line.startsWith("data:")) {
                    dataLines.add(line.length() > "data:".length() && line.charAt("data:".length()) == ' '
                            ? line.substring("data:".length() + 1)
                            : line.substring("data:".length()));
                }
            }
            if (eventName != null || !dataLines.isEmpty()) {
                handler.handle(eventName, String.join("\n", dataLines), downstream);
            }
        }
    }

    public static void writeFrame(OutputStream outputStream, String eventName, String data) throws IOException {
        if (eventName != null && !eventName.isBlank()) {
            outputStream.write(("event: " + eventName + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (data != null) {
            for (String chunk : data.split("\n", -1)) {
                outputStream.write(("data: " + chunk + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        outputStream.write("\n".getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /** 转发 SSE comment（含 heartbeat），并立即 flush。 */
    public static void writeComment(OutputStream outputStream, String commentLine) throws IOException {
        String line = commentLine == null ? ":" : commentLine;
        if (!line.startsWith(":")) {
            line = ": " + line;
        }
        outputStream.write((line + "\n\n").getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    @FunctionalInterface
    public interface FrameHandler {
        void handle(String eventName, String data, OutputStream downstream) throws IOException;
    }

    public static FrameHandler passthrough() {
        return (eventName, data, downstream) -> writeFrame(downstream, eventName, data);
    }

    public static FrameHandler withSideEffect(java.util.function.BiConsumer<String, String> sideEffect,
                                              FrameHandler delegate) {
        return (eventName, data, downstream) -> {
            sideEffect.accept(eventName, data);
            delegate.handle(eventName, data, downstream);
        };
    }
}
