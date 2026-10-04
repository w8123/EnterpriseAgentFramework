package com.enterprise.ai.capability.catalog.httpapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpApiContractCanonicalizerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpApiContractCanonicalizer canonicalizer = new HttpApiContractCanonicalizer(json);
    private final HttpApiServiceScope ordersProd = new HttpApiServiceScope(7L, "Orders", "Prod");

    @Test
    void marketNamespaceSeparatesSameOperationWithoutChangingLegacyInternalBytes() throws Exception {
        var operation = standardContract("GET", "/gateway", "/orders/{id}", orderedConditions(), schemaOne());
        var internal = canonicalizer.canonicalize(ordersProd, operation);
        var alpha = canonicalizer.canonicalize(new HttpApiServiceScope(7L, "Orders", "Prod", "api-market:alpha"), operation);
        var beta = canonicalizer.canonicalize(new HttpApiServiceScope(7L, "Orders", "Prod", "api-market:beta"), operation);
        assertNotEquals(internal.identityHash(), alpha.identityHash());
        assertNotEquals(alpha.identityHash(), beta.identityHash());
        assertFalse(internal.contractJson().contains("externalServiceKey"));
        assertEquals(internal.contractJson(), canonicalizer.canonicalize(new HttpApiServiceScope(7L, "Orders", "Prod", null), operation).contractJson());
        var legacyTree = json.readTree(internal.contractJson());
        assertEquals("{\"environment\":\"prod\",\"projectCode\":\"orders\"}", legacyTree.path("scope").toString());
        String legacyIdentity = "{\"identity\":" + legacyTree.path("identity") + ",\"scope\":{" +
                "\"environment\":\"prod\",\"projectCode\":\"orders\"}}";
        String legacyIdentityHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(legacyIdentity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(legacyIdentityHash, internal.identityHash());
        assertEquals("http-api:orders:prod:" + legacyIdentityHash, internal.qualifiedName());
        assertTrue(alpha.qualifiedName().startsWith("http-api:orders:prod:market:"));
        assertEquals(alpha.identityHash(), canonicalizer.canonicalize(new HttpApiServiceScope(999L, "orders", "prod", "api-market:alpha"), operation).identityHash());
    }

    @Test
    void stableIdentityUsesProjectCodeAndEnvironmentButNeverTheDatabaseSurrogateId() throws Exception {
        HttpApiContractCanonicalizer.CanonicalHttpApiContract first = canonicalizer.canonicalize(
                ordersProd, standardContract("get", "/gateway/", "/orders/{id}/", orderedConditions(), schemaOne()));
        HttpApiContractCanonicalizer.CanonicalHttpApiContract reRegisteredProject = canonicalizer.canonicalize(
                new HttpApiServiceScope(701L, " orders ", " PROD "),
                standardContract("GET", "gateway", "orders/{id}", reversedConditions(), schemaTwo()));

        assertEquals("GET", first.httpMethod());
        assertEquals("/gateway/orders/{id}", first.routeTemplate());
        assertEquals(first.identityHash(), reRegisteredProject.identityHash());
        assertEquals(first.contractHash(), reRegisteredProject.contractHash());
        assertEquals(first.contractJson(), reRegisteredProject.contractJson());
        assertTrue(first.identityHash().matches("[0-9a-f]{64}"));
        assertEquals("http-api:orders:prod:" + first.identityHash(), first.qualifiedName());
        assertEquals(first.qualifiedName(), reRegisteredProject.qualifiedName());

        assertNotEquals(first.identityHash(), canonicalizer.canonicalize(
                new HttpApiServiceScope(7L, "orders-other", "prod"),
                standardContract("GET", "/gateway", "/orders/{id}", orderedConditions(), schemaOne())).identityHash());
        assertNotEquals(first.identityHash(), canonicalizer.canonicalize(
                new HttpApiServiceScope(7L, "orders", "stage"),
                standardContract("GET", "/gateway", "/orders/{id}", orderedConditions(), schemaOne())).identityHash());
        assertNotEquals(first.identityHash(), canonicalizer.canonicalize(
                ordersProd, standardContract("POST", "/gateway", "/orders/{id}", orderedConditions(), schemaOne())).identityHash());
        assertNotEquals(first.identityHash(), canonicalizer.canonicalize(
                ordersProd, standardContract("GET", "/gateway", "/orders/{other}", orderedConditions(), schemaOne())).identityHash());
        assertNotEquals(first.identityHash(), canonicalizer.canonicalize(
                ordersProd, standardContract("GET", "/gateway", "/orders/{id}", changedConditions(), schemaOne())).identityHash());
    }

    @Test
    void preservesLosslessSpringMappingPredicatesAndCanonicalizesTheirSetSemantics() throws Exception {
        HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical = canonicalizer.canonicalize(ordersProd,
                standardContract("GET", "/gateway", "/orders/{id}", orderedConditions(), schemaOne()));
        HttpApiContractCanonicalizer.CanonicalHttpApiContract reordered = canonicalizer.canonicalize(ordersProd,
                standardContract("GET", "/gateway", "/orders/{id}", reversedConditions(), schemaTwo()));

        assertEquals(canonical.identityHash(), reordered.identityHash());
        JsonNode conditions = json.readTree(canonical.mappingConditionsJson()).path("conditions");
        assertEquals(4, conditions.size(), "duplicate predicate is removed but same-name predicates coexist");
        assertTrue(conditions.toString().contains("NOT_EQUALS"));
        assertTrue(conditions.toString().contains("ABSENT"));

        HttpApiOperationContract.MappingConditions changedValue = mapping(List.of(
                condition(HttpApiMappingConditionKind.HEADER, "X-Mode", HttpApiMappingConditionOperator.EQUALS, "external"),
                condition(HttpApiMappingConditionKind.HEADER, "X-Mode", HttpApiMappingConditionOperator.NOT_EQUALS, "legacy"),
                condition(HttpApiMappingConditionKind.PARAM, "debug", HttpApiMappingConditionOperator.ABSENT, null),
                condition(HttpApiMappingConditionKind.PARAM, "v", HttpApiMappingConditionOperator.PRESENT, null)));
        HttpApiOperationContract.MappingConditions changedOperator = mapping(List.of(
                condition(HttpApiMappingConditionKind.HEADER, "X-Mode", HttpApiMappingConditionOperator.PRESENT, null),
                condition(HttpApiMappingConditionKind.HEADER, "X-Mode", HttpApiMappingConditionOperator.NOT_EQUALS, "legacy"),
                condition(HttpApiMappingConditionKind.PARAM, "debug", HttpApiMappingConditionOperator.ABSENT, null),
                condition(HttpApiMappingConditionKind.PARAM, "v", HttpApiMappingConditionOperator.PRESENT, null)));
        HttpApiOperationContract.MappingConditions changedValueCase = mapping(List.of(
                condition(HttpApiMappingConditionKind.HEADER, "X-Mode", HttpApiMappingConditionOperator.EQUALS, "Internal"),
                condition(HttpApiMappingConditionKind.HEADER, "X-Mode", HttpApiMappingConditionOperator.NOT_EQUALS, "legacy"),
                condition(HttpApiMappingConditionKind.PARAM, "debug", HttpApiMappingConditionOperator.ABSENT, null),
                condition(HttpApiMappingConditionKind.PARAM, "v", HttpApiMappingConditionOperator.PRESENT, null)));
        assertNotEquals(canonical.identityHash(), canonicalizer.canonicalize(ordersProd,
                standardContract("GET", "/gateway", "/orders/{id}", changedValue, schemaOne())).identityHash());
        assertNotEquals(canonical.identityHash(), canonicalizer.canonicalize(ordersProd,
                standardContract("GET", "/gateway", "/orders/{id}", changedOperator, schemaOne())).identityHash());
        assertNotEquals(canonical.identityHash(), canonicalizer.canonicalize(ordersProd,
                standardContract("GET", "/gateway", "/orders/{id}", changedValueCase, schemaOne())).identityHash(),
                "normal mapping values retain case-sensitive Spring semantics");

        HttpApiOperationContract.MappingConditions safeSensitivePresence = mapping(List.of(
                condition(HttpApiMappingConditionKind.HEADER, "Authorization", HttpApiMappingConditionOperator.PRESENT, null),
                condition(HttpApiMappingConditionKind.HEADER, "Cookie", HttpApiMappingConditionOperator.ABSENT, null)));
        String safeSensitiveJson = canonicalizer.canonicalize(ordersProd,
                standardContract("GET", "/gateway", "/orders/{id}", safeSensitivePresence, schemaOne()))
                .mappingConditionsJson().toLowerCase();
        assertTrue(safeSensitiveJson.contains("authorization"));
        assertTrue(safeSensitiveJson.contains("cookie"));
        assertFalse(safeSensitiveJson.contains("actual-header"));

        HttpApiOperationContract.MappingConditions unsafeSensitiveValue = mapping(List.of(
                condition(HttpApiMappingConditionKind.HEADER, "Authorization",
                        HttpApiMappingConditionOperator.EQUALS, "Bearer actual-header")));
        assertThrows(IllegalArgumentException.class, () -> canonicalizer.canonicalize(ordersProd,
                standardContract("GET", "/gateway", "/orders/{id}", unsafeSensitiveValue, schemaOne())));
    }

    @Test
    void hashesStructuredContractSemanticsButNotJsonFieldOrderOrSourceFacts() throws Exception {
        HttpApiOperationContract baseline = standardContract("GET", "/gateway", "/orders/{id}",
                orderedConditions(), schemaOne());
        HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical = canonicalizer.canonicalize(ordersProd, baseline);

        HttpApiOperationContract changedParameterLocation = operation(baseline.httpMethod(), baseline.contextPath(),
                baseline.endpointPath(), baseline.mappingConditions(), List.of(
                        parameter("id", HttpApiParameterLocation.QUERY, true, schemaOne(), List.of("application/json")),
                        parameter("include", HttpApiParameterLocation.QUERY, false, simpleSchema("string"), List.of())),
                baseline.requestBody(), baseline.responses(), baseline.authentication(), baseline.sideEffect());
        HttpApiOperationContract changedSchema = operation(baseline.httpMethod(), baseline.contextPath(),
                baseline.endpointPath(), baseline.mappingConditions(), List.of(
                        parameter("id", HttpApiParameterLocation.PATH, true, simpleSchema("integer"), List.of("application/json")),
                        parameter("include", HttpApiParameterLocation.QUERY, false, simpleSchema("string"), List.of())),
                baseline.requestBody(), baseline.responses(), baseline.authentication(), baseline.sideEffect());
        HttpApiOperationContract changedContent = operation(baseline.httpMethod(), baseline.contextPath(),
                baseline.endpointPath(), baseline.mappingConditions(), baseline.parameters(),
                new HttpApiOperationContract.RequestBody(true, schemaOne(), List.of("application/xml")),
                baseline.responses(), baseline.authentication(), baseline.sideEffect());
        HttpApiOperationContract changedAuthentication = operation(baseline.httpMethod(), baseline.contextPath(),
                baseline.endpointPath(), baseline.mappingConditions(), baseline.parameters(), baseline.requestBody(),
                baseline.responses(), new HttpApiOperationContract.AuthenticationRequirement(true,
                        List.of("mtls"), List.of("X-Client-Cert")), baseline.sideEffect());
        HttpApiOperationContract changedSideEffect = operation(baseline.httpMethod(), baseline.contextPath(),
                baseline.endpointPath(), baseline.mappingConditions(), baseline.parameters(), baseline.requestBody(),
                baseline.responses(), baseline.authentication(), "WRITE");
        HttpApiOperationContract changedEnumSchema = operation(baseline.httpMethod(), baseline.contextPath(),
                baseline.endpointPath(), baseline.mappingConditions(), List.of(
                        parameter("id", HttpApiParameterLocation.PATH, true,
                                json.readTree("{\"type\":\"string\",\"enum\":[\"active\",\"archived\"]}"),
                                List.of("application/json")),
                        parameter("include", HttpApiParameterLocation.QUERY, false, simpleSchema("string"), List.of())),
                baseline.requestBody(), baseline.responses(), baseline.authentication(), baseline.sideEffect());

        assertNotEquals(canonical.contractHash(), canonicalizer.canonicalize(ordersProd, changedParameterLocation).contractHash());
        assertNotEquals(canonical.contractHash(), canonicalizer.canonicalize(ordersProd, changedSchema).contractHash());
        assertNotEquals(canonical.contractHash(), canonicalizer.canonicalize(ordersProd, changedContent).contractHash());
        assertNotEquals(canonical.contractHash(), canonicalizer.canonicalize(ordersProd, changedAuthentication).contractHash());
        assertNotEquals(canonical.contractHash(), canonicalizer.canonicalize(ordersProd, changedSideEffect).contractHash());
        assertNotEquals(canonical.contractHash(), canonicalizer.canonicalize(ordersProd, changedEnumSchema).contractHash());
        assertEquals(canonical.identityHash(), canonicalizer.canonicalize(ordersProd, changedSideEffect).identityHash());
    }

    @Test
    void stripsSampleAnnotationsWithoutMistakingBusinessPropertyNamesForSchemaKeywords() throws Exception {
        JsonNode schema = json.readTree("""
                {
                  "type":"object",
                  "example":"root-example",
                  "default":"root-default",
                  "properties":{
                    "example":{"type":"string","example":"property-example"},
                    "default":{"type":"string","default":"property-default"},
                    "mode":{"type":"string","enum":["open","closed"]},
                    "fixed":{"type":"string","const":"fixed-value"}
                  },
                  "$defs":{
                    "TokenResponse":{"type":"string","enum":["issued","expired"]},
                    "default":{"type":"string","examples":["definition-example"]}
                  }
                }
                """);
        HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical = canonicalizer.canonicalize(ordersProd,
                contractWithSchema(schema));
        JsonNode persisted = json.readTree(canonical.contractJson()).path("parameters").get(0).path("schema");

        assertFalse(persisted.has("example"));
        assertFalse(persisted.has("default"));
        assertTrue(persisted.path("properties").has("example"));
        assertTrue(persisted.path("properties").has("default"));
        assertFalse(persisted.path("properties").path("example").has("example"));
        assertFalse(persisted.path("properties").path("default").has("default"));
        assertEquals(List.of("open", "closed"), json.convertValue(
                persisted.path("properties").path("mode").path("enum"), List.class));
        assertEquals("fixed-value", persisted.path("properties").path("fixed").path("const").asText());
        assertEquals(List.of("issued", "expired"), json.convertValue(
                persisted.path("$defs").path("TokenResponse").path("enum"), List.class));
        assertTrue(persisted.path("$defs").has("default"));
        assertFalse(persisted.path("$defs").path("default").has("examples"));
        assertFalse(canonical.contractJson().contains("root-example"));
        assertFalse(canonical.contractJson().contains("property-example"));
        assertFalse(canonical.contractJson().contains("definition-example"));

        JsonNode sensitiveNestedLiteral = json.readTree("""
                {"type":"object","properties":{"credentials":{"type":"object","properties":{
                  "token":{"type":"string","enum":["actual-token"]}}}}}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> canonicalizer.canonicalize(ordersProd, contractWithSchema(sensitiveNestedLiteral)));

        JsonNode sensitiveNestedConst = json.readTree("""
                {"type":"object","properties":{"credentials":{"type":"object","properties":{
                  "password":{"type":"string","const":"actual-password"}}}}}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> canonicalizer.canonicalize(ordersProd, contractWithSchema(sensitiveNestedConst)));
    }

    @Test
    void retainsUnknownAuthenticationAsDistinctFromNoAuthenticationAndAcceptsTrace() throws Exception {
        HttpApiOperationContract unknown = operation("TRACE", "/gateway", "/diagnostics", mapping(List.of()),
                List.of(), null, List.of(new HttpApiOperationContract.Response("DEFAULT", simpleSchema("string"), List.of())),
                new HttpApiOperationContract.AuthenticationRequirement(false, "UNKNOWN", List.of(), List.of()), "READ_ONLY");
        HttpApiOperationContract none = operation("TRACE", "/gateway", "/diagnostics", mapping(List.of()),
                List.of(), null, List.of(new HttpApiOperationContract.Response("DEFAULT", simpleSchema("string"), List.of())),
                new HttpApiOperationContract.AuthenticationRequirement(false, "NONE", List.of(), List.of()), "READ_ONLY");

        HttpApiContractCanonicalizer.CanonicalHttpApiContract unknownCanonical = canonicalizer.canonicalize(ordersProd, unknown);
        assertEquals("TRACE", unknownCanonical.httpMethod());
        assertEquals("UNKNOWN", json.readTree(unknownCanonical.contractJson()).path("authentication").path("state").asText());
        assertNotEquals(unknownCanonical.contractHash(), canonicalizer.canonicalize(ordersProd, none).contractHash());
    }

    private HttpApiOperationContract standardContract(String method, String contextPath, String endpointPath,
                                                       HttpApiOperationContract.MappingConditions conditions,
                                                       JsonNode parameterSchema) throws Exception {
        return operation(method, contextPath, endpointPath, conditions, List.of(
                        parameter("include", HttpApiParameterLocation.QUERY, false, simpleSchema("string"), List.of()),
                        parameter("id", HttpApiParameterLocation.PATH, true, parameterSchema,
                                List.of("APPLICATION/JSON", "application/json"))),
                new HttpApiOperationContract.RequestBody(true, schemaOne(),
                        List.of("Application/Json", "application/json")),
                List.of(
                        new HttpApiOperationContract.Response("404", simpleSchema("string"), List.of("application/json")),
                        new HttpApiOperationContract.Response("200", schemaTwo(), List.of("APPLICATION/JSON"))),
                new HttpApiOperationContract.AuthenticationRequirement(true,
                        List.of("OAuth2", "oauth2"), List.of("X-Request-Id")), "READ_ONLY");
    }

    private HttpApiOperationContract contractWithSchema(JsonNode schema) throws Exception {
        return operation("GET", "/gateway", "/schemas", mapping(List.of()), List.of(
                        parameter("payload", HttpApiParameterLocation.BODY, true, schema, List.of("application/json"))),
                null, List.of(new HttpApiOperationContract.Response("200", simpleSchema("string"), List.of())),
                new HttpApiOperationContract.AuthenticationRequirement(false, List.of(), List.of()), "READ_ONLY");
    }

    private HttpApiOperationContract operation(String method, String contextPath, String endpointPath,
                                                HttpApiOperationContract.MappingConditions conditions,
                                                List<HttpApiOperationContract.Parameter> parameters,
                                                HttpApiOperationContract.RequestBody requestBody,
                                                List<HttpApiOperationContract.Response> responses,
                                                HttpApiOperationContract.AuthenticationRequirement authentication,
                                                String sideEffect) {
        return new HttpApiOperationContract(method, contextPath, endpointPath, conditions, parameters,
                requestBody, responses, authentication, sideEffect);
    }

    private HttpApiOperationContract.Parameter parameter(String name, HttpApiParameterLocation location,
                                                          boolean required, JsonNode schema, List<String> contentTypes) {
        return new HttpApiOperationContract.Parameter(name, location, required, schema, contentTypes);
    }

    private HttpApiOperationContract.MappingConditions orderedConditions() {
        return mapping(List.of(
                condition(HttpApiMappingConditionKind.HEADER, "X-Mode", HttpApiMappingConditionOperator.EQUALS, "internal"),
                condition(HttpApiMappingConditionKind.HEADER, "X-Mode", HttpApiMappingConditionOperator.NOT_EQUALS, "legacy"),
                condition(HttpApiMappingConditionKind.PARAM, "debug", HttpApiMappingConditionOperator.ABSENT, null),
                condition(HttpApiMappingConditionKind.PARAM, "v", HttpApiMappingConditionOperator.PRESENT, null),
                condition(HttpApiMappingConditionKind.HEADER, "X-Mode", HttpApiMappingConditionOperator.EQUALS, "internal")));
    }

    private HttpApiOperationContract.MappingConditions reversedConditions() {
        return mapping(List.of(
                condition(HttpApiMappingConditionKind.PARAM, "v", HttpApiMappingConditionOperator.PRESENT, null),
                condition(HttpApiMappingConditionKind.HEADER, "x-mode", HttpApiMappingConditionOperator.NOT_EQUALS, "legacy"),
                condition(HttpApiMappingConditionKind.PARAM, "debug", HttpApiMappingConditionOperator.ABSENT, null),
                condition(HttpApiMappingConditionKind.HEADER, "x-mode", HttpApiMappingConditionOperator.EQUALS, "internal")));
    }

    private HttpApiOperationContract.MappingConditions changedConditions() {
        return mapping(List.of(
                condition(HttpApiMappingConditionKind.HEADER, "X-Mode", HttpApiMappingConditionOperator.EQUALS, "external"),
                condition(HttpApiMappingConditionKind.PARAM, "v", HttpApiMappingConditionOperator.PRESENT, null)));
    }

    private HttpApiOperationContract.MappingConditions mapping(
            List<HttpApiOperationContract.MappingCondition> conditions) {
        return new HttpApiOperationContract.MappingConditions(
                List.of("APPLICATION/JSON", "application/json"), List.of("application/json"), conditions);
    }

    private HttpApiOperationContract.MappingCondition condition(HttpApiMappingConditionKind kind, String name,
                                                                 HttpApiMappingConditionOperator operator, String value) {
        return new HttpApiOperationContract.MappingCondition(kind, name, operator, value);
    }

    private JsonNode schemaOne() throws Exception {
        return json.readTree("{\"properties\":{\"code\":{\"type\":\"string\"},\"id\":{\"type\":\"integer\"}},\"type\":\"object\"}");
    }

    private JsonNode schemaTwo() throws Exception {
        return json.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},\"code\":{\"type\":\"string\"}}}");
    }

    private JsonNode simpleSchema(String type) throws Exception {
        return json.readTree("{\"type\":\"" + type + "\"}");
    }
}
