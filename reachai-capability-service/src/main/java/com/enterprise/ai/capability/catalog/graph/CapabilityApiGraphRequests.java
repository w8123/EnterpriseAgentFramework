package com.enterprise.ai.capability.catalog.graph;

import java.util.List;

public final class CapabilityApiGraphRequests {

    private CapabilityApiGraphRequests() {
    }

    public record EdgeUpsertRequest(Long sourceNodeId, Long targetNodeId, String kind, String note) {
    }

    public record CandidateConfirmRequest(String confirmedBy) {
    }

    public record CandidateRejectRequest(String rejectReason) {
    }

    public record LayoutSaveRequest(List<LayoutPositionDTO> positions) {
    }

    public record LayoutPositionDTO(Long nodeId, Double x, Double y, String extJson) {
    }

    public record InferResultDTO(int generated) {
    }

    public record ApiErrorResponse(String message) {
    }
}
