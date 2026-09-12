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
    void exactIdentityDeletionQuotesLegacyCharactersAsOneLiteral() throws Exception {
        var client = mock(MilvusServiceClient.class);
        when(client.delete(any(io.milvus.param.dml.DeleteParam.class))).thenReturn(R.success(MutationResult.newBuilder().build()));
        String identity = "历史\\向量\" || id != \"\n\t";
        new MilvusVectorService(client).deleteById("legacy_collection", identity);
        var deletion = ArgumentCaptor.forClass(io.milvus.param.dml.DeleteParam.class);
        verify(client).delete(deletion.capture());
        String expression = deletion.getValue().getExpr();
        org.junit.jupiter.api.Assertions.assertTrue(expression.startsWith("id == "));
        assertEquals(identity, new com.fasterxml.jackson.databind.ObjectMapper().readValue(expression.substring(6), String.class));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"indexed", "empty", "missing", "missing700", "denied"})
    void existingCollectionResumesIndexAndLoadWithoutReplacingExistingIndex(String state) {
        var client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any())).thenReturn(R.success(true));
        R<io.milvus.grpc.DescribeIndexResponse> response;
        if (state.equals("missing")) response = R.failed(io.milvus.grpc.ErrorCode.IndexNotExist, "missing");
        else if (state.equals("missing700")) response = R.failed(new io.milvus.exception.ServerException("index not found", 700, io.milvus.grpc.ErrorCode.IndexNotExist));
        else if (state.equals("denied")) response = R.failed(io.milvus.grpc.ErrorCode.PermissionDenied, "denied");
        else {
            var description = io.milvus.grpc.DescribeIndexResponse.newBuilder();
            if (state.equals("indexed")) description.addIndexDescriptions(io.milvus.grpc.IndexDescription.newBuilder().setFieldName("vector").setIndexName("custom-index"));
            response = R.success(description.build());
        }
        when(client.describeIndex(any())).thenReturn(response);
        when(client.createIndex(any())).thenReturn(R.success());
        when(client.loadCollection(any())).thenReturn(R.success());
        var service = new MilvusVectorService(client);
        if (state.equals("denied")) {
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.ensureCollection("kb", 2));
            verify(client, never()).loadCollection(any());
        } else {
            service.ensureCollection("kb", 2);
            verify(client).loadCollection(any());
        }
        if (state.equals("empty") || state.startsWith("missing")) verify(client).createIndex(any());
        else verify(client, never()).createIndex(any());
        verify(client, never()).createCollection(any(io.milvus.param.collection.CreateCollectionParam.class));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"exists", "create", "index", "load", "drop"})
    void collectionFailuresArePropagated(String operation) {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.withRetry(any(io.milvus.param.RetryParam.class))).thenReturn(client);
        when(client.hasCollection(any())).thenReturn(operation.equals("exists")
                ? R.failed(new IllegalStateException("exists failed")) : R.success(operation.equals("drop")));
        when(client.createCollection(any(io.milvus.param.collection.CreateCollectionParam.class))).thenReturn(operation.equals("create")
                ? R.failed(new IllegalStateException("create failed")) : R.success());
        when(client.createIndex(any())).thenReturn(operation.equals("index")
                ? R.failed(new IllegalStateException("index failed")) : R.success());
        when(client.loadCollection(any())).thenReturn(operation.equals("load")
                ? R.failed(new IllegalStateException("load failed")) : R.success());
        when(client.dropCollection(any())).thenReturn(R.failed(new IllegalStateException("drop failed")));
        var service = new MilvusVectorService(client);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> {
            if (operation.equals("drop")) service.dropCollection("kb");
            else service.ensureCollection("kb", 2);
        });
        if (operation.equals("exists")) verify(client, never()).createCollection(any(io.milvus.param.collection.CreateCollectionParam.class));
        if (operation.equals("create")) verify(client, never()).createIndex(any());
        if (operation.equals("index")) verify(client, never()).loadCollection(any());
    }

    @Test
    void aMissingCollectionIsAnIdempotentDropButUnknownExistenceIsAnError() {
        var client = mock(MilvusServiceClient.class);var service = new MilvusVectorService(client);
        when(client.hasCollection(any())).thenReturn(R.success(false));
        service.dropCollection("retired");verify(client,never()).dropCollection(any());
        when(client.hasCollection(any())).thenReturn(R.failed(new IllegalStateException("existence unavailable")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->service.dropCollection("retired"));
        when(client.hasCollection(any())).thenReturn(R.success(null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->service.dropCollection("retired"));
    }

    @Test
    void aFailedVectorDeleteRequiresConfirmedCollectionAbsence() {
        var client = mock(MilvusServiceClient.class);var service = new MilvusVectorService(client);
        when(client.delete(any(io.milvus.param.dml.DeleteParam.class))).thenReturn(R.failed(new IllegalStateException("delete unavailable")));
        when(client.hasCollection(any())).thenReturn(R.success(false));
        service.deleteById("retired","vector");
        when(client.hasCollection(any())).thenReturn(R.success(true));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->service.deleteById("retired","vector"));
        when(client.hasCollection(any())).thenReturn(R.failed(new IllegalStateException("existence unavailable")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->service.deleteById("retired","vector"));
    }

    @Test
    void collectionCreationDisablesImplicitTransportRetry() {
        var client=mock(MilvusServiceClient.class);var single=mock(MilvusServiceClient.class);
        when(client.hasCollection(any())).thenReturn(R.success(false));
        when(client.withRetry(any(io.milvus.param.RetryParam.class))).thenReturn(single);
        when(single.createCollection(any(io.milvus.param.collection.CreateCollectionParam.class))).thenReturn(R.success());
        when(client.createIndex(any())).thenReturn(R.success());when(client.loadCollection(any())).thenReturn(R.success());
        new MilvusVectorService(client).ensureCollection("new_collection",2);
        var retry=ArgumentCaptor.forClass(io.milvus.param.RetryParam.class);verify(client).withRetry(retry.capture());
        assertEquals(1,retry.getValue().getMaxRetryTimes());verify(single).createCollection(any(io.milvus.param.collection.CreateCollectionParam.class));
        verify(client,never()).createCollection(any(io.milvus.param.collection.CreateCollectionParam.class));
    }

    @Test
    void writesDeterministicIdsWithUpsertInsteadOfInsert() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        MilvusServiceClient singleWrite = mock(MilvusServiceClient.class);
        when(client.withRetry(any(io.milvus.param.RetryParam.class))).thenReturn(singleWrite);
        when(singleWrite.upsert(any(UpsertParam.class))).thenReturn(R.<MutationResult>success());
        MilvusVectorService service = new MilvusVectorService(client);

        service.upsert("kb_contract", List.of("file_1_chunk_0"),
                List.of(List.of(0.1f, 0.2f)), List.of("file_1"), List.of("contract text"));

        ArgumentCaptor<UpsertParam> request = ArgumentCaptor.forClass(UpsertParam.class);
        verify(singleWrite).upsert(request.capture());
        var retry = ArgumentCaptor.forClass(io.milvus.param.RetryParam.class);
        verify(client).withRetry(retry.capture());
        assertEquals(1, retry.getValue().getMaxRetryTimes());
        verify(client, never()).upsert(any(UpsertParam.class));
        verify(client, never()).insert(any(InsertParam.class));
        assertEquals("kb_contract", request.getValue().getCollectionName());
        assertEquals(1, request.getValue().getRowCount());
    }
}
