package com.enterprise.ai.capability.catalog.enrichment;

import com.enterprise.ai.agent.capability.catalog.scan.*;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.capability.catalog.businessmethod.*;
import com.enterprise.ai.capability.catalog.httpapi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogSubjectResolverTest {
    static {
        var metadata = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "resolver-tests");
        for (Class<?> type : List.of(ScanProjectToolEntity.class, BusinessMethodAssetEntity.class, HttpApiAssetEntity.class, HttpApiSourceBindingEntity.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(metadata, type);
        }
    }
    final ScanProjectMapper projects = mock(ScanProjectMapper.class);
    final ScanProjectToolMapper observations = mock(ScanProjectToolMapper.class);
    final BusinessMethodAssetMapper methods = mock(BusinessMethodAssetMapper.class);
    final BusinessMethodAssetStore methodStore = mock(BusinessMethodAssetStore.class);
    final HttpApiAssetMapper apis = mock(HttpApiAssetMapper.class);
    final HttpApiSourceBindingMapper bindings = mock(HttpApiSourceBindingMapper.class);
    final HttpApiCatalogService apiCatalog = mock(HttpApiCatalogService.class);
    final CapabilityCatalogGroupingService grouping = mock(CapabilityCatalogGroupingService.class);
    final ObjectMapper json = new ObjectMapper();
    final CatalogSubjectResolver resolver = new CatalogSubjectResolver(projects, observations, methods, methodStore, apis, bindings, apiCatalog, grouping, json);
    CatalogSubjectResolverTest() { var project = new ScanProjectEntity(); project.setId(7L); project.setProjectCode("orders"); project.setName("Orders"); when(projects.selectById(7L)).thenReturn(project); }

    @Test void aggregateOnlyDocumentIndexDoesNotReadAnyOperationOrSourceCatalog() {
        var key = new CatalogSubjectResolver.DocumentReference("PROJECT", 7L);
        assertEquals("Orders", resolver.documentReferences(7L, List.of(key)).get(key).title());
        assertEquals(Map.of(), resolver.documentReferences(7L, List.of()));
        verifyNoInteractions(methods, methodStore, apis, bindings, observations, apiCatalog, grouping);
    }

    @Test void singleMethodDocumentLoadsOnlyExactOwnedSourcesAndKeepsReferenceQueriesLight() {
        var method = new BusinessMethodAssetEntity(); method.setId(11L); method.setProjectId(7L); method.setQualifiedName("orders:find"); method.setAcceptedRevisionId(12L);
        when(methods.selectOne(any())).thenReturn(method);
        var exact = source(41L, "sdk:orders:find"); exact.setAssetType("BUSINESS_METHOD"); exact.setSourceQualifiedName("orders:find");
        var wrongCase = source(42L, "sdk:orders:Find"); wrongCase.setAssetType("BUSINESS_METHOD"); wrongCase.setSourceQualifiedName("orders:Find");
        var foreign = source(43L, "sdk:orders:find"); foreign.setAssetType("BUSINESS_METHOD"); foreign.setSourceQualifiedName("orders:find"); foreign.setProjectId(8L);
        when(observations.selectList(any())).thenReturn(List.of(exact, wrongCase, foreign));
        assertEquals(List.of(41L), resolver.resolveDocumentReference(7L, "BUSINESS_METHOD", 11L).observations().stream().map(ScanProjectToolEntity::getId).toList());
        verify(observations).selectList(argThat(q -> q.getSqlSegment().contains("source_qualified_name IN")
                && q.getSqlSegment().contains("asset_type") && !q.getSqlSelect().contains("parameters_json")));
        verify(methods).selectOne(argThat(q -> q.getSqlSelect().contains("accepted_revision_id")
                && q.getSqlSegment().contains("project_id") && q.getSqlSegment().contains("id =")));
        verifyNoInteractions(methodStore, apis, bindings, apiCatalog);
    }

    @Test void documentReferencesReadTypedTitlesAndSourceIdsWithoutHydratingAnyContracts() {
        var method = new BusinessMethodAssetEntity(); method.setId(21L); method.setProjectId(7L); method.setQualifiedName("orders:find");
        method.setTitle("查询订单"); method.setAcceptedRevisionId(12L);
        var api = api(21L, "orders:http:21");
        var source = source(41L, "sdk:orders:find"); source.setAssetType("BUSINESS_METHOD"); source.setSourceQualifiedName("orders:find");
        var unowned = source(42L, "OrderController.java#OrderController#find"); unowned.setTitle("候选接口"); unowned.setParametersJson("malformed JSON");
        var group = new ScanModuleEntity(); group.setId(4L); group.setProjectId(7L); group.setDisplayName("订单查询");
        when(methods.selectList(any())).thenReturn(List.of(method)); when(apis.selectList(any())).thenReturn(List.of(api));
        when(observations.selectList(any())).thenReturn(List.of(source, unowned)); when(grouping.list(7L)).thenReturn(List.of(group));
        var keys = List.of(new CatalogSubjectResolver.DocumentReference("PROJECT", 7L), new CatalogSubjectResolver.DocumentReference("GROUP", 4L),
                new CatalogSubjectResolver.DocumentReference("BUSINESS_METHOD", 21L), new CatalogSubjectResolver.DocumentReference("HTTP_API", 21L),
                new CatalogSubjectResolver.DocumentReference("SOURCE_OBSERVATION", 42L));
        var references = resolver.documentReferences(7L, keys);
        assertEquals(5, references.size());
        assertEquals("Orders", references.get(keys.get(0)).title()); assertEquals("订单查询", references.get(keys.get(1)).title());
        assertEquals("查询订单", references.get(keys.get(2)).title()); assertEquals(21L, references.get(keys.get(3)).subjectId());
        assertEquals(List.of(41L), references.get(keys.get(2)).observations().stream().map(ScanProjectToolEntity::getId).toList());
        assertEquals(List.of(42L), references.get(keys.get(4)).observations().stream().map(ScanProjectToolEntity::getId).toList());
        verify(observations, times(2)).selectList(any()); verify(methods).selectList(any()); verify(apis, times(2)).selectList(any());
        verifyNoInteractions(methodStore, apiCatalog);
    }

    @Test void typedFormalOwnerInventorySkipsMalformedUnownedSourceContracts() {
        var source = source(41L, "OrderController.java#OrderController#find"); source.setParametersJson("not valid JSON");
        when(observations.selectList(any())).thenReturn(List.of(source));
        assertEquals(List.of(), resolver.operations(7L, false, "BUSINESS_METHOD"));
        assertEquals(List.of(), resolver.operations(7L, false, "HTTP_API"));
        assertThrows(IllegalArgumentException.class, () -> resolver.operations(7L));
        verify(methodStore).acceptedSources(List.of());
        verify(apiCatalog).details(List.of());
    }

    @Test void typedFormalInventoryDoesNotReadOrHydrateTheOtherOwnerCatalog() {
        when(methods.selectList(any())).thenThrow(new IllegalStateException("unrelated method catalog is broken"));
        assertEquals(List.of(), resolver.operations(7L, false, "HTTP_API"));
        verify(methods, never()).selectList(any());
        reset(methods); reset(apis); reset(bindings);
        when(apis.selectList(any())).thenThrow(new IllegalStateException("unrelated API catalog is broken"));
        assertEquals(List.of(), resolver.operations(7L, false, "BUSINESS_METHOD"));
        verify(apis, never()).selectList(any()); verify(bindings, never()).selectList(any());
        assertThrows(IllegalArgumentException.class, () -> resolver.operations(7L, false, "SOURCE_OBSERVATION"));
    }

    @Test void typedClassificationReadsOnlyOwnerIdentitiesWithoutSourcesBindingsOrContracts() {
        var method = new BusinessMethodAssetEntity(); method.setId(11L); method.setProjectId(7L); method.setQualifiedName("orders:find"); method.setAcceptedRevisionId(12L);
        when(methods.selectList(any())).thenReturn(List.of(method));
        when(methodStore.acceptedSources(anyList())).thenThrow(new IllegalStateException("broken execution contract must not affect classification"));
        var methodSource = source(41L, "sdk:orders:find"); methodSource.setAssetType("BUSINESS_METHOD"); methodSource.setSourceQualifiedName("orders:find");
        when(observations.selectList(any())).thenReturn(List.of(methodSource));
        var methodIndex = resolver.operations(7L, false, "BUSINESS_METHOD");
        assertEquals(1, methodIndex.size()); assertEquals(11L, methodIndex.get(0).subjectId()); assertEquals(List.of(), methodIndex.get(0).observations());
        var api = api(21L, "orders:http:21"); when(apis.selectList(any())).thenReturn(List.of(api));
        when(apiCatalog.details(anyList())).thenThrow(new IllegalStateException("contract hydration can invalidate stale market integrations"));
        var apiIndex = resolver.operations(7L, false, "HTTP_API");
        assertEquals(1, apiIndex.size()); assertEquals(21L, apiIndex.get(0).subjectId());
        verifyNoInteractions(methodStore, apiCatalog, observations, bindings);
    }

    @Test void classificationGroupDescriptionIgnoresUnownedSourcesWhileProjectProseKeepsItsExistingScope() {
        var source = source(41L, "OrderController.java#OrderController#find"); source.setParametersJson("not valid JSON");
        when(observations.selectList(any())).thenReturn(List.of(source));
        var group = new ScanModuleEntity(); group.setId(4L); group.setProjectId(7L); group.setDisplayName("Orders");
        when(grouping.requireGroup(7L, 4L)).thenReturn(group);
        when(grouping.formalMemberIds(7L, 4L)).thenReturn(new CapabilityCatalogGroupingService.OwnerMemberIds(List.of(), List.of()));
        var aggregate = resolver.resolve(7L, "GROUP", 4L);
        assertEquals("GROUP", aggregate.subjectType()); assertEquals("Orders", aggregate.title()); assertEquals("", aggregate.description());
        verifyNoInteractions(observations, methods, methodStore, apis, bindings, apiCatalog);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(7L, "PROJECT", 7L));
    }

    @Test void groupAggregateLoadsOnlyPersistedFormalMembersAndKeepsDescriptionAndFingerprint() throws Exception {
        var group = new ScanModuleEntity(); group.setId(4L); group.setProjectId(7L); group.setDisplayName("Orders");
        when(grouping.requireGroup(7L, 4L)).thenReturn(group);
        when(grouping.formalMemberIds(7L, 4L)).thenReturn(new CapabilityCatalogGroupingService.OwnerMemberIds(List.of(11L), List.of(21L)));
        var method = new BusinessMethodAssetEntity(); method.setId(11L); method.setProjectId(7L); method.setQualifiedName("orders:find");
        method.setTitle("查询订单"); method.setAcceptedRevisionId(12L); method.setStatus("ACCEPTED");
        when(methods.selectList(any())).thenReturn(List.of(method));
        var revision = new BusinessMethodRevisionEntity(); revision.setId(12L); revision.setContractHash("method-hash");
        var declaration = json.readValue("{\"name\":\"find\",\"parameters\":[]}", CapabilityRegistration.class);
        when(methodStore.acceptedSources(List.of(method))).thenReturn(Map.of(11L,
                new BusinessMethodAssetStore.AcceptedSource(revision, declaration)));
        var api = api(21L, "orders:http:21"); when(apis.selectList(any())).thenReturn(List.of(api));
        when(apiCatalog.details(List.of(api))).thenReturn(Map.of(21L, detail(api, "candidate-hash", null)));

        var aggregate = resolver.resolve(7L, "GROUP", 4L);
        assertEquals("\n查询订单 [BUSINESS_METHOD]\nGET /orders [HTTP_API]", aggregate.description());
        assertEquals(CatalogSubjectResolver.digest("\nBUSINESS_METHOD:orders:find:method-hash:true"
                + "\nHTTP_API:orders:http:21:candidate-hash:false"), aggregate.contractHash());
        assertFalse(aggregate.accepted()); assertTrue(aggregate.observations().isEmpty());
        when(grouping.formalMemberIds(7L, 4L)).thenReturn(new CapabilityCatalogGroupingService.OwnerMemberIds(List.of(11L), List.of()));
        assertNotEquals(aggregate.contractHash(), resolver.resolve(7L, "GROUP", 4L).contractHash(),
                "removing an owner must make existing classification prose stale");
        verify(methodStore, times(2)).acceptedSources(List.of(method)); verify(apiCatalog).details(List.of(api));
        verifyNoInteractions(observations, bindings);
        verify(grouping, never()).groups(any());
        when(grouping.requireGroup(7L, 99L)).thenThrow(new IllegalArgumentException("foreign group"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(7L, "GROUP", 99L));
    }

    @Test void readsMethodAcceptedFactsAndApiOwnerDetailsOncePerInventoryAndKeepsDeclaredReturns() throws Exception {
        var method = new BusinessMethodAssetEntity(); method.setId(11L); method.setProjectId(7L); method.setQualifiedName("orders:find"); method.setAcceptedRevisionId(12L);
        when(methods.selectList(any())).thenReturn(List.of(method));
        var revision = new BusinessMethodRevisionEntity(); revision.setId(12L); revision.setContractHash("method-hash");
        var declaration = json.readValue("{\"name\":\"find\",\"parameters\":[],\"responseType\":\"com.example.OrderDto\",\"httpMethod\":\"POST\",\"endpointPath\":\"/sdk/find\"}", CapabilityRegistration.class);
        when(methodStore.acceptedSources(List.of(method))).thenReturn(Map.of(11L, new BusinessMethodAssetStore.AcceptedSource(revision, declaration)));
        var api = api(21L, "orders:http:21"); when(apis.selectList(any())).thenReturn(List.of(api));
        var detail = detail(api, "accepted", json.readTree("{\"parameters\":[],\"responses\":[{\"status\":\"200\",\"schema\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}}}}]}"));
        when(apiCatalog.details(List.of(api))).thenReturn(Map.of(21L, detail));
        List<CatalogSubject> result = resolver.operations(7L);
        assertEquals(2, result.size()); assertTrue(result.get(0).parameters().stream().anyMatch(p -> "RESPONSE".equals(p.location()) && "com.example.OrderDto".equals(p.type())));
        assertEquals("response200", result.get(1).parameters().get(0).name());
        verify(methodStore).acceptedSources(List.of(method)); verify(methodStore, never()).acceptedDeclaration(any()); verify(methodStore, never()).acceptedRevision(any());
        verify(bindings).selectList(any()); verify(apiCatalog).details(List.of(api));
    }

    @Test void ambiguousMappingConditionsAndDifferentSdkSourceIdentityStayUnresolved() {
        var a = api(21L, "orders:http:21"); var b = api(22L, "orders:http:22");
        when(apis.selectById(21L)).thenReturn(a);
        when(apis.selectList(any())).thenReturn(List.of(a, b)); when(apiCatalog.details(anyList())).thenReturn(Map.of(21L, detail(a, "", null), 22L, detail(b, "", null)));
        var source = source(41L, "OrderController.java#OrderController#find"); when(observations.selectById(41L)).thenReturn(source); when(observations.selectList(any())).thenReturn(List.of(source));
        String location = "src/main/java/com/example/OrderController.java#com.example.OrderController#find()";
        when(bindings.selectList(any())).thenReturn(List.of(binding(a, location), binding(b, location)));
        assertEquals("SOURCE_OBSERVATION", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        when(apis.selectList(any())).thenReturn(List.of(a)); when(apiCatalog.details(anyList())).thenReturn(Map.of(21L, detail(a, "", null)));
        when(bindings.selectList(any())).thenReturn(List.of(binding(a, location)));
        assertEquals("HTTP_API", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        source.setSource("sdk"); source.setSourceLocation("sdk:orders:find");
        assertEquals("SOURCE_OBSERVATION", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
    }

    @Test void scannerSourceFormatsResolveOnlyOneBindingIncludingSameAssetDirectoryAndOverloadAmbiguity() {
        var api = api(21L, "orders:http:21"); when(apis.selectList(any())).thenReturn(List.of(api)); when(apiCatalog.details(anyList())).thenReturn(Map.of(21L, detail(api, "", null)));
        when(apis.selectById(21L)).thenReturn(api);
        var source = source(41L, "OrderController.java#OrderController#find"); when(observations.selectById(41L)).thenReturn(source); when(observations.selectList(any())).thenReturn(List.of(source));
        var plain = binding(api, "src/main/java/com/example/OrderController.java#com.example.OrderController#find()");
        when(bindings.selectList(any())).thenReturn(List.of(plain)); assertEquals("HTTP_API", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        var overloaded = binding(api, "src/main/java/com/example/OrderController.java#com.example.OrderController#find(java.lang.String)"); overloaded.setId(22L);
        when(bindings.selectList(any())).thenReturn(List.of(plain, overloaded)); assertEquals("SOURCE_OBSERVATION", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        var differentDirectory = binding(api, "src/main/java/org/other/OrderController.java#org.other.OrderController#find()"); differentDirectory.setId(23L);
        when(bindings.selectList(any())).thenReturn(List.of(plain, differentDirectory)); assertEquals("SOURCE_OBSERVATION", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        source.setSourceLocation("openapi.yaml#/paths/~1orders/get"); var openApi = binding(api, "specs/openapi.yaml#/paths/~1orders/get"); openApi.setSourceKind("OPENAPI_SCAN");
        when(bindings.selectList(any())).thenReturn(List.of(openApi)); assertEquals("HTTP_API", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
    }

    @Test void removedOrForeignScopeBindingsNeverAttachCurrentSourceToAnOwnerInAnotherEnvironment() {
        var api = api(21L, "orders:http:21"); when(apis.selectById(21L)).thenReturn(api); when(apis.selectList(any())).thenReturn(List.of(api));
        when(apiCatalog.details(anyList())).thenReturn(Map.of(21L, detail(api, "", null)));
        var source = source(41L, "OrderController.java#OrderController#find"); when(observations.selectById(41L)).thenReturn(source); when(observations.selectList(any())).thenReturn(List.of(source));
        var binding = binding(api, "src/main/java/com/example/OrderController.java#com.example.OrderController#find()"); when(bindings.selectList(any())).thenReturn(List.of(binding));
        assertEquals("HTTP_API", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        binding.setStatus("REMOVED"); assertEquals("SOURCE_OBSERVATION", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        binding.setStatus("DISCOVERED"); binding.setProjectCode("other"); assertEquals("SOURCE_OBSERVATION", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        binding.setProjectCode("orders"); binding.setEnvironment("test"); assertEquals("SOURCE_OBSERVATION", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        binding.setEnvironment("default"); projects.selectById(7L).setEnvironment("test");
        assertEquals("SOURCE_OBSERVATION", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        assertTrue(resolver.resolve(7L, "HTTP_API", 21L).observations().isEmpty(), "old environment owner still exists without adopting current scan evidence");
        api.setEnvironment("test"); binding.setEnvironment("test"); assertEquals("HTTP_API", resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).subjectType());
    }

    @Test void sourceRouteAndReturnChangesInvalidateProseFingerprintAndOnlyTrustedReturnTypeIsSynthesized() {
        var source = source(41L, "src#OrderController#find"); when(observations.selectById(41L)).thenReturn(source); when(observations.selectList(any())).thenReturn(List.of(source));
        source.setResponseType("com.example.OrderDto"); CatalogSubject initial = resolver.resolve(7L, "SOURCE_OBSERVATION", 41L);
        assertEquals("com.example.OrderDto", initial.parameters().get(0).type()); assertTrue(initial.parameters().get(0).children().isEmpty());
        source.setEndpointPath("/orders/latest"); CatalogSubject moved = resolver.resolve(7L, "SOURCE_OBSERVATION", 41L); assertNotEquals(initial.contractHash(), moved.contractHash());
        source.setResponseType("java.lang.String"); assertNotEquals(moved.contractHash(), resolver.resolve(7L, "SOURCE_OBSERVATION", 41L).contractHash());
    }

    @Test void aggregateFingerprintIncludesTypedOperationIdentityAndOwnerProjectIsRequired() {
        var a = source(41L, "src#OrderController#find"); when(observations.selectList(any())).thenReturn(List.of(a));
        String first = resolver.resolve(7L, "PROJECT", 7L).contractHash();
        var b = source(42L, "src#OrderController#find"); when(observations.selectList(any())).thenReturn(List.of(b));
        assertNotEquals(first, resolver.resolve(7L, "PROJECT", 7L).contractHash());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(8L, "PROJECT", 8L));
        when(observations.selectList(any())).thenReturn(List.of()); var api = api(21L, "orders:http:21"); when(apis.selectList(any())).thenReturn(List.of(api));
        when(apiCatalog.details(anyList())).thenReturn(Map.of(21L, detail(api, "same-hash", null)));
        String beforeAcceptance = resolver.resolve(7L, "PROJECT", 7L).contractHash(); String ownerHash = resolver.operations(7L).get(0).contractHash();
        when(apiCatalog.details(anyList())).thenReturn(Map.of(21L, detail(api, "same-hash", json.createObjectNode())));
        assertEquals(ownerHash, resolver.operations(7L).get(0).contractHash()); assertNotEquals(beforeAcceptance, resolver.resolve(7L, "PROJECT", 7L).contractHash());
    }
    private HttpApiAssetEntity api(Long id, String name) { var api = new HttpApiAssetEntity(); api.setId(id); api.setProjectId(7L); api.setProjectCode("orders"); api.setEnvironment("default"); api.setHttpMethod("GET"); api.setRouteTemplate("/orders"); api.setQualifiedName(name); return api; }
    private HttpApiCatalogService.ApiDetail detail(HttpApiAssetEntity api, String acceptedHash, com.fasterxml.jackson.databind.JsonNode accepted) {
        return new HttpApiCatalogService.ApiDetail(new HttpApiCatalogService.ApiSummary(api.getId(), api.getQualifiedName(), 7L, "orders", "default", "GET", "/orders", "CURRENT", true, "", acceptedHash, acceptedHash, "set-revision", 1, List.of("CONTROLLER_SCAN"), "", null), null, accepted, List.of());
    }
    private HttpApiSourceBindingEntity binding(HttpApiAssetEntity api, String location) { var binding = new HttpApiSourceBindingEntity(); binding.setId(api.getId()); binding.setAssetId(api.getId()); binding.setProjectId(7L); binding.setProjectCode(api.getProjectCode()); binding.setEnvironment(api.getEnvironment()); binding.setStatus("DISCOVERED"); binding.setSourceKind("CONTROLLER_SCAN"); binding.setSourceLocation(location); return binding; }
    private ScanProjectToolEntity source(Long id, String location) { var source = new ScanProjectToolEntity(); source.setId(id); source.setProjectId(7L); source.setAssetType("HTTP_API"); source.setSource("scanner"); source.setName("source" + id); source.setSourceLocation(location); source.setParametersJson("[]"); source.setHttpMethod("GET"); source.setEndpointPath("/orders"); return source; }
}
