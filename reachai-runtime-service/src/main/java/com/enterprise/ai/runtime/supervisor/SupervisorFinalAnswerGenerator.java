package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agentscope.AgentScopeAnswerPhase;
import com.enterprise.ai.runtime.agentscope.ModelStreamFailure;
import com.enterprise.ai.runtime.agentscope.ReachAiAgentScopeChatModel;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionEventSink;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Generates an eligible public answer without execution tools or a separate recovery budget. */
final class SupervisorFinalAnswerGenerator {
    private static final Logger log = LoggerFactory.getLogger(SupervisorFinalAnswerGenerator.class);
    private static final String FINAL_PASS_INSTRUCTION =
            "Final answer phase is active. Produce the complete user-facing answer in plain text only. "
                    + "Do not call any tool. Do not reveal internal planning, tool arguments, or reasoning.";

    private final ReachAiAgentScopeChatModel model;
    private final RuntimeAgentExecutionCancellation cancellation;
    private final RuntimeAgentExecutionEventSink eventSink;
    private final Duration timeout;

    SupervisorFinalAnswerGenerator(ReachAiAgentScopeChatModel model,
                                   RuntimeAgentExecutionCancellation cancellation,
                                   RuntimeAgentExecutionEventSink eventSink,
                                   long totalTimeoutMs) {
        this.model = model;
        this.cancellation = cancellation;
        this.eventSink = eventSink;
        this.timeout = Duration.ofMillis(Math.max(30_000L, totalTimeoutMs));
    }

    record Prompt(String userMessage, String internalDraft, String resultGuidance, List<Msg> history) {}

    static Hook publicFinalHook(AgentScopeAnswerPhase answerPhase) {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PreReasoningEvent pre && answerPhase.isPublicFinal()) {
                    GenerateOptions forced = noToolsOptions();
                    GenerateOptions current = pre.getGenerateOptions();
                    pre.setGenerateOptions(current == null
                            ? forced
                            : GenerateOptions.mergeOptions(current, forced));
                }
                return Mono.just(event);
            }
        };
    }

    String generate(Prompt prompt,
                    RuntimeContextEngineeringService.Invocation contextEngineering,
                    ReActAgent reactAgent,
                    RuntimeContext runtimeContext) {
        String answer;
        try {
            answer = stream(messages(prompt, prompt.history()));
        } catch (RuntimeAgentExecutionCancellation.CancellationSignal cancelled) {
            throw cancelled;
        } catch (Exception ex) {
            cancellation.throwIfCancelled();
            ModelStreamFailure streamFailure = ModelStreamFailure.findIn(ex);
            if (streamFailure != null
                    && streamFailure.isContextLengthExceeded()
                    && !model.didStreamContent()) {
                try {
                    Optional<List<Msg>> compacted = contextEngineering
                            .compactForFinalOverflow(reactAgent, runtimeContext)
                            .block(timeout);
                    if (compacted != null && compacted.isPresent()) {
                        return publishFallback(stream(messages(prompt, compacted.get())));
                    }
                } catch (RuntimeAgentExecutionCancellation.CancellationSignal cancelled) {
                    throw cancelled;
                } catch (Exception recoveryFailure) {
                    cancellation.throwIfCancelled();
                    ModelStreamFailure retryFailure = ModelStreamFailure.findIn(recoveryFailure);
                    if (retryFailure != null) throw retryFailure;
                    log.warn("Forced PUBLIC_FINAL context recovery failed; original overflow retained: {}",
                            recoveryFailure.getClass().getSimpleName());
                }
            }
            // Once public deltas exist, propagate the failure without another model call or replay.
            if (streamFailure != null) throw streamFailure;
            throw new IllegalStateException("Forced PUBLIC_FINAL pass failed: " + rootMessage(ex), ex);
        }
        return publishFallback(answer);
    }

    private static GenerateOptions noToolsOptions() {
        return GenerateOptions.builder()
                .toolChoice(new ToolChoice.None())
                .temperature(0.1D)
                .build();
    }

    private List<Msg> messages(Prompt prompt, List<Msg> baseMessages) {
        List<Msg> finalMessages = baseMessages == null
                ? new ArrayList<>()
                : new ArrayList<>(baseMessages);
        if (finalMessages.isEmpty()) {
            finalMessages.add(Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .textContent(prompt.userMessage())
                    .build());
        }
        if (StringUtils.hasText(prompt.internalDraft())) {
            finalMessages.add(Msg.builder()
                    .name("system")
                    .role(MsgRole.SYSTEM)
                    .textContent("Internal draft context (do not quote as planning): "
                            + prompt.internalDraft().trim())
                    .build());
        }
        if (StringUtils.hasText(prompt.resultGuidance())) {
            finalMessages.add(Msg.builder()
                    .name("system")
                    .role(MsgRole.SYSTEM)
                    .textContent("Verified workflow result guidance (follow it exactly): " + prompt.resultGuidance())
                    .build());
        }
        finalMessages.add(Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(FINAL_PASS_INSTRUCTION)
                .build());
        return finalMessages;
    }

    private String stream(List<Msg> finalMessages) {
        cancellation.throwIfCancelled();
        List<ChatResponse> responses = model.stream(finalMessages, List.of(), noToolsOptions())
                .collectList()
                .block(timeout);
        cancellation.throwIfCancelled();
        String answer = "";
        if (responses != null) {
            for (ChatResponse response : responses) {
                if (response == null || response.getContent() == null) continue;
                for (var block : response.getContent()) {
                    if (block instanceof TextBlock text && StringUtils.hasText(text.getText())) {
                        answer = text.getText();
                    }
                }
            }
        }
        return answer;
    }

    private String publishFallback(String answer) {
        if (!model.didStreamContent() && !answer.isEmpty() && !cancellation.isCancelled()) {
            eventSink.emit("message.delta", Map.of("text", answer));
        }
        return answer.isEmpty() ? null : answer;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        if (StringUtils.hasText(current.getMessage())) return current.getMessage().trim();
        if (StringUtils.hasText(error.getMessage())) return error.getMessage().trim();
        return error.getClass().getSimpleName();
    }
}
