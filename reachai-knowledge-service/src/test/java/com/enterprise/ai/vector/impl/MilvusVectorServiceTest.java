package com.enterprise.ai.vector.impl;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.UpsertParam;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusVectorServiceTest {

    @Test
    void writesDeterministicIdsWithUpsertInsteadOfInsert() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.upsert(any(UpsertParam.class))).thenReturn(R.<MutationResult>success());
        MilvusVectorService service = new MilvusVectorService(client);

        service.upsert("kb_contract", List.of("file_1_chunk_0"),
                List.of(List.of(0.1f, 0.2f)), List.of("file_1"), List.of("contract text"));

        ArgumentCaptor<UpsertParam> request = ArgumentCaptor.forClass(UpsertParam.class);
        verify(client).upsert(request.capture());
        verify(client, never()).insert(any(InsertParam.class));
        assertEquals("kb_contract", request.getValue().getCollectionName());
        assertEquals(1, request.getValue().getRowCount());
    }
}
