package com.enterprise.ai.external;

import java.util.List;

/**
 * Connector to one kind of external knowledge base system. Adding a new system (FastGPT, MaxKB…) means one more
 * Spring bean implementing this interface; retrieval, the console and hit logs pick it up by {@link #type()}.
 */
public interface ExternalKnowledgeProvider {

    /** Stable upper-case identifier stored in knowledge_base.external_provider, e.g. RAGFLOW. */
    String type();

    /** Chinese display name for the console. */
    String displayName();

    /** Default address when the system is a hosted service (ima); null when the user must enter one. */
    default String defaultBaseUrl() { return null; }

    /** Whether the system needs a client ID besides the API key (ima). */
    default boolean requiresClientId() { return false; }

    /** Lists the remote datasets the credentials can read. Also serves as the connection test. */
    List<ExternalKnowledgeConnection.RemoteDataset> listDatasets(ExternalKnowledgeConnection connection);

    /** Searches the connection's selected datasets. Failures throw; an empty list means no match. */
    List<ExternalKnowledgeHit> search(ExternalKnowledgeConnection connection, String query, int topK, float threshold);
}
