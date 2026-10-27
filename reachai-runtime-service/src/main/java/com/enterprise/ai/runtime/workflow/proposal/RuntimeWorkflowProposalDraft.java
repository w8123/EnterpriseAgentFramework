package com.enterprise.ai.runtime.workflow.proposal;

import java.util.List;
import java.util.Map;

/** Intermediate proposal values; GraphSpec remains the executable contract. */
final class RuntimeWorkflowProposalDraft {

    private RuntimeWorkflowProposalDraft() {
    }

    record ProposalResponse(String summary,
                                    String entryNodeId,
                                    List<String> exitNodeIds,
                                    List<ProposalNodePayload> nodes,
                                    List<ProposalEdge> edges,
                                    List<String> warnings) {
    }

    record ProposalNodePayload(String id,
                                       String type,
                                       String label,
                                       String description,
                                       Map<String, Object> config,
                                       List<Map<String, Object>> inputs,
                                       List<Map<String, Object>> outputs) {
    }

    record ProposalNode(String id,
                                String type,
                                String canvasKind,
                                String label,
                                String description,
                                Map<String, Object> config,
                                List<Map<String, Object>> inputs,
                                List<Map<String, Object>> outputs) {
    }

    record ProposalEdge(String id,
                                String from,
                                String to,
                                String condition,
                                String sourceHandle,
                                String targetHandle) {
    }

    record ProposalBoundaries(String entryNodeId, List<String> exitNodeIds) {
    }
}
