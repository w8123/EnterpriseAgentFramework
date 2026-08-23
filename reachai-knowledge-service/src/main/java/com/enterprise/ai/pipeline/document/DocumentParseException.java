package com.enterprise.ai.pipeline.document;

import lombok.Getter;

@Getter
public class DocumentParseException extends RuntimeException {

    private final DocumentParseErrorCode errorCode;

    public DocumentParseException(DocumentParseErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public DocumentParseException(DocumentParseErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public boolean isRetryable() {
        return errorCode.isRetryable();
    }
}
