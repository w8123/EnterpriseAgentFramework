package com.enterprise.ai.runtime.registry;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphRegistration;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncItem;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncRequest;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncResponse;
import com.enterprise.ai.runtime.workflow.RuntimeSdkWorkflowSyncService;
import com.enterprise.ai.runtime.workflow.RuntimeSdkWorkflowSyncService.SyncGraph;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Registry translates the public SDK protocol; Workflow owns all draft synchronization. */
@Service
@RequiredArgsConstructor
public class RuntimeAgentGraphSyncService {
    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeSdkWorkflowSyncService workflowSync;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AgentGraphSyncResponse sync(String projectCode, AgentGraphSyncRequest request) {
        ProjectRef project = getProject(projectCode);
        List<AgentGraphRegistration> graphs = request == null || request.graphs() == null ? List.of() : request.graphs();
        boolean apply = request == null || request.apply() == null || Boolean.TRUE.equals(request.apply());
        String syncId = StringUtils.hasText(request == null ? null : request.syncId())
                ? request.syncId().trim() : UUID.randomUUID().toString();
        List<SyncGraph> definitions = graphs.stream().map(this::definition).toList();
        var receipts = workflowSync.sync(project.projectId(), project.projectCode(), syncId, apply, definitions);
        var items = receipts.stream().map(receipt -> new AgentGraphSyncItem(receipt.graphCode(),
                receipt.workflowId(), receipt.keySlug(), apply
                        ? (receipt.newWorkflow() ? "CREATED" : "UPDATED")
                        : (receipt.newWorkflow() ? "WOULD_CREATE" : "WOULD_UPDATE"), apply ? "applied" : "diff only")).toList();
        int created = apply ? (int) receipts.stream().filter(RuntimeSdkWorkflowSyncService.GraphReceipt::newWorkflow).count() : 0;
        int updated = apply ? receipts.size() - created : 0;
        return new AgentGraphSyncResponse(syncId, project.projectId(), project.projectCode(),
                graphs.size(), created, updated, items);
    }

    private SyncGraph definition(AgentGraphRegistration graph) {
        if (graph == null || graph.graphSpec() == null) throw new IllegalArgumentException("SDK Agent Graph 缺少 graphSpec");
        return new SyncGraph(graph.code(), graph.name(), graph.description(), graph.executionEngine(),
                graph.modelInstanceId(), writeJson(graph.graphSpec()), writeJson(graph.metadata()));
    }

    private ProjectRef getProject(String projectCode) {
        if (!StringUtils.hasText(projectCode)) throw new IllegalArgumentException("projectCode is required");
        Map<String, Object> body = capabilityClient.getProject(projectCode.trim());
        if (body == null) throw new IllegalArgumentException("Capability project lookup response is missing");
        Long projectId = longValue(body.get("projectId"));
        String resolvedCode = body.get("projectCode") == null ? projectCode.trim() : String.valueOf(body.get("projectCode")).trim();
        if (projectId == null || projectId <= 0 || !StringUtils.hasText(resolvedCode)) {
            throw new IllegalArgumentException("Capability project lookup response is incomplete: " + projectCode);
        }
        return new ProjectRef(projectId, resolvedCode);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && StringUtils.hasText(text)) return Long.parseLong(text.trim());
        return null;
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("SDK Graph JSON 序列化失败", ex); }
    }

    private record ProjectRef(Long projectId, String projectCode) { }
}
