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

    @Test
    void replacingTheSamePrimaryKeyLeavesOneCurrentVector() throws Exception {
        String host = System.getProperty("reachai.live.milvus.host");
        int port = Integer.parseInt(System.getProperty("reachai.live.milvus.port", "19530"));
        String collection = "reachai_it_upsert_" + UUID.randomUUID().toString().replace("-", "");
        MilvusServiceClient client = new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build());
        MilvusVectorService service = new MilvusVectorService(client);
        try {
            service.ensureCollection(collection, 2);
            service.upsert(collection, List.of("file_live_chunk_0"), List.of(List.of(1.0f, 0.0f)),
                    List.of("file_live"), List.of("first"));
            flush(client, collection);
            service.upsert(collection, List.of("file_live_chunk_0"), List.of(List.of(0.0f, 1.0f)),
                    List.of("file_live"), List.of("second"));
            flush(client, collection);

            List<VectorSearchResult> results = waitForCurrentVector(service, collection);
            assertEquals(1, results.size());
            assertEquals("second", results.get(0).getFields().get("content"));
        } finally {
            try {
                service.dropCollection(collection);
            } finally {
                client.close();
            }
        }
    }

    private static void flush(MilvusServiceClient client, String collection) {
        client.flush(FlushParam.newBuilder()
                .addCollectionName(collection)
                .withSyncFlush(true)
                .build());
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
