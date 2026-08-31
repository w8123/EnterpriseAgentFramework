package com.enterprise.ai.control.a2a.application.task;

import com.enterprise.ai.control.a2a.application.port.A2aContentCipher;
import com.enterprise.ai.control.a2a.application.port.A2aTaskManagementReader.TaskRow;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.ArtifactRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.MessageRecord;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class A2aTaskPayloadService {

    private final A2aTaskManagementService management;
    private final A2aTaskRepository tasks;
    private final A2aContentCipher cipher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PayloadView message(String taskId, String direction, String messageId) {
        TaskRow task = management.requireUnique(taskId, direction);
        A2aDirection parsedDirection = A2aDirection.parse(task.direction());
        MessageRecord message = tasks.findMessage(
                        parsedDirection, task.principalId(), task.tenantScope(), identifier(messageId))
                .filter(value -> value.taskRefId() != null && value.taskRefId() == task.taskRefId())
                .orElseThrow(() -> new A2aDomainException(
                        "A2A_MESSAGE_NOT_FOUND", "Message was not found for this Task"));
        requireAvailable(message.payloadCiphertext(), message.payloadObjectRef(),
                message.contentDeletedAt(), message.retentionExpiresAt());
        byte[] plaintext = cipher.decrypt(new A2aContentCipher.EncryptedContent(
                        message.encryptionKeyId(), message.encryptionNonce(), message.payloadCiphertext()),
                A2aContentBinding.message(
                        parsedDirection, task.principalId(), task.tenantScope(), message.messageId()));
        verify(plaintext, message.payloadSha256());
        return new PayloadView(
                "MESSAGE", message.messageId(), "application/a2a+json",
                message.payloadBytes(), message.payloadSha256(),
                message.contentClassification(), parse(plaintext));
    }

    @Transactional(readOnly = true)
    public PayloadView artifact(String taskId, String direction, String artifactId) {
        TaskRow task = management.requireUnique(taskId, direction);
        A2aDirection parsedDirection = A2aDirection.parse(task.direction());
        ArtifactRecord artifact = tasks.findArtifacts(
                        parsedDirection, task.principalId(), task.tenantScope(), task.taskRefId())
                .stream().filter(value -> value.artifactId().equals(identifier(artifactId)))
                .findFirst().orElseThrow(() -> new A2aDomainException(
                        "A2A_ARTIFACT_NOT_FOUND", "Artifact was not found for this Task"));
        requireAvailable(artifact.payloadCiphertext(), artifact.payloadObjectRef(),
                artifact.contentDeletedAt(), artifact.retentionExpiresAt());
        byte[] plaintext = cipher.decrypt(new A2aContentCipher.EncryptedContent(
                        artifact.encryptionKeyId(), artifact.encryptionNonce(), artifact.payloadCiphertext()),
                A2aContentBinding.artifact(
                        parsedDirection, task.principalId(), task.tenantScope(),
                        task.taskId(), artifact.artifactId()));
        verify(plaintext, artifact.payloadSha256());
        return new PayloadView(
                "ARTIFACT", artifact.artifactId(), "application/a2a+json",
                artifact.payloadBytes(), artifact.payloadSha256(),
                artifact.contentClassification(), parse(plaintext));
    }

    private void requireAvailable(
            String ciphertext, String objectRef, LocalDateTime deletedAt, LocalDateTime expiresAt) {
        if (deletedAt != null || (expiresAt != null && expiresAt.isBefore(LocalDateTime.now(clock)))) {
            throw new A2aDomainException("A2A_PAYLOAD_EXPIRED",
                    "Payload is no longer available under the retention policy");
        }
        if (ciphertext == null) {
            if (objectRef != null) {
                throw new A2aDomainException("A2A_OBJECT_PAYLOAD_UNAVAILABLE",
                        "External object payload retrieval is not configured");
            }
            throw new A2aDomainException("A2A_PAYLOAD_UNAVAILABLE", "Payload is unavailable");
        }
    }

    private JsonNode parse(byte[] value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception invalid) {
            throw new A2aDomainException("A2A_PAYLOAD_INTEGRITY_FAILED",
                    "Decrypted payload is not valid canonical JSON");
        }
    }

    private void verify(byte[] value, String expected) {
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
            if (!actual.equals(expected)) {
                throw new A2aDomainException("A2A_PAYLOAD_INTEGRITY_FAILED",
                        "Payload integrity verification failed");
            }
        } catch (A2aDomainException known) {
            throw known;
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private String identifier(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 128) {
            throw new A2aDomainException("A2A_RESOURCE_ID_INVALID", "resource id is invalid");
        }
        return value.trim();
    }

    public record PayloadView(
            String resourceType,
            String resourceId,
            String mediaType,
            long bytes,
            String sha256,
            String contentClassification,
            JsonNode content) {
    }
}
