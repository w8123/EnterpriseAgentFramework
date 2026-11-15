package com.enterprise.ai.capability.catalog.httpapi;

import com.enterprise.ai.common.capability.HttpApiJsonBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Whole-document parsing for the batch import: OpenAPI 3 (YAML), Swagger 2.0, Postman v2.1 and HAR. */
class HttpApiDocumentImportServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpApiDocumentImportService service = new HttpApiDocumentImportService(json,
            mock(com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper.class), mock(HttpApiAssetMapper.class),
            mock(ManualHttpApiService.class), new HttpApiDocumentFetcher(false));

    private Map<String, HttpApiDocumentImportService.Candidate> byKey(HttpApiDocumentImportService.Preview preview) {
        return preview.candidates().stream().collect(Collectors.toMap(HttpApiDocumentImportService.Candidate::key, c -> c));
    }

    @Test
    void openApi3YamlWithRefsAllOfSecurityAndServerPrefix() {
        String yaml = """
                openapi: 3.0.1
                info: {title: 芋道 ERP, version: '2.0'}
                servers: [{url: 'https://erp.example.com/admin-api'}]
                security: [{bearer: []}]
                tags: [{name: 库存}]
                paths:
                  /erp/stock/page:
                    get:
                      tags: [ERP 库存]
                      summary: 获得库存分页
                      parameters:
                        - {name: pageNo, in: query, required: true, schema: {type: integer, format: int32}}
                        - {name: ids, in: query, schema: {type: array, items: {type: integer}}}
                        - {name: tenant-id, in: header, required: true, schema: {type: string}}
                      responses:
                        '200': {description: ok, content: {'*/*': {schema: {$ref: '#/components/schemas/PageStock'}}}}
                  /erp/customer/create:
                    post:
                      tags: [ERP 客户]
                      summary: 创建客户
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/CustomerSave'}
                      responses: {'200': {description: ok}}
                  /erp/customer/{id}:
                    delete:
                      summary: 删除客户
                      security: []
                      parameters: [{name: id, in: path, required: true, schema: {type: integer}}]
                      responses: {'200': {description: ok}}
                  /erp/file/upload:
                    post:
                      requestBody: {content: {multipart/form-data: {schema: {type: object}}}}
                      responses: {'200': {description: ok}}
                  /erp/ping:
                    head: {responses: {'200': {description: ok}}}
                components:
                  securitySchemes:
                    bearer: {type: http, scheme: bearer}
                  schemas:
                    PageStock: {type: object, properties: {list: {type: array, items: {type: object}}}}
                    Base: {type: object, properties: {id: {type: integer, readOnly: true}, remark: {type: string, pattern: '^a'}}}
                    CustomerSave:
                      allOf:
                        - $ref: '#/components/schemas/Base'
                        - type: object
                          required: [name, id]
                          properties:
                            name: {type: string, description: 客户名称, example: 张三}
                            mobile: {type: string, format: phone}
                            level: {oneOf: [{type: string, enum: [A, B]}, {type: integer}]}
                            parent: {$ref: '#/components/schemas/CustomerSave'}
                """;
        var preview = service.parse(yaml, "/admin-api");
        assertEquals("OPENAPI3", preview.format());
        assertEquals("芋道 ERP", preview.title());
        assertEquals(1, preview.skipped());
        var candidates = byKey(preview);

        var page = candidates.get("GET /erp/stock/page").request();
        assertEquals("获得库存分页", page.summary());
        assertEquals(List.of("ERP 库存"), page.businessGroups());
        assertEquals("READ_ONLY", page.sideEffect());
        assertEquals("BEARER", page.authentication().mode());
        assertEquals("integer", page.parameters().get(0).type());
        assertTrue(page.parameters().get(0).required());
        assertEquals("string", page.parameters().get(1).type());
        assertTrue(candidates.get("GET /erp/stock/page").warnings().stream().anyMatch(w -> w.contains("tenant-id")));

        var create = candidates.get("POST /erp/customer/create").request();
        assertEquals("WRITE", create.sideEffect());
        var schema = create.requestBody().schema();
        assertNull(HttpApiJsonBinding.unsupportedReason(schema));
        assertFalse(schema.path("properties").has("id"), "readOnly fields are not request input");
        assertEquals("客户名称", schema.at("/properties/name/description").asText());
        assertFalse(schema.at("/properties/mobile").has("format"));
        assertFalse(schema.at("/properties/remark").has("pattern"));
        assertEquals("string", schema.at("/properties/level/type").asText());
        assertEquals("object", schema.at("/properties/parent/type").asText());
        assertEquals("[\"name\"]", schema.path("required").toString());

        var delete = candidates.get("DELETE /erp/customer/{id}").request();
        assertEquals("IRREVERSIBLE", delete.sideEffect());
        assertEquals("NONE", delete.authentication().mode());
        assertEquals("PATH", delete.parameters().get(0).location());

        assertEquals("文件上传接口暂不支持", candidates.get("POST /erp/file/upload").unsupported());
    }

    @Test
    void swagger2JsonIsConvertedWithBasePathBodyAndApiKey() throws Exception {
        String doc = json.writeValueAsString(Map.of(
                "swagger", "2.0", "info", Map.of("title", "旧系统", "version", "1"), "basePath", "/api",
                "securityDefinitions", Map.of("key", Map.of("type", "apiKey", "name", "X-Api-Key", "in", "header")),
                "security", List.of(Map.of("key", List.of())),
                "definitions", Map.of("Order", Map.of("type", "object", "properties", Map.of("no", Map.of("type", "string")))),
                "paths", Map.of(
                        "/orders/query", Map.of("post", Map.of("summary", "查询订单列表", "parameters", List.of(
                                Map.of("in", "body", "name", "body", "schema", Map.of("$ref", "#/definitions/Order"))),
                                "responses", Map.of("200", Map.of("description", "ok", "schema", Map.of("type", "array", "items", Map.of("$ref", "#/definitions/Order")))))),
                        "/orders/import", Map.of("post", Map.of("consumes", List.of("multipart/form-data"), "parameters", List.of(
                                Map.of("in", "formData", "name", "file", "type", "file")), "responses", Map.of())))));
        var candidates = byKey(service.parse(doc, ""));
        var query = candidates.get("POST /api/orders/query");
        assertNotNull(query, candidates.keySet().toString());
        assertEquals("READ_ONLY", query.request().sideEffect());
        assertEquals("array", query.request().responseType());
        assertEquals("API_KEY_HEADER", query.request().authentication().mode());
        assertEquals("X-Api-Key", query.request().authentication().headerName());
        assertEquals("string", query.request().requestBody().schema().at("/properties/no/type").asText());
        assertNotNull(candidates.get("POST /api/orders/import").unsupported());
    }

    @Test
    void postmanCollectionReadsFoldersVariablesBodiesAndAuth() throws Exception {
        String doc = """
                {"info":{"name":"CRM","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "auth":{"type":"apikey","apikey":[{"key":"key","value":"X-Token"},{"key":"in","value":"header"}]},
                 "variable":[{"key":"baseUrl","value":"https://crm.example.com/admin-api"}],
                 "item":[{"name":"客户","item":[
                   {"name":"客户详情","request":{"method":"GET","url":{"raw":"{{baseUrl}}/crm/customer/:id?withContacts=true",
                     "host":["{{baseUrl}}"],"path":["admin-api","crm","customer",":id"],
                     "query":[{"key":"withContacts","value":"true"},{"key":"access_token","value":"abc"}],
                     "variable":[{"key":"id","value":"12"}]}}},
                   {"name":"新建客户","request":{"method":"POST","auth":{"type":"bearer"},
                     "header":[{"key":"Content-Type","value":"application/json"}],
                     "body":{"mode":"raw","raw":"{\\"name\\":\\"张三\\",\\"age\\":30,\\"tags\\":[\\"vip\\"],\\"owner\\":{{ownerId}}}",
                       "options":{"raw":{"language":"json"}}},
                     "url":"{{baseUrl}}/crm/customer/create"}},
                   {"name":"导入","request":{"method":"POST","body":{"mode":"formdata","formdata":[{"key":"file","type":"file"}]},
                     "url":"{{baseUrl}}/crm/customer/import"}}
                 ]}]}
                """;
        var preview = service.parse(doc, "/admin-api");
        assertEquals("POSTMAN", preview.format());
        var candidates = byKey(preview);
        var detail = candidates.get("GET /crm/customer/{id}");
        assertNotNull(detail, candidates.keySet().toString());
        assertEquals("客户详情", detail.request().summary());
        assertEquals(List.of("客户"), detail.request().businessGroups());
        assertEquals("crm.example.com", detail.host());
        assertEquals("API_KEY_HEADER", detail.request().authentication().mode());
        assertEquals("X-Token", detail.request().authentication().headerName());
        var path = detail.request().parameters().stream().filter(p -> p.location().equals("PATH")).findFirst().orElseThrow();
        assertEquals("integer", path.type());
        assertTrue(detail.warnings().stream().anyMatch(w -> w.contains("access_token")));

        var create = candidates.get("POST /crm/customer/create").request();
        assertEquals("BEARER", create.authentication().mode());
        assertEquals("integer", create.requestBody().schema().at("/properties/age/type").asText());
        assertEquals("array", create.requestBody().schema().at("/properties/tags/type").asText());
        assertEquals("string", create.requestBody().schema().at("/properties/owner/type").asText());
        assertNotNull(candidates.get("POST /crm/customer/import").unsupported());
    }

    @Test
    void harKeepsApiCallsTemplatesIdsAndDeduplicates() throws Exception {
        String doc = """
                {"log":{"entries":[
                  {"_resourceType":"xhr","request":{"method":"GET","url":"https://erp.example.com/admin-api/erp/product/get/123?id=1",
                    "headers":[{"name":"Authorization","value":"Bearer x"}],"queryString":[{"name":"id","value":"1"}]},
                   "response":{"content":{"mimeType":"application/json","text":"{\\"data\\":1}"}}},
                  {"_resourceType":"xhr","request":{"method":"GET","url":"https://erp.example.com/admin-api/erp/product/get/456",
                    "headers":[],"queryString":[]},"response":{"content":{"mimeType":"application/json"}}},
                  {"_resourceType":"script","request":{"method":"GET","url":"https://erp.example.com/assets/app.js","headers":[],"queryString":[]},
                   "response":{"content":{"mimeType":"application/javascript"}}},
                  {"_resourceType":"fetch","request":{"method":"POST","url":"https://erp.example.com/admin-api/erp/sale-order/create",
                    "headers":[{"name":"Content-Type","value":"application/json"}],"queryString":[],
                    "postData":{"mimeType":"application/json","text":"{\\"customerId\\":4,\\"items\\":[{\\"productId\\":1,\\"count\\":2.5}]}"}},
                   "response":{"content":{"mimeType":"application/json"}}}
                ]}}
                """;
        var preview = service.parse(doc, "/admin-api");
        assertEquals("HAR", preview.format());
        var candidates = byKey(preview);
        assertEquals(2, candidates.size(), candidates.keySet().toString());
        var get = candidates.get("GET /erp/product/get/{id}").request();
        assertEquals("BEARER", get.authentication().mode());
        assertTrue(get.parameters().stream().anyMatch(p -> p.location().equals("QUERY") && p.name().equals("id")));
        var create = candidates.get("POST /erp/sale-order/create").request();
        assertEquals("number", create.requestBody().schema().at("/properties/items/items/properties/count/type").asText());
    }

    @Test
    void rejectsUnknownDocumentsAndHtml() {
        assertThrows(IllegalArgumentException.class, () -> service.parse("{\"hello\":1}", ""));
        assertThrows(IllegalArgumentException.class, () -> service.parse("<html></html>", ""));
        assertThrows(IllegalArgumentException.class, () -> service.parse("a: [", ""));
    }

    @Test
    void aHostOnTheIntranetAllowlistMayBeThisMachineAndNothingElseOpens() {
        // Port 1 refuses the connection: getting that far means the address check let it through.
        var allowed = new HttpApiDocumentFetcher(false, () -> List.of("127.0.0.1"));
        var passed = assertThrows(IllegalArgumentException.class, () -> allowed.fetch("http://127.0.0.1:1/v3/api-docs", null));
        assertTrue(passed.getMessage().startsWith("无法访问文档地址"), passed.getMessage());
        var blocked = assertThrows(IllegalArgumentException.class,
                () -> new HttpApiDocumentFetcher(false).fetch("http://127.0.0.1:1/v3/api-docs", null));
        assertTrue(blocked.getMessage().contains("内网地址白名单"), blocked.getMessage());
        var metadata = new HttpApiDocumentFetcher(false, () -> List.of("169.254.169.254"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> metadata.fetch("http://169.254.169.254/latest", null))
                .getMessage().contains("云平台内部地址"));
        var unavailable = new HttpApiDocumentFetcher(false, () -> { throw new IllegalStateException("runtime down"); });
        assertTrue(assertThrows(IllegalArgumentException.class, () -> unavailable.fetch("http://127.0.0.1:1/v3/api-docs", null))
                .getMessage().contains("内网地址白名单"));
    }

    @Test
    void fetcherBlocksLoopbackMetadataAndBadSchemes() throws Exception {
        var fetcher = new HttpApiDocumentFetcher(false);
        assertTrue(fetcher.blocked(InetAddress.getByName("127.0.0.1")));
        assertTrue(fetcher.blocked(InetAddress.getByName("169.254.169.254")));
        assertTrue(fetcher.blocked(InetAddress.getByName("::ffff:169.254.169.254")));
        assertTrue(fetcher.blocked(InetAddress.getByName("100.100.100.200")));
        assertTrue(fetcher.blocked(InetAddress.getByName("0.0.0.0")));
        assertFalse(fetcher.blocked(InetAddress.getByName("10.1.2.3")), "intranet business systems are allowed");
        assertFalse(new HttpApiDocumentFetcher(true).blocked(InetAddress.getByName("127.0.0.1")));
        assertThrows(IllegalArgumentException.class, () -> fetcher.fetch("file:///etc/passwd", null));
        assertThrows(IllegalArgumentException.class, () -> fetcher.fetch("http://user:pw@example.com/v3/api-docs", null));
        assertThrows(IllegalArgumentException.class, () -> fetcher.fetch("http://127.0.0.1:1/v3/api-docs", null));
        assertEquals(List.of("https://erp.example.com/admin-api/v3/api-docs", "https://erp.example.com/admin-api/v2/api-docs"),
                HttpApiDocumentFetcher.guesses(java.net.URI.create("https://erp.example.com/admin-api/swagger-ui/index.html"))
                        .stream().map(Object::toString).toList());
        assertEquals("/erp/a/{id}/b/{id2}", HttpApiDocumentImportService.templatePath("/erp/a/12/b/0f8fad5b-d9cb-469f-a165-70867728950e"));
    }
}
