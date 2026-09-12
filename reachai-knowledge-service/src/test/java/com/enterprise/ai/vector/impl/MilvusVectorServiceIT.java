package com.enterprise.ai.vector.impl;

import com.enterprise.ai.vector.VectorSearchRequest;
import com.enterprise.ai.vector.VectorSearchResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.collection.FlushParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Opt-in acceptance proving that the deployed Milvus version supports upsert. */
@EnabledIfSystemProperty(named = "reachai.live.milvus.host", matches = ".+")
class MilvusVectorServiceIT {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"none", "index", "load"})
    void replacingTheSamePrimaryKeyLeavesOneCurrentVector(String interruptedStage) throws Exception {
        String host = System.getProperty("reachai.live.milvus.host");
        int port = Integer.parseInt(System.getProperty("reachai.live.milvus.port", "19530"));
        String username = System.getProperty("reachai.live.milvus.username", System.getenv("MILVUS_USERNAME"));
        String password = System.getProperty("reachai.live.milvus.password", System.getenv("MILVUS_PASSWORD"));
        String collection = "reachai_it_upsert_" + UUID.randomUUID().toString().replace("-", "");
        ConnectParam.Builder connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port);
        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            connectParam.withAuthorization(username, password);
        }
        MilvusServiceClient client = org.mockito.Mockito.spy(new MilvusServiceClient(connectParam.build()));
        MilvusVectorService service = new MilvusVectorService(client);
        try {
            if (!interruptedStage.equals("none")) {
                var failed = io.milvus.param.R.failed(new IllegalStateException("injected provisioning interruption"));
                if (interruptedStage.equals("index")) {
                    org.mockito.Mockito.doReturn(failed).when(client).createIndex(org.mockito.ArgumentMatchers.any());
                } else {
                    org.mockito.Mockito.doReturn(failed).when(client).loadCollection(org.mockito.ArgumentMatchers.any());
                }
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.ensureCollection(collection, 2));
                org.mockito.Mockito.doCallRealMethod().when(client).createIndex(org.mockito.ArgumentMatchers.any());
                org.mockito.Mockito.doCallRealMethod().when(client).loadCollection(org.mockito.ArgumentMatchers.any());
            }
            service.ensureCollection(collection, 2);
            service.ensureCollection(collection, 2); // Existing collection must resume index/load safely.
            service.upsert(collection, List.of("file_live_chunk_0"), List.of(List.of(1.0f, 0.0f)),
                    List.of("file_live"), List.of("first"));
            flush(client, collection);
            service.upsert(collection, List.of("file_live_chunk_0"), List.of(List.of(0.0f, 1.0f)),
                    List.of("file_live"), List.of("second"));
            flush(client, collection);

            List<VectorSearchResult> results = waitForCurrentVector(service, collection);
            assertEquals(1, results.size());
            assertEquals("second", results.get(0).getFields().get("content"));
            service.deleteById(collection, "file_live_chunk_0");
            flush(client, collection);
            VectorSearchRequest deleted = VectorSearchRequest.builder().collectionName(collection)
                    .queryVector(List.of(0.0f, 1.0f)).topK(5).build();
            for (int attempt = 0; attempt < 20 && !service.search(deleted).isEmpty(); attempt++) {
                Thread.sleep(250);
            }
            assertEquals(0, service.search(deleted).size());
        } finally {
            try {
                service.dropCollection(collection);
            } finally {
                client.close();
            }
        }
    }

    private static void flush(MilvusServiceClient client, String collection) {
        var result = client.flush(FlushParam.newBuilder()
                .addCollectionName(collection)
                .withSyncFlush(true)
                .build());
        assertEquals(io.milvus.param.R.Status.Success.getCode(), result.getStatus());
    }

    private static List<VectorSearchResult> waitForCurrentVector(MilvusVectorService service,
                                                                  String collection) throws InterruptedException {
        VectorSearchRequest request = VectorSearchRequest.builder()
                .collectionName(collection)
                .queryVector(List.of(0.0f, 1.0f))
                .topK(5)
                .filterExpression("file_id == \"file_live\"")
                .outputFields(List.of("id", "file_id", "content"))
                .build();
        for (int attempt = 0; attempt < 20; attempt++) {
            List<VectorSearchResult> results = service.search(request);
            if (!results.isEmpty() && "second".equals(results.get(0).getFields().get("content"))) {
                return results;
            }
            Thread.sleep(250);
        }
        List<VectorSearchResult> results = service.search(request);
        assertFalse(results.isEmpty(), "Milvus upsert was not queryable within the acceptance window");
        return results;
    }
}
