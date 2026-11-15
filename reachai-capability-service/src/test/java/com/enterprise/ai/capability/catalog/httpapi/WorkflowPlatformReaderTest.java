package com.enterprise.ai.capability.catalog.httpapi;

import com.enterprise.ai.common.capability.HttpApiJsonBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Dify apps and n8n webhooks offered as API candidates (no network: the fetcher is stubbed). */
class WorkflowPlatformReaderTest {
    private final ObjectMapper json = new ObjectMapper();

    private static final class StubFetcher extends HttpApiDocumentFetcher {
        final Map<String, String> responses;
        final List<String> calls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();

        StubFetcher(Map<String, String> responses) {
            super(false);
            this.responses = responses;
        }

        @Override
        public Fetched fetchJson(String rawUrl, Map<String, String> sent) {
            calls.add(rawUrl);
            headers.add(sent);
            String body = responses.get(rawUrl);
            if (body == null) throw new IllegalArgumentException("文档地址返回 HTTP 404");
            if ("401".equals(body)) throw new IllegalArgumentException("文档地址需要登录（HTTP 401）。可以填写 Authorization");
            return new Fetched(rawUrl, body);
        }
    }

    private Map<String, HttpApiDocumentImportService.Candidate> byKey(HttpApiDocumentImportService.Preview preview) {
        return preview.candidates().stream().collect(Collectors.toMap(HttpApiDocumentImportService.Candidate::key, c -> c));
    }

    @Test
    void difyWorkflowAppBecomesWorkflowsRunWithInputForm() {
        StubFetcher fetcher = new StubFetcher(Map.of(
                "https://dify.example.com/v1/info", "{\"name\":\"合同审查\",\"description\":\"检查合同风险\",\"mode\":\"workflow\"}",
                "https://dify.example.com/v1/parameters", """
                        {"user_input_form":[
                          {"text-input":{"label":"合同标题","variable":"title","required":true,"max_length":48}},
                          {"select":{"label":"类型","variable":"kind","required":false,"options":["采购","销售"]}},
                          {"number":{"label":"金额","variable":"amount","required":false}},
                          {"file":{"label":"合同文件","variable":"doc","required":false}}
                        ]}"""));
        HttpApiDocumentImportService.Preview preview = new WorkflowPlatformReader(json, fetcher)
                .read(new WorkflowPlatformReader.Request("dify", "https://dify.example.com/", "app-secret", null), "");

        assertEquals("DIFY", preview.format());
        assertEquals("合同审查", preview.title());
        assertEquals("Bearer app-secret", fetcher.headers.get(0).get("Authorization"));
        HttpApiDocumentImportService.Candidate run = byKey(preview).get("POST /v1/workflows/run");
        assertNotNull(run);
        assertEquals(1, preview.candidates().size());
        assertEquals("dify", run.request().origin());
        assertEquals("BEARER", run.request().authentication().mode());
        assertEquals("https://dify.example.com", run.host());
        var body = run.request().requestBody().schema();
        assertNull(HttpApiJsonBinding.unsupportedReason(body));
        assertEquals("blocking", body.at("/properties/response_mode/enum/0").asText());
        assertEquals("title", body.at("/properties/inputs/required/0").asText());
        assertEquals("采购", body.at("/properties/inputs/properties/kind/enum/0").asText());
        assertEquals("number", body.at("/properties/inputs/properties/amount/type").asText());
        assertTrue(body.at("/properties/inputs/properties/doc").isMissingNode());
        assertTrue(preview.warnings().stream().anyMatch(w -> w.contains("合同文件")));
        assertFalse(json.valueToTree(preview).toString().contains("app-secret"));
    }

