package com.enterprise.ai.vector.impl;

import com.enterprise.ai.vector.VectorSearchRequest;
import com.enterprise.ai.vector.VectorSearchResult;
import com.enterprise.ai.vector.VectorService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorService implements VectorService {

    private final MilvusServiceClient milvusClient;

    @Override
    public void ensureCollection(String collectionName, int dimension) {
        if (collectionExists(collectionName)) {
            R<io.milvus.grpc.DescribeIndexResponse> indexes = milvusClient.describeIndex(
                    io.milvus.param.index.DescribeIndexParam.newBuilder().withCollectionName(collectionName).build());
            // Newer servers use IndexNotFound (700); SDK 2.4 also exposes the legacy enum code.
            boolean missing = indexes != null && (Objects.equals(indexes.getStatus(), 700)
                    || Objects.equals(indexes.getStatus(), io.milvus.grpc.ErrorCode.IndexNotExist.getNumber()));
            if (!missing) {
                requireSuccess(indexes, "describe index");
                if (indexes.getData() == null) throw new IllegalStateException("Milvus describe index returned no data");
                missing = indexes.getData().getIndexDescriptionsList().stream()
                        .noneMatch(index -> "vector".equals(index.getFieldName()));
            }
            if (missing) createVectorIndex(collectionName);
            loadCollection(collectionName);
            return;
        }

        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(128)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build();

        FieldType fileIdField = FieldType.newBuilder()
                .withName("file_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(128)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(8192)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName("vector")
                .withDataType(DataType.FloatVector)
                .withDimension(dimension)
                .build();

        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .addFieldType(idField)
                .addFieldType(fileIdField)
                .addFieldType(contentField)
                .addFieldType(vectorField)
                .build();

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withSchema(schema)
                .build();

        R<RpcStatus> createResult = milvusClient.withRetry(io.milvus.param.RetryParam.newBuilder()
                .withMaxRetryTimes(1).build()).createCollection(createParam);
        requireSuccess(createResult, "create collection");

        createVectorIndex(collectionName);
        loadCollection(collectionName);

        log.info("Collection {} created and loaded", collectionName);
    }

    private void createVectorIndex(String collectionName) {
        requireSuccess(milvusClient.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("vector")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":1024}")
                .build()), "create index");
    }

    private void loadCollection(String collectionName) {
        requireSuccess(milvusClient.loadCollection(
                LoadCollectionParam.newBuilder().withCollectionName(collectionName).build()), "load collection");
    }

    @Override
    public void upsert(String collectionName, List<String> ids, List<List<Float>> vectors,
                       List<String> fileIds, List<String> contents) {
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", ids));
        fields.add(new InsertParam.Field("file_id", fileIds));
        fields.add(new InsertParam.Field("content", contents));
        fields.add(new InsertParam.Field("vector", vectors));

        UpsertParam upsertParam = UpsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build();

        // A transport retry could leave an earlier RPC in flight after a later RPC succeeds.
        // The execution journal may acknowledge completion only for a single write request.
        R<MutationResult> result = milvusClient.withRetry(io.milvus.param.RetryParam.newBuilder()
                .withMaxRetryTimes(1).build()).upsert(upsertParam);
        requireSuccess(result, "upsert");
        log.info("Upserted {} vectors into {}", ids.size(), collectionName);
    }

    @Override
    public List<VectorSearchResult> search(VectorSearchRequest request) {
        SearchParam.Builder builder = SearchParam.newBuilder()
                .withCollectionName(request.getCollectionName())
                .withMetricType(MetricType.COSINE)
                .withTopK(request.getTopK())
                .withVectors(List.of(request.getQueryVector()))
                .withVectorFieldName("vector")
                .withOutFields(request.getOutputFields() != null
                        ? request.getOutputFields()
                        : List.of("id", "file_id", "content"))
                .withParams("{\"nprobe\":16}");

        if (request.getFilterExpression() != null && !request.getFilterExpression().isEmpty()) {
            builder.withExpr(request.getFilterExpression());
        }

        R<SearchResults> response = milvusClient.search(builder.build());
        requireSuccess(response, "search");

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<VectorSearchResult> results = new ArrayList<>();

        if (wrapper.getRowRecords(0) == null) {
            return results;
        }

        for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
            SearchResultsWrapper.IDScore idScore = wrapper.getIDScore(0).get(i);
            Map<String, Object> fields = new HashMap<>();
            var row = wrapper.getRowRecords(0).get(i);
            for (String fieldName : row.getFieldValues().keySet()) {
                fields.put(fieldName, row.get(fieldName));
            }
            results.add(VectorSearchResult.builder()
                    .id(String.valueOf(idScore.getStrID()))
                    .score(idScore.getScore())
                    .fields(fields)
                    .build());
        }

        return results;
    }

    @Override
    public void deleteById(String collectionName, String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Vector identity is required");
        String expr = "id == \"" + new String(com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance().quoteAsString(id)) + "\"";
        R<MutationResult> result = milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build());
        // A retired collection may already have been dropped. A successful existence read is
        // required before treating a failed delete as absence; authentication/transport errors propagate.
        if (result != null && !Objects.equals(result.getStatus(), R.Status.Success.getCode()) && !collectionExists(collectionName)) return;
        requireSuccess(result, "delete by id");
        log.info("Deleted vector id={} from {}", id, collectionName);
    }

    @Override
    public void dropCollection(String collectionName) {
        if (!collectionExists(collectionName)) return;
        requireSuccess(milvusClient.withRetry(io.milvus.param.RetryParam.newBuilder().withMaxRetryTimes(1).build()).dropCollection(
                DropCollectionParam.newBuilder().withCollectionName(collectionName).build()), "drop collection");
        log.info("Dropped collection {}", collectionName);
    }

    private boolean collectionExists(String collectionName) {
        R<Boolean> result = milvusClient.hasCollection(HasCollectionParam.newBuilder().withCollectionName(collectionName).build());
        requireSuccess(result, "check collection");
        if (result.getData() == null) throw new IllegalStateException("Milvus collection existence returned no result");
        return result.getData();
    }

    private static void requireSuccess(R<?> result, String operation) {
        if (result == null || result.getException() != null
                || !Objects.equals(result.getStatus(), R.Status.Success.getCode())) {
            throw new IllegalStateException("Milvus " + operation + " failed (status="
                    + (result == null ? "missing" : result.getStatus()) + ")",
                    result == null ? null : result.getException());
        }
    }
}
