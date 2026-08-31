package com.enterprise.ai.control.a2a.application.port;

import com.enterprise.ai.control.a2a.domain.A2aTaskState;

import java.time.LocalDateTime;
import java.util.List;

/** Typed, duplicate-safe A2A 1.0 HTTP+JSON request/response codec. */
public interface A2aOutboundProtocolCodec {

    EncodedRequest encodeTextMessage(
            String tenant,
            String messageId,
            String remoteContextId,
            String remoteTaskId,
            String text,
            String protocolSkillId,
            String contentClassification,
            List<String> acceptedOutputModes,
            int historyLength);

    DecodedResponse decodeSendResponse(byte[] responseBody);

    EncodedOperation encodeCancelTask(String tenant, String remoteTaskId);

    DecodedResponse decodeTaskResponse(byte[] responseBody);

    record EncodedRequest(
            byte[] requestBody,
            byte[] canonicalMessage,
            String messageSha256,
            String idempotencySha256,
            long messageBytes,
            String safeSummary) {

        public EncodedRequest {
            requestBody = requestBody == null ? new byte[0] : requestBody.clone();
            canonicalMessage = canonicalMessage == null ? new byte[0] : canonicalMessage.clone();
        }

        @Override
        public byte[] requestBody() {
            return requestBody.clone();
        }

        @Override
        public byte[] canonicalMessage() {
            return canonicalMessage.clone();
        }
    }

    record EncodedOperation(byte[] requestBody, String requestSha256) {
        public EncodedOperation {
            requestBody = requestBody == null ? new byte[0] : requestBody.clone();
        }

        @Override
        public byte[] requestBody() {
            return requestBody.clone();
        }
    }

    record DecodedResponse(
            String canonicalEnvelopeJson,
            String remoteTaskId,
            String remoteContextId,
            A2aTaskState state,
            LocalDateTime statusTimestamp,
            String safeStatusSummary,
            List<DecodedMessage> messages,
            List<DecodedArtifact> artifacts) {

        public DecodedResponse {
            messages = messages == null ? List.of() : List.copyOf(messages);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    record DecodedMessage(
            String messageId,
            String role,
            byte[] canonicalJson,
            String payloadSha256,
            long payloadBytes,
            List<String> mediaTypes,
            String safeSummary) {

        public DecodedMessage {
            canonicalJson = canonicalJson == null ? new byte[0] : canonicalJson.clone();
            mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        }

        @Override
        public byte[] canonicalJson() {
            return canonicalJson.clone();
        }
    }

    record DecodedArtifact(
            String artifactId,
            String name,
            String description,
            byte[] canonicalJson,
            String payloadSha256,
            long payloadBytes,
            String mediaTypesJson,
            List<String> mediaTypes,
            String safeSummary) {

        public DecodedArtifact {
            canonicalJson = canonicalJson == null ? new byte[0] : canonicalJson.clone();
            mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        }

        @Override
        public byte[] canonicalJson() {
            return canonicalJson.clone();
        }
    }
}
