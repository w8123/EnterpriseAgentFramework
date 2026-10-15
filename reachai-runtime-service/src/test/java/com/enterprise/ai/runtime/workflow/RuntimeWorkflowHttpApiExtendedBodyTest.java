package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.consolehttpapi.RuntimeHttpApiConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeWorkflowHttpApiExtendedBodyTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"WRITE", "IDEMPOTENT_WRITE", "IRREVERSIBLE"})
    void publishedWholeArrayBodyUsesStrictBindingAndEveryWriteFloorDisablesRetry(String effect) throws Exception {
        var fixture = fixture(effect, false);
        Object body = List.of(Map.of("nested", Map.of("code", "fixture")));
        var outcome = fixture.service.invoke(fixture.node, Map.of("body", body), fixture.identity);
        assertTrue(outcome.success(), outcome.code());
        assertEquals("WRITE", fixture.service.publishedRiskFloor(fixture.node));
        verify(fixture.http).execute(argThat(request -> !request.followRedirects()
                && "PUT".equals(request.method()) && "application/json".equals(request.headers().get("Content-Type"))
                && json.valueToTree(body).toString().equals(request.body())));
        clearInvocations(fixture.http);
        assertFalse(fixture.service.invoke(fixture.node, Map.of("body", body, "body.nested", Map.of()), fixture.identity).success());
        assertFalse(fixture.service.invoke(fixture.node, Map.of("body", List.of(Map.of("undeclared", true))), fixture.identity).success());
        assertFalse(fixture.service.invoke(fixture.node, Map.of(), fixture.identity).success());
        verifyNoInteractions(fixture.http);
    }

    @ParameterizedTest
    @ValueSource(strings = {"  ", "原文\"b\nc"})
    void textBodyRemainsExactLogicalString(String value) throws Exception {
        var fixture = fixture("WRITE", true);
        assertTrue(fixture.service.invoke(fixture.node, Map.of("body", value), fixture.identity).success());
        verify(fixture.http).execute(argThat(request -> value.equals(request.body())
                && "TEXT".equals(request.bodyType()) && "text/plain;charset=UTF-8".equals(request.headers().get("Content-Type"))));
    }

    private Fixture fixture(String effect, boolean text) throws Exception {
        String reference = "http-api:fixture", hash = "a".repeat(64), source = "b".repeat(64);
        var contract = json.readTree("""
                {"identity":{"method":"PUT","routeTemplate":"/fixture","mappingConditions":{}},
                 "parameters":[],"responses":[{"contentTypes":["application/json"],"schema":{"type":"object"}}],
                 "authentication":{"state":"UNKNOWN"}}
                """);
        ((com.fasterxml.jackson.databind.node.ObjectNode) contract).put("sideEffect", effect);
        ((com.fasterxml.jackson.databind.node.ObjectNode) contract).set("requestBody", json.readTree(text
                ? "{\"required\":true,\"contentTypes\":[\"text/plain\"],\"schema\":{\"type\":\"string\"}}"
                : """
                    {"required":true,"contentTypes":["application/json"],"schema":{"type":"array","items":{
                      "type":"object","properties":{"nested":{"type":"object","properties":{"code":{"type":"string"}},
                      "required":["code"]}},"required":["nested"]}}}
                    """));
        var owner = mock(RuntimeCapabilityCatalogClient.class);
        when(owner.getHttpApiExecutionContext(9L, "fixture")).thenReturn(new HttpApiConsoleContracts.ExecutionContext(
                1, 9L, reference, 7L, "fixture", "dev", "PUT", "/fixture", true, "ACCEPTED", null,
                hash, hash, source, contract, false, null, true, null));
        var connections = mock(RuntimeHttpApiConnectionService.class);
        when(connections.read(any())).thenReturn(new HttpApiConsoleContracts.ConnectionView(reference, 7L, "fixture", "dev",
                "https://fixture.test", "NONE", null, null, null, 1L, "CONFIGURED", null, "https://fixture.test/fixture"));
        when(connections.snapshot(reference)).thenReturn(new RuntimeHttpApiConnectionService.ConnectionSnapshot(
                7L, "fixture", "dev", "https://fixture.test", "NONE", null, 1L));
        var row = new RuntimeWorkflowHttpApiPinEntity();
        row.setId(1L); row.setWorkflowVersionId(1L); row.setNodeId("n1"); row.setApiId(9L); row.setQualifiedName(reference);
        row.setProjectId(7L); row.setProjectCode("fixture"); row.setEnvironment("dev");
        row.setAcceptedContractHash(hash); row.setSourceSetRevision(source); row.setConnectionRevision(1L);
        var pins = mock(RuntimeWorkflowHttpApiPinMapper.class); when(pins.selectById(1L)).thenReturn(row);
        var node = GraphSpec.Node.builder().id("n1").config(Map.of("httpApiAssetId", 9L))
                .ref(GraphSpec.CapabilityRef.builder().definitionId(1L).qualifiedName(reference).contractHash(hash).build()).build();
        var http = mock(WorkflowHttpClient.class);
        when(http.execute(any())).thenReturn(new WorkflowHttpClient.HttpExecutionResult(true, "OK", 200,
                Map.of(), "{\"ok\":true}", Map.of("ok", true), "application/json", 1L, 11, 0, "[url]", false));
        return new Fixture(new RuntimeWorkflowHttpApiService(owner, connections, pins, http, json), node, http,
                WorkflowExecutionIdentity.fromMcpRemoteClient("fixture", 7L, "fixture", "test-client"));
    }

    private record Fixture(RuntimeWorkflowHttpApiService service, GraphSpec.Node node, WorkflowHttpClient http,
                           WorkflowExecutionIdentity identity) { }
}
