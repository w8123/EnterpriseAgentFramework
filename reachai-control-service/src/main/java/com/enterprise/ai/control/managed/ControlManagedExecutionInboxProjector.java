package com.enterprise.ai.control.managed;

/**
 * Application boundary for projecting a Runtime Managed Execution inbox event into a
 * Control-owned product domain.
 *
 * <p>The managed module owns durable receipt, replay protection, leasing and retry. Product
 * modules implement this port for the source types they own; the managed module never imports
 * their application services or persistence types.</p>
 */
public interface ControlManagedExecutionInboxProjector {

    boolean supports(ProjectionEvent event);

    ProjectionResult project(ProjectionEvent event);

    record ProjectionEvent(
            String eventId,
            String executionId,
            String eventType,
            String tenantId,
            String projectCode,
            String sourceType,
            String sourceRef,
            String runtimeStatus,
            String payloadJson) {
    }

    record ProjectionResult(String status, String error) {
        public static ProjectionResult applied() {
            return new ProjectionResult("APPLIED", null);
        }

        public static ProjectionResult ignored() {
            return new ProjectionResult("IGNORED", null);
        }

        public static ProjectionResult pending(String error) {
            return new ProjectionResult("PENDING", error);
        }

        public static ProjectionResult failed(String error) {
            return new ProjectionResult("FAILED", error);
        }
    }
}
