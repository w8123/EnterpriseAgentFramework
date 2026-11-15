package com.enterprise.ai.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalKnowledgeProvidersTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> requests = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> headers = new ConcurrentHashMap<>();
    private HttpServer server;
    private String base;
    private final ExternalKnowledgeHttp http = new ExternalKnowledgeHttp(5, true);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (body.length > 0) requests.put(path, json.readTree(body));
            headers.put(path, Map.of(
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "clientid", String.valueOf(exchange.getRequestHeaders().getFirst("ima-openapi-clientid")),
                    "apikey", String.valueOf(exchange.getRequestHeaders().getFirst("ima-openapi-apikey"))));
            String answer = responses.get(path);
            byte[] out = (answer == null ? "{\"error\":\"missing\"}" : answer).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer == null ? 404 : 200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void ragflowListsDatasetsAndRetrievesChunks() {
        responses.put("/api/v1/datasets", "{\"code\":0,\"data\":[{\"id\":\"ds1\",\"name\":\"制度\"},{\"id\":\"ds2\",\"name\":\"产品\"}]}");
        responses.put("/api/v1/retrieval", "{\"code\":0,\"data\":{\"chunks\":[{\"id\":\"c1\",\"content\":\"退货 7 天内可申请\","
                + "\"document_id\":\"d1\",\"document_keyword\":\"售后制度.pdf\",\"similarity\":0.83},{\"id\":\"c2\",\"content\":\"\"}],\"total\":2}}");
        var provider = new RagflowKnowledgeProvider(http);
        // A pasted address that already ends with /api/v1 must not be doubled.
        var connection = new ExternalKnowledgeConnection("RAGFLOW", base + "/api/v1/", null, "rk-1",
                List.of(new ExternalKnowledgeConnection.RemoteDataset("ds1", "制度")));

        assertThat(provider.listDatasets(connection)).extracting(ExternalKnowledgeConnection.RemoteDataset::name).containsExactly("制度", "产品");
        var hits = provider.search(connection, "退货多久", 3, 0.3f);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.documentName()).isEqualTo("售后制度.pdf");
            assertThat(hit.score()).isEqualTo(0.83f);
        });
        assertThat(headers.get("/api/v1/retrieval").get("authorization")).isEqualTo("Bearer rk-1");
        JsonNode sent = requests.get("/api/v1/retrieval");
        assertThat(sent.path("question").asText()).isEqualTo("退货多久");
        assertThat(sent.path("dataset_ids").get(0).asText()).isEqualTo("ds1");
        assertThat(sent.path("page_size").asInt()).isEqualTo(3);
    }

    @Test
    void ragflowErrorEnvelopeBecomesReadableFailure() {
        responses.put("/api/v1/datasets", "{\"code\":109,\"message\":\"Authentication error: API key is invalid!\"}");
        var provider = new RagflowKnowledgeProvider(http);
        var connection = new ExternalKnowledgeConnection("RAGFLOW", base, null, "bad", List.of());

        assertThatThrownBy(() -> provider.listDatasets(connection))
                .isInstanceOf(ExternalKnowledgeException.class).hasMessageContaining("API Key");
    }

    @Test
    void imaListsKnowledgeBasesAndScoresSnippetsByRank() {
        responses.put("/openapi/wiki/v1/search_knowledge_base", "{\"code\":0,\"msg\":\"ok\",\"data\":{\"info_list\":[{\"id\":\"kb1\",\"name\":\"团队资料\"}],\"is_end\":true,\"next_cursor\":\"\"}}");
        responses.put("/openapi/wiki/v1/get_addable_knowledge_base_list", "{\"code\":0,\"data\":{\"addable_knowledge_base_list\":[{\"id\":\"kb1\",\"name\":\"团队资料\"},{\"id\":\"kb2\",\"name\":\"共享\"}],\"is_end\":true}}");
        responses.put("/openapi/wiki/v1/search_knowledge", "{\"code\":0,\"data\":{\"info_list\":["
                + "{\"media_id\":\"m1\",\"title\":\"报销制度\",\"highlight_content\":\"差旅<em>报销</em>需在 30 天内提交\"},"
                + "{\"media_id\":\"m2\",\"title\":\"员工手册\",\"highlight_content\":\"报销流程见附件\"},"
                + "{\"media_id\":\"f1\",\"title\":\"\",\"highlight_content\":\"\"}],\"is_end\":true}}");
        var provider = new ImaKnowledgeProvider(http);
        var connection = new ExternalKnowledgeConnection("IMA", base, "cid", "key",
                List.of(new ExternalKnowledgeConnection.RemoteDataset("kb1", "团队资料")));

        assertThat(provider.listDatasets(connection)).extracting(ExternalKnowledgeConnection.RemoteDataset::id).containsExactly("kb1", "kb2");
        var hits = provider.search(connection, "报销", 5, 0.9f);

        assertThat(hits).extracting(ExternalKnowledgeHit::content)
                .containsExactly("《报销制度》差旅报销需在 30 天内提交", "《员工手册》报销流程见附件");
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
        assertThat(headers.get("/openapi/wiki/v1/search_knowledge")).containsEntry("clientid", "cid").containsEntry("apikey", "key");
        assertThat(requests.get("/openapi/wiki/v1/search_knowledge").path("knowledge_base_id").asText()).isEqualTo("kb1");
    }

    @Test
    void imaNonZeroCodeIsReported() {
        responses.put("/openapi/wiki/v1/search_knowledge_base", "{\"code\":110001,\"msg\":\"鉴权失败\"}");
        var provider = new ImaKnowledgeProvider(http);
        var connection = new ExternalKnowledgeConnection("IMA", base, "cid", "key", List.of());

        assertThatThrownBy(() -> provider.listDatasets(connection)).hasMessageContaining("鉴权失败");
    }

    @Test
    void difyRetrievesRecordsAndAppliesThreshold() {
        responses.put("/v1/datasets", "{\"data\":[{\"id\":\"d-1\",\"name\":\"FAQ\"}],\"has_more\":false}");
        responses.put("/v1/datasets/d-1/retrieve", "{\"records\":[{\"segment\":{\"id\":\"s1\",\"content\":\"营业时间 9 点到 18 点\","
                + "\"document\":{\"id\":\"doc1\",\"name\":\"FAQ.md\"}},\"score\":0.71},{\"segment\":{\"id\":\"s2\",\"content\":\"无关\",\"document\":{}},\"score\":0.1}]}");
        var provider = new DifyKnowledgeProvider(http);
        var connection = new ExternalKnowledgeConnection("DIFY", base, null, "dk",
                List.of(new ExternalKnowledgeConnection.RemoteDataset("d-1", "FAQ")));

        assertThat(provider.listDatasets(connection)).hasSize(1);
        assertThat(provider.search(connection, "几点上班", 5, 0.3f)).singleElement()
                .satisfies(hit -> assertThat(hit.documentName()).isEqualTo("FAQ.md"));
        assertThat(headers.get("/v1/datasets/d-1/retrieve").get("authorization")).isEqualTo("Bearer dk");
    }

    @Test
    void notFoundIsExplained() {
        var provider = new DifyKnowledgeProvider(http);
        var connection = new ExternalKnowledgeConnection("DIFY", base + "/wrong", null, "dk", List.of());

        assertThatThrownBy(() -> provider.listDatasets(connection)).hasMessageContaining("404");
    }
}
