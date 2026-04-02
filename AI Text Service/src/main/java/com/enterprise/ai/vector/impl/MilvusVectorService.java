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
        R<Boolean> hasCollection = milvusClient.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collectionName).build());
        if (hasCollection.getData() != null && hasCollection.getData()) {
            log.debug("Collection {} already exists", collectionName);
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

        R<RpcStatus> createResult = milvusClient.createCollection(createParam);
        if (createResult.getException() != null) {
            throw new RuntimeException("Failed to create collection: " + collectionName, createResult.getException());
        }

        milvusClient.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("vector")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":1024}")
                .build());

        milvusClient.loadCollection(
                LoadCollectionParam.newBuilder().withCollectionName(collectionName).build());

        log.info("Collection {} created and loaded", collectionName);
    }

    @Override
    public void insert(String collectionName, List<String> ids, List<List<Float>> vectors,
                       List<String> fileIds, List<String> contents) {
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", ids));
        fields.add(new InsertParam.Field("file_id", fileIds));
        fields.add(new InsertParam.Field("content", contents));
        fields.add(new InsertParam.Field("vector", vectors));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build();

        R<MutationResult> result = milvusClient.insert(insertParam);
        if (result.getException() != null) {
            throw new RuntimeException("Milvus insert failed", result.getException());
        }
        log.info("Inserted {} vectors into {}", ids.size(), collectionName);
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
        if (response.getException() != null) {
            throw new RuntimeException("Milvus search failed", response.getException());
        }

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
    public void deleteByFileId(String collectionName, String fileId) {
        String expr = "file_id == \"" + fileId + "\"";
        R<MutationResult> result = milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build());
        if (result.getException() != null) {
            throw new RuntimeException("Milvus delete failed", result.getException());
        }
        log.info("Deleted vectors with file_id={} from {}", fileId, collectionName);
    }

    @Override
    public void dropCollection(String collectionName) {
        milvusClient.dropCollection(
                DropCollectionParam.newBuilder().withCollectionName(collectionName).build());
        log.info("Dropped collection {}", collectionName);
    }
}
