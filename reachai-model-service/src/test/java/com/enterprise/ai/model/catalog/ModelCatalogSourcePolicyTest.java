package com.enterprise.ai.model.catalog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelCatalogSourcePolicyTest {

    @Test
    void acceptsExactApprovedHttpsHost() {
        ModelCatalogSourceEntity source = source(
                "https://developers.openai.com/api/docs/models/all.md",
                "developers.openai.com");

        assertEquals("developers.openai.com", ModelCatalogSourcePolicy.validate(source).getHost());
    }

    @Test
    void rejectsDatabaseControlledUnapprovedHost() {
        ModelCatalogSourceEntity source = source("https://127.0.0.1/internal", "127.0.0.1");

        ModelCatalogSyncException error = assertThrows(
                ModelCatalogSyncException.class,
                () -> ModelCatalogSourcePolicy.validate(source));

        assertEquals("MODEL_CATALOG_SOURCE_NOT_ALLOWED", error.code());
    }

    @Test
    void rejectsHostMismatchAndHttp() {
        assertThrows(ModelCatalogSyncException.class, () -> ModelCatalogSourcePolicy.validate(source(
                "https://api.openai.com/v1/models", "developers.openai.com")));
        assertThrows(ModelCatalogSyncException.class, () -> ModelCatalogSourcePolicy.validate(source(
                "http://developers.openai.com/api/docs/models/all", "developers.openai.com")));
    }

    private ModelCatalogSourceEntity source(String url, String host) {
        ModelCatalogSourceEntity source = new ModelCatalogSourceEntity();
        source.setSourceUrl(url);
        source.setAllowedHost(host);
        return source;
    }
}
