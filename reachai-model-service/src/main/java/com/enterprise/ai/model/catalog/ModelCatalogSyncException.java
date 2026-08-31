package com.enterprise.ai.model.catalog;

public class ModelCatalogSyncException extends RuntimeException {

    private final String code;
    private final boolean retriable;

    public ModelCatalogSyncException(String code, String message, boolean retriable) {
        super(message);
        this.code = code;
        this.retriable = retriable;
    }

    public ModelCatalogSyncException(String code, String message, boolean retriable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retriable = retriable;
    }

    public String code() {
        return code;
    }

    public boolean retriable() {
        return retriable;
    }
}
