package com.enterprise.ai.pipeline.document;

public interface DocumentParseProvider {

    DocumentProviderType getProviderType();

    DocumentParseResult parse(DocumentParseRequest request);
}
