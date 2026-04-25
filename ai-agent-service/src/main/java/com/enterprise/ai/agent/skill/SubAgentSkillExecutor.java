package com.enterprise.ai.agent.skill;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agentscope.AgentFactory;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * {@link SubAgentSkill} 的运行期执行器。
 * <p>
 * 关键职责：
 * <ol>
 *   <li>防御无限递归：DEPTH ThreadLocal 限制 SubAgent 嵌套最多 3 层；</li>
 *   <li>继承审计上下文：子 Agent 复用父 traceId，Skill Mining 才能把一条链路拼起来；</li>
 *   <li>结构化参数注入：把 args 序列化为 "[Given parameters]: {json}" 追加到用户消息，
 *       避免主 Agent 调用 Skill 时必须"自然语言复述"所有参数；</li>
 *   <li>用 {@link AgentFactory#buildFromDefinition} 构造子 Agent，自动享受 Tool Retrieval 等能力。</li>
 * </ol>
 * <p>
 * 通过 {@link ObjectProvider} 懒加载 AgentFactory，打破 ToolDefinitionService ↔ AgentFactory ↔
 * SubAgentSkillExecutor 的启动期循环依赖。
 */
@Slf4j
@Component
public class SubAgentSkillExecutor {

    static final int MAX_DEPTH = 3;
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private final ObjectProvider<AgentFactory> agentFactoryProvider;
    private final ObjectMapper objectMapper;

    public SubAgentSkillExecutor(ObjectProvider<AgentFactory> agentFactoryProvider,
                                 ObjectMapper objectMapper) {
        this.agentFactoryProvider = agentFactoryProvider;
        this.objectMapper = objectMapper;
    }

    public Object execute(SubAgentSkill skill, Map<String, Object> args) {
        int depth = DEPTH.get();
        if (depth >= MAX_DEPTH) {
            throw new IllegalStateException(
                    "SubAgent 嵌套层级超过 " + MAX_DEPTH + "，可能存在循环 Skill 依赖: skill=" + skill.name());
        }
        DEPTH.set(depth + 1);
        try {
            ToolExecutionContext parentCtx = ToolExecutionContextHolder.get();
            ToolExecutionContext childCtx = buildChildContext(parentCtx, skill.name());

            SubAgentSpec spec = skill.getSpec();
            AgentDefinition childDef = AgentDefinition.builder()
                    .name("skill:" + skill.name())
                    .systemPrompt(spec.systemPrompt())
                    .tools(spec.toolWhitelist())
                    .maxSteps(spec.maxSteps())
                    .useMultiAgentModel(spec.useMultiAgentModel())
                    .type("single")
                    .build();

            String composedMessage = composeChildMessage(args);

            AgentFactory factory = agentFactoryProvider.getObject();
            ReActAgent child = factory.buildFromDefinition(childDef, composedMessage, childCtx);
            Duration timeout = resolveTimeout(skill);
            int retryLimit = resolveRetryLimit(skill);

            log.debug("[SubAgentSkill] 触发子 Agent: skill={}, depth={}, args.size={}, traceId={}",
                    skill.name(), depth + 1, args.size(), childCtx.getTraceId());

            Msg request = Msg.builder().textContent(composedMessage).build();
            Mono<Msg> callMono = child.call(request).timeout(timeout);
            if (retryLimit > 0) {
                callMono = callMono.retryWhen(Retry.max(retryLimit).filter(this::isRetryable));
            }
            Msg response;
            ToolExecutionContext holderPrev = ToolExecutionContextHolder.get();
            ToolExecutionContextHolder.set(childCtx);
            try {
                response = callMono.block();
            } catch (Exception ex) {
                if (containsTimeout(ex)) {
                    throw new SkillTimeoutException(skill.name(), timeout.toMillis(), ex);
                }
                throw ex;
            } finally {
                ToolExecutionContextHolder.set(holderPrev);
            }
            return response == null ? "" : response.getTextContent();
        } catch (IllegalStateException recursionFail) {
            throw recursionFail;
        } catch (SkillTimeoutException timeout) {
            throw timeout;
        } catch (Exception ex) {
            log.warn("[SubAgentSkill] 执行失败: skill={}, err={}", skill.name(), ex.toString());
            throw new RuntimeException("Skill 执行失败: " + ex.getMessage(), ex);
        } finally {
            DEPTH.set(depth);
        }
    }

    /**
     * 子 ctx 继承父的 traceId/sessionId/userId；agentName 换成 {@code skill:xxx}。
     */
    ToolExecutionContext buildChildContext(ToolExecutionContext parent, String skillName) {
        String traceId = parent == null || isBlank(parent.getTraceId())
                ? UUID.randomUUID().toString()
                : parent.getTraceId();
        return ToolExecutionContext.builder()
                .traceId(traceId)
                .sessionId(parent == null ? null : parent.getSessionId())
                .userId(parent == null ? null : parent.getUserId())
                .agentName("skill:" + skillName)
                .intentType(parent == null ? null : parent.getIntentType())
                // Phase 3.0 / 3.1：子 Skill 继承父护栏设置 & 角色，避免"父不允许 IRREVERSIBLE 但 Skill 绕过"。
                .allowIrreversible(parent != null && parent.isAllowIrreversible())
                .roles(parent == null ? null : parent.getRoles())
                .build();
    }

    /**
     * 父 Agent 调用 Skill 时传入的 args 是结构化 JSON 对象，子 Agent 拿到的却是
     * 一条自然语言 Msg，这里把两者拼接：先描述任务，再在后面贴 JSON 参数，
     * 子 Agent 的 systemPrompt 可以直接引用这些字段名。
     */
    String composeChildMessage(Map<String, Object> args) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下结构化参数完成任务。\n");
        sb.append("[Given parameters]:\n");
        if (args == null || args.isEmpty()) {
            sb.append("{}");
        } else {
            try {
                sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(args));
            } catch (Exception ex) {
                sb.append(args);
            }
        }
        return sb.toString();
    }

    static void resetDepth() { // visible for tests
        DEPTH.remove();
    }

    /** 当前线程正在嵌套的 SubAgent 层数；0 表示尚未进入任何 Skill。 */
    public static int currentDepth() {
        return DEPTH.get();
    }

    Duration resolveTimeout(SubAgentSkill skill) {
        if (skill == null || skill.metadata() == null || skill.metadata().timeoutMs() <= 0) {
            return Duration.ofSeconds(60);
        }
        return Duration.ofMillis(skill.metadata().timeoutMs());
    }

    int resolveRetryLimit(SubAgentSkill skill) {
        if (skill == null || skill.metadata() == null) {
            return 0;
        }
        return Math.max(0, skill.metadata().retryLimit());
    }

    private boolean isRetryable(Throwable ex) {
        return ex instanceof SkillTimeoutException || containsTimeout(ex);
    }

    /** 沿 cause 链找 TimeoutException；显式防御自引用 cause 的死循环。 */
    private boolean containsTimeout(Throwable ex) {
        Throwable cursor = ex;
        int guard = 0;
        while (cursor != null && guard++ < 16) {
            if (cursor instanceof TimeoutException) {
                return true;
            }
            Throwable next = cursor.getCause();
            if (next == cursor) {
                break;
            }
            cursor = next;
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
