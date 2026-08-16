package com.enterprise.ai.model.runtime;

import com.enterprise.ai.model.service.ModelStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 OpenAI 兼容 SSE 读循环的终态判定（含提前 EOF）。
 */
class OpenAiCompatibleRuntimeClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reasoningThenEofWithoutDoneOrFinishReason_isInterruptedOnly() throws Exception {
        String sse = ""
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"secret-chain\"}}]}\n"
                + "\n";
        TerminalCapture capture = drainAndEmit(sse);

        assertFalse(capture.types.contains(ModelStreamEvent.COMPLETED));
        assertEquals(List.of(ModelStreamEvent.REASONING_DELTA, ModelStreamEvent.ERROR), capture.types);
        assertEquals(ModelStreamEvent.CODE_STREAM_INTERRUPTED, capture.errorCode);
        assertEquals("模型流在返回最终结果前中断，请重试。", capture.errorMessage);
        ModelStreamEvent errorEvent = capture.events.stream()
                .filter(e -> ModelStreamEvent.ERROR.equals(e.getType()))
                .findFirst()
                .orElseThrow();
        assertNull(errorEvent.getText());
        assertFalse(objectMapper.writeValueAsString(errorEvent).contains("secret-chain"),
                "interrupted terminal must not carry reasoning body");
        assertTrue(capture.completedSink);
        assertNull(capture.sinkError);
    }

    @Test
    void contentThenEofWithoutTerminal_isInterruptedOnly() throws Exception {
        String sse = ""
                + "data: {\"choices\":[{\"delta\":{\"content\":\"partial-answer\"}}]}\n"
                + "\n";
        TerminalCapture capture = drainAndEmit(sse);

        assertFalse(capture.types.contains(ModelStreamEvent.COMPLETED));
        assertEquals(List.of(ModelStreamEvent.CONTENT_DELTA, ModelStreamEvent.ERROR), capture.types);
        assertEquals(ModelStreamEvent.CODE_STREAM_INTERRUPTED, capture.errorCode);
        assertEquals(1, capture.types.stream().filter(ModelStreamEvent.ERROR::equals).count());
    }

    @Test
    void usageThenEofWithoutTerminal_isInterruptedOnly() throws Exception {
        String sse = ""
                + "data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":7,\"total_tokens\":10}}\n"
                + "\n";
        TerminalCapture capture = drainAndEmit(sse);

        assertFalse(capture.types.contains(ModelStreamEvent.COMPLETED));
        assertTrue(capture.types.contains(ModelStreamEvent.USAGE));
        assertEquals(ModelStreamEvent.CODE_STREAM_INTERRUPTED, capture.errorCode);
        ModelStreamEvent usage = capture.events.stream()
                .filter(e -> ModelStreamEvent.USAGE.equals(e.getType()))
                .findFirst()
                .orElseThrow();
        assertEquals(3, usage.getUsage().getPromptTokens());
        assertEquals(7, usage.getUsage().getCompletionTokens());
    }

    @Test
    void finishReasonStopWithoutDone_isCompatibleCompletedOnce() throws Exception {
        String sse = ""
                + "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n"
                + "\n";
        TerminalCapture capture = drainAndEmit(sse);

        assertEquals(List.of(ModelStreamEvent.CONTENT_DELTA, ModelStreamEvent.COMPLETED), capture.types);
        assertEquals(1, capture.types.stream().filter(ModelStreamEvent.COMPLETED::equals).count());
        assertFalse(capture.types.contains(ModelStreamEvent.ERROR));
        assertEquals("stop", capture.events.get(capture.events.size() - 1).getFinishReason());
    }

    @Test
    void finishReasonStopsReadLoopWithoutWaitingForDoneOrConnectionClose() throws Exception {
        AtomicInteger readCount = new AtomicInteger();
        BufferedReader reader = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() {
                if (readCount.getAndIncrement() == 0) {
                    return "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
                }
                throw new AssertionError("finish_reason must terminate the SSE read loop");
            }
        };
        List<ModelStreamEvent> events = new ArrayList<>();

        OpenAiCompatibleRuntimeClient.SseTerminal terminal = OpenAiCompatibleRuntimeClient.drainOpenAiSse(
                reader,
                objectMapper,
                events::add);

        assertEquals(1, readCount.get());
        assertTrue(terminal.hasFinishReason());
        assertEquals("stop", terminal.finishReason());
        assertEquals(List.of(ModelStreamEvent.CONTENT_DELTA), events.stream().map(ModelStreamEvent::getType).toList());
    }

    @Test
    void normalDone_emitsCompletedOnce() throws Exception {
        String sse = ""
                + "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}\n"
                + "data: [DONE]\n";
        TerminalCapture capture = drainAndEmit(sse);

        assertEquals(List.of(
                ModelStreamEvent.CONTENT_DELTA,
                ModelStreamEvent.CONTENT_DELTA,
                ModelStreamEvent.COMPLETED), capture.types);
        assertEquals(1, capture.types.stream().filter(ModelStreamEvent.COMPLETED::equals).count());
        assertFalse(capture.types.contains(ModelStreamEvent.ERROR));
        // A non-empty finish_reason is terminal on its own. The reader must not
        // wait for a following [DONE], because some compatible providers keep
        // that connection open after the terminal chunk.
        assertFalse(capture.terminal.receivedDone());
        assertTrue(capture.terminal.hasFinishReason());
    }

    @Test
    void terminalMutuallyExclusive_neverBothCompletedAndError() throws Exception {
        List<String> fixtures = List.of(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"x\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\ndata: [DONE]\n");
        for (String sse : fixtures) {
            TerminalCapture capture = drainAndEmit(sse);
            boolean hasCompleted = capture.types.contains(ModelStreamEvent.COMPLETED);
            boolean hasError = capture.types.contains(ModelStreamEvent.ERROR);
            assertFalse(hasCompleted && hasError, "must not emit both completed and error for: " + sse);
            assertTrue(hasCompleted ^ hasError, "must emit exactly one terminal for: " + sse);
        }
    }

    @Test
    void blankFinishReasonDoesNotCountAsNormalTerminal() throws Exception {
        String sse = ""
                + "data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"   \"}]}\n"
                + "\n";
        TerminalCapture capture = drainAndEmit(sse);
        assertFalse(capture.types.contains(ModelStreamEvent.COMPLETED));
        assertEquals(ModelStreamEvent.CODE_STREAM_INTERRUPTED, capture.errorCode);
    }

    private TerminalCapture drainAndEmit(String sse) throws Exception {
        List<ModelStreamEvent> events = new ArrayList<>();
        OpenAiCompatibleRuntimeClient.SseTerminal terminal = OpenAiCompatibleRuntimeClient.drainOpenAiSse(
                new BufferedReader(new StringReader(sse)),
                objectMapper,
                events::add);

        AtomicReference<Throwable> sinkError = new AtomicReference<>();
        List<ModelStreamEvent> afterTerminal = new ArrayList<>();
        boolean[] completed = {false};
        Flux.<ModelStreamEvent>create(sink -> {
            for (ModelStreamEvent event : events) {
                sink.next(event);
            }
            OpenAiCompatibleRuntimeClient.emitSseTerminal(sink, terminal);
        }, FluxSink.OverflowStrategy.BUFFER)
                .doOnNext(afterTerminal::add)
                .doOnComplete(() -> completed[0] = true)
                .doOnError(sinkError::set)
                .collectList()
                .block();

        List<ModelStreamEvent> all = new ArrayList<>(afterTerminal);
        String serialized = objectMapper.writeValueAsString(all);
        String errorCode = all.stream()
                .filter(e -> ModelStreamEvent.ERROR.equals(e.getType()))
                .map(ModelStreamEvent::getCode)
                .findFirst()
                .orElse(null);
        String errorMessage = all.stream()
                .filter(e -> ModelStreamEvent.ERROR.equals(e.getType()))
                .map(ModelStreamEvent::getMessage)
                .findFirst()
                .orElse(null);
        return new TerminalCapture(
                all,
                all.stream().map(ModelStreamEvent::getType).toList(),
                terminal,
                errorCode,
                errorMessage,
                serialized,
                completed[0],
                sinkError.get());
    }

    private record TerminalCapture(
            List<ModelStreamEvent> events,
            List<String> types,
            OpenAiCompatibleRuntimeClient.SseTerminal terminal,
            String errorCode,
            String errorMessage,
            String serialized,
            boolean completedSink,
            Throwable sinkError) {
    }
}
