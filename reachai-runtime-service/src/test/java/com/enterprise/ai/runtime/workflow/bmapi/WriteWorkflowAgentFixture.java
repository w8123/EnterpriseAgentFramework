package com.enterprise.ai.runtime.workflow.bmapi;

import com.enterprise.ai.runtime.agent.*;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelUsage;
import com.enterprise.ai.runtime.execution.*;
import com.enterprise.ai.runtime.internal.RuntimeAgentExecutionInternalController;
import com.enterprise.ai.runtime.memory.RuntimeSessionMemoryService;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogMapper;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionWriter;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.supervisor.*;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService;
import com.enterprise.ai.runtime.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Agent config/resolver/Guard/approval/resume/GraphSpec host; only model responses are controlled. */
final class WriteWorkflowAgentFixture {
    private final ObjectMapper json;
    private final DataSource dataSource;
    private final ArrayDeque<ModelChatData> responses = new ArrayDeque<>();
    private final AtomicInteger modelCalls = new AtomicInteger();
    final RuntimeAgentController identities;
    final RuntimeAgentConfigController configurations;
    final RuntimeAgentExecutionInternalController executions;

    WriteWorkflowAgentFixture(SqlSessionTemplate session, DataSource dataSource, ObjectMapper json,
                              RuntimeGraphSpecExecutor graphExecutor, RuntimeTraceEvidenceWriter evidence,
                              RuntimeTraceRootService roots, RuntimeTraceSpanTerminationService termination,
                              RuntimeRunLifecycleService runs, RuntimeWorkflowHttpApiService apiProjection) {
        this.json = json;
        this.dataSource = dataSource;
        var agents = session.getMapper(RuntimeAgentMapper.class);
        var configs = session.getMapper(RuntimeAgentConfigVersionMapper.class);
        var tools = session.getMapper(RuntimeAgentWorkflowToolMapper.class);
        var skills = session.getMapper(RuntimeAgentSkillBindingMapper.class);
        var remote = session.getMapper(RuntimeAgentRemoteBindingMapper.class);
        var workflows = session.getMapper(RuntimeWorkflowDefinitionMapper.class);
        var versions = session.getMapper(RuntimeWorkflowVersionMapper.class);
        var configService = transactional(new RuntimeAgentConfigService(configs, tools, skills, remote, agents,
                new RuntimeWorkflowToolCatalogReader(workflows, versions, json), json));
        identities = new RuntimeAgentController(transactional(new RuntimeAgentService(agents, configService)), null);
        configurations = new RuntimeAgentConfigController(configService);
        var workflowQuery = transactional(new RuntimeWorkflowExecutionReader(workflows, versions));
        var resolver = transactional(new RuntimeAgentExecutionContextResolver(
                new RuntimeAgentIdentityReader(agents), configs, tools, skills, workflowQuery,
                new RuntimeAgentRemoteBindingReader(remote, configs)));
        var trace = new SupervisorExecutionTraceService(evidence, runs, json, roots, termination);
        var approvals = transactional(new RuntimeSupervisorApprovalService(
                session.getMapper(RuntimeInteractionSessionMapper.class),
                session.getMapper(RuntimeInteractionEventMapper.class), json, trace, 900));
        var inputProtection = new RuntimeWorkflowInputProtectionService(workflowQuery, apiProjection, json);
        var approvalUi = new SupervisorApprovalInteractionService(approvals, json, inputProtection);
        var guard = new SupervisorToolPolicyService(
                new RuntimeGuardDecisionWriter(session.getMapper(RuntimeGuardDecisionLogMapper.class)), approvalUi, json);
        var interactions = transactional(new RuntimeWorkflowInteractionSessionService(
                session.getMapper(RuntimeInteractionSessionMapper.class), session.getMapper(RuntimeInteractionEventMapper.class), json));
        RuntimeModelServiceClient model = request -> {
            modelCalls.incrementAndGet();
            ModelChatData response;
            synchronized (responses) { response = responses.pollFirst(); }
            if (response == null) throw new IllegalStateException("Unexpected controlled model round");
            return new ModelChatResult(200, "success", response);
        };
        var supervisor = new AgentScopeSupervisorRuntimeAdapter(model, null, null, workflowQuery, graphExecutor,
                interactions, RuntimeSessionMemoryService.transientOnly(), guard, trace, json);
        supervisor.setWorkflowInputProtection(inputProtection);
        var execution = new RuntimeAgentExecutionService(resolver, supervisor, approvals,
                new RuntimeInteractionResumeService(interactions, graphExecutor, json, null),
                (sessionId, identity) -> { throw new UnsupportedOperationException("Session clearing is outside this fixture"); }, runs);
        executions = new RuntimeAgentExecutionInternalController(execution, null, 8_000L);
    }

    void planAndRequestWrite(Map<String, Object> arguments) {
        synchronized (responses) {
            responses.clear();
            responses.add(calls("plan", "record_supervisor_plan", Map.of(
                    "summary", "Append one order note", "steps", List.of("Append note"),
                    "workflowToolNames", List.of("append_order_note"))));
            responses.add(calls("write", "append_order_note", arguments));
        }
    }

    void finishConfirmedWrite() {
        synchronized (responses) {
            responses.clear();
            responses.add(calls("final", "begin_final_answer", Map.of()));
            responses.add(text("NOTED: the confirmed order-note Workflow returned its business state."));
        }
    }

    void attemptFailedWriteReplan(Map<String, Object> arguments) {
        synchronized (responses) {
            responses.clear();
            responses.add(calls("replan", "record_supervisor_plan", Map.of(
                    "summary", "Try the same write again", "steps", List.of("Repeat note"),
                    "workflowToolNames", List.of("append_order_note"))));
            responses.add(calls("repeat", "append_order_note", arguments));
            responses.add(calls("abandon", "record_supervisor_plan", Map.of(
                    "summary", "Stop and inspect the original run", "steps", List.of(), "workflowToolNames", List.of())));
            responses.add(calls("final", "begin_final_answer", Map.of()));
            responses.add(text("The write result is unconfirmed. Check the business record; this run will not resend it."));
        }
    }

    int modelCalls() { return modelCalls.get(); }

    private ModelChatData calls(String id, String name, Map<String, Object> args) {
        try {
            return new ModelChatData(null, "controlled-local", "fixture", new ModelUsage(1, 1, 2), null,
                    json.valueToTree(List.of(Map.of("id", id, "type", "function", "function",
                            Map.of("name", name, "arguments", json.writeValueAsString(args))))), "tool_calls");
        } catch (Exception invalid) { throw new IllegalArgumentException("Controlled model arguments are invalid", invalid); }
    }

    private ModelChatData text(String content) {
        return new ModelChatData(content, "controlled-local", "fixture", new ModelUsage(1, 1, 2), null, null, "stop");
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
