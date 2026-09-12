package com.enterprise.ai.control.aicoding.domain;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class AiCodingTaskValues {

    public static String normalizeClientProvider(
            String expectedProvider,
            String suppliedProvider,
            String field) {
        if (!StringUtils.hasText(suppliedProvider)) {
            return expectedProvider;
        }
        String provider = AiCodingTaskValues.requiredEnum(
                ExecutorProvider.class,
                suppliedProvider,
                field).name();
        if (!expectedProvider.equals(provider)) {
            throw new IllegalArgumentException(
                    field + " must match task executorProvider");
        }
        return provider;
    }



    public static final String PROTOCOL_VERSION = "v1";
    public static final String TASK_CONTEXT_SCHEMA = "reachai.ai-coding.task-context.v1";
    public static final String EVENT_SCHEMA = "reachai.ai-coding.event.v1";
    public static final String QUESTION_SCHEMA = "reachai.ai-coding.question.v1";
    public static final String ARTIFACT_SCHEMA = "reachai.ai-coding.artifact.v1";
    public static final int PROJECT_CODE_MAX_CHARACTERS = 96;
    public static final int TASK_TITLE_MAX_CHARACTERS = 256;
    public static final int TARGET_TYPE_MAX_CHARACTERS = 40;
    public static final int TARGET_KEY_MAX_CHARACTERS = 256;
    public static final int CLIENT_EVENT_ID_MAX_CHARACTERS = 96;
    public static final int EVENT_TYPE_MAX_CHARACTERS = 40;
    public static final int EVENT_MESSAGE_MAX_CHARACTERS = 1000;
    public static final int ACTOR_NAME_MAX_CHARACTERS = 96;
    public static final int QUESTION_ID_MAX_CHARACTERS = 96;
    public static final int QUESTION_TITLE_MAX_CHARACTERS = 256;
    public static final int ARTIFACT_KEY_MAX_CHARACTERS = 128;
    public static final int CONTRACT_KEY_MAX_CHARACTERS = 128;
    public static final int CONTRACT_VERSION_MAX_CHARACTERS = 32;
    public static final int CLIENT_SESSION_REF_MAX_CHARACTERS = 256;
    public static final int QUESTION_OPTION_MAX_CHARACTERS = 1000;
    public static final int QUESTION_OPTION_MAX_COUNT = 20;
    public static final int TEXT_MAX_UTF8_BYTES = 60_000;

    private AiCodingTaskValues() {
    }

    public enum AccessMode {
        READ_ONLY,
        READ_WRITE
    }

    public enum ExecutorProvider {
        CODEX,
        CURSOR,
        TRAE,
        CLAUDE_CODE
    }

    public enum ExecutionMode {
        EXTERNAL_CLIENT,
        MANAGED_SANDBOX
    }

    public enum ManagedSandboxProfile {
        ANALYZE_READONLY,
        WORKSPACE_PATCH
    }

    public enum ExecutionStatus {
        READY,
        RUNNING,
        WAITING_USER,
        RESULT_SUBMITTED,
        RESULT_APPLIED,
        ACCEPTANCE_READY,
        COMPLETED,
        FAILED,
        CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }

        /**
         * Whether an activated AI Coding client may still interact with this task.
         *
         * <p>{@link #ACCEPTANCE_READY} is intentionally excluded even though it is
         * not terminal: the coding delivery has ended and the remaining transition
         * is owned by ReachAI's human acceptance flow.</p>
         */
        public boolean acceptsClientAccess() {
            return !terminal() && this != ACCEPTANCE_READY;
        }
    }

    public enum ConnectionStatus {
        WAITING_CONNECT,
        ACTIVE,
        TIMED_OUT,
        CLOSED,
        NOT_APPLICABLE
    }

    public enum TargetRole {
        PRIMARY,
        RELATED
    }

    public enum ActorType {
        USER,
        AI_CODING,
        REACHAI
    }

    public enum ActivationStatus {
        ISSUED,
        ACTIVATED,
        EXPIRED,
        REVOKED
    }

    public enum QuestionStatus {
        OPEN,
        ANSWERED,
        CLOSED
    }

    public enum ArtifactProcessingStatus {
        RECEIVED,
        VALIDATED,
        APPLIED,
        REJECTED
    }

    public enum ArtifactNextAction {
        COMPLETE,
        ACCEPTANCE_REQUIRED,
        FAIL,
        STAY_APPLIED
    }

    public static <E extends Enum<E>> E requiredEnum(
            Class<E> enumType,
            String value,
            String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(field + " is invalid: " + value, ex);
        }
    }

    public static String requiredKey(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException(field + " is invalid: " + value);
        }
        return normalized;
    }

    public static String requiredText(
            String value,
            String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public static String optionalText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public static String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    public static void requireSchema(String actual, String expected, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " must be " + expected);
        }
    }

    public static String requiredText(
            String value,
            String field,
            int maxCharacters) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return requireMaxCharacters(value.trim(), field, maxCharacters);
    }

    public static String optionalText(
            String value,
            String field,
            int maxCharacters) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return requireMaxCharacters(value.trim(), field, maxCharacters);
    }

    public static String requireMaxCharacters(
            String value,
            String field,
            int maxCharacters) {
        if (value == null) {
            return null;
        }
        int characters = value.codePointCount(0, value.length());
        if (characters > maxCharacters) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxCharacters + " characters");
        }
        return value;
    }

    public static String requiredTextUtf8(
            String value,
            String field,
            int maxBytes) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return requireMaxUtf8Bytes(value.trim(), field, maxBytes);
    }

    public static String requireMaxUtf8Bytes(
            String value,
            String field,
            int maxBytes) {
        if (value != null
                && value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxBytes + " UTF-8 bytes");
        }
        return value;
    }

    public static String fitText(String value, int maxCharacters) {
        if (value == null
                || value.codePointCount(0, value.length()) <= maxCharacters) {
            return value;
        }
        if (maxCharacters < 2) {
            return value.substring(0, value.offsetByCodePoints(0, maxCharacters));
        }
        int end = value.offsetByCodePoints(0, maxCharacters - 1);
        return value.substring(0, end) + "…";
    }
}