    @Test
    void difyChatAppAndUnknownMode() {
        StubFetcher chat = new StubFetcher(Map.of(
                "https://api.dify.ai/v1/info", "{\"name\":\"客服\",\"mode\":\"advanced-chat\"}",
                "https://api.dify.ai/v1/parameters", "{\"user_input_form\":[]}"));
        var preview = new WorkflowPlatformReader(json, chat).read(new WorkflowPlatformReader.Request("DIFY", "https://api.dify.ai/v1", "k", null), null);
        var message = byKey(preview).get("POST /v1/chat-messages");
        assertNotNull(message);
        assertEquals("READ_ONLY", message.request().sideEffect());
        assertEquals("query", message.request().requestBody().schema().at("/required/0").asText());

        StubFetcher old = new StubFetcher(Map.of(
                "https://api.dify.ai/v1/info", "{\"name\":\"旧版\"}",
                "https://api.dify.ai/v1/parameters", "{\"user_input_form\":[]}"));
        var both = new WorkflowPlatformReader(json, old).read(new WorkflowPlatformReader.Request("DIFY", "https://api.dify.ai/v1", "k", null), null);
        assertEquals(2, both.candidates().size());
    }

    @Test
    void difyWrongKeyIsExplained() {
        StubFetcher fetcher = new StubFetcher(Map.of("https://api.dify.ai/v1/info", "401"));
        var reader = new WorkflowPlatformReader(json, fetcher);
        var error = assertThrows(IllegalArgumentException.class,
                () -> reader.read(new WorkflowPlatformReader.Request("DIFY", "https://api.dify.ai/v1", "bad", null), null));
        assertTrue(error.getMessage().contains("API 密钥"));
    }

    @Test
    void n8nListsWebhookWorkflowsThroughThePublicApi() {
        String page = """
                {"data":[
                  {"id":"1","name":"工单分派","active":true,"nodes":[
                    {"type":"n8n-nodes-base.webhook","parameters":{"httpMethod":"POST","path":"ticket","authentication":"headerAuth","responseMode":"lastNode"},"webhookId":"w1","notes":"传 title 和 priority"},
                    {"type":"n8n-nodes-base.slack","parameters":{}}]},
                  {"id":"2","name":"查订单","active":false,"nodes":[
                    {"type":"n8n-nodes-base.webhook","parameters":{"path":"order/:orderNo","responseMode":"responseNode"},"webhookId":"w2"}]},
                  {"id":"3","name":"定时报表","active":true,"nodes":[{"type":"n8n-nodes-base.scheduleTrigger","parameters":{}}]}
                ],"nextCursor":null}""";
        StubFetcher fetcher = new StubFetcher(Map.of("https://n8n.example.com/api/v1/workflows?limit=100", page));
        var preview = new WorkflowPlatformReader(json, fetcher)
                .read(new WorkflowPlatformReader.Request("N8N", "https://n8n.example.com/", "n8n-key", null), "");

        assertEquals("n8n-key", fetcher.headers.get(0).get("X-N8N-API-KEY"));
        var candidates = byKey(preview);
        var ticket = candidates.get("POST /webhook/ticket");
        assertNotNull(ticket);
        assertEquals("工单分派", ticket.request().summary());
        assertTrue(ticket.request().description().contains("传 title 和 priority"));
        assertEquals("API_KEY_HEADER", ticket.request().authentication().mode());
        assertEquals("WRITE", ticket.request().sideEffect());
        assertNull(HttpApiJsonBinding.unsupportedReason(ticket.request().requestBody().schema()));
        var order = candidates.get("GET /webhook/w2/order/{orderNo}");
        assertNotNull(order, candidates.keySet().toString());
        assertEquals("orderNo", order.request().parameters().get(0).name());
        assertNull(order.request().requestBody());
        assertTrue(order.warnings().contains("工作流还没激活"));
        assertEquals(2, preview.candidates().size());
    }

    @Test
    void n8nPastedWebhookUrls() {
        var preview = new WorkflowPlatformReader(json, new StubFetcher(Map.of())).read(new WorkflowPlatformReader.Request("n8n", null, null,
                "https://n8n.example.com/webhook/ticket\nGET https://n8n.example.com/webhook-test/report\nnot a url"), "");
        var candidates = byKey(preview);
        assertEquals("POST", candidates.get("POST /webhook/ticket").request().httpMethod());
        assertTrue(candidates.get("GET /webhook-test/report").warnings().get(0).contains("测试地址"));
        assertEquals(1, preview.skipped());
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPlatformReader(json, new StubFetcher(Map.of())).read(new WorkflowPlatformReader.Request("n8n", null, null, null), ""));
    }
}
