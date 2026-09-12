package com.enterprise.ai.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real MySQL and production mappers in isolated clones; deliberately reuses keys without restarting the server. */
@EnabledIfSystemProperty(named = "reachai.mysql.publicationVerification", matches = "true")
class KnowledgeRecordGenerationIT {
    @Test void insertionAndUpdateIdentity() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            new KnowledgeRecordGenerationTest.Context(source).verifyInsertionAndUpdates();
        }
    }
    @Test void requestsRejectReusedPrimaryKeys() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            new KnowledgeRecordGenerationTest.Context(source).verifyRequestIdentity();
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reembeddingRejectsReusedPrimaryKeys(boolean registered) throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            new KnowledgeRecordGenerationTest.Context(source).verifyReembedding(registered);
        }
    }
    @Test void unchangedIdentityCanPublishAndTargetsAreFrozen() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            var context = new KnowledgeRecordGenerationTest.Context(source);
            context.verifyFrozenTargets(); context.verifySuccessfulPublication();
        }
    }
}
