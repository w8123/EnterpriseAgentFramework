package com.enterprise.ai.pipeline.document;

/**
 * Stable error codes exposed by the import job and UI.  Only explicitly
 * transient errors may be retried, and a retry always uses the same provider.
 */
public enum DocumentParseErrorCode {
    UNSUPPORTED_FORMAT(false),
    FORMAT_MISMATCH(false),
    INVALID_TEXT_ENCODING(false),
    EMPTY_CONTENT(false),
    FILE_TOO_LARGE(false),
    DOCLING_DISABLED(false),
    DOCLING_UNAVAILABLE(true),
    DOCLING_TIMEOUT(true),
    DOCLING_RESPONSE_INVALID(true),
    DOCLING_RESPONSE_TOO_LARGE(false),
    DOCLING_REJECTED(false),
    DOCLING_PARTIAL_SUCCESS(false),
    DOCLING_EMPTY_OUTPUT(false),
    INTERNAL_ERROR(false);

    private final boolean retryable;

    DocumentParseErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
