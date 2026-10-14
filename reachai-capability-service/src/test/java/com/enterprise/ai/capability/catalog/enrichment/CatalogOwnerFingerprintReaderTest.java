package com.enterprise.ai.capability.catalog.enrichment;

import com.enterprise.ai.capability.catalog.businessmethod.*;
import com.enterprise.ai.capability.catalog.httpapi.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CatalogOwnerFingerprintReaderTest {
    final BusinessMethodAssetMapper methods = mock(BusinessMethodAssetMapper.class);
    final BusinessMethodRevisionMapper revisions = mock(BusinessMethodRevisionMapper.class);
    final HttpApiAssetMapper apis = mock(HttpApiAssetMapper.class);
    final HttpApiContractCanonicalizer contracts = mock(HttpApiContractCanonicalizer.class);
    final CatalogOwnerFingerprintReader reader = new CatalogOwnerFingerprintReader(methods, revisions, apis, contracts);

    @Test void batchesRelatedAcceptedOwnersAndRejectsForeignOrMismatchedRevisionEvidence() {
        var method = method(21L, 7L, 31L); var broken = method(22L, 7L, 32L); var foreign = method(23L, 8L, 33L);
        var goodRevision = revision(31L, 21L, "method-hash"); var wrongRevision = revision(32L, 99L, "wrong-owner");
        var foreignRevision = revision(33L, 23L, "foreign-project");
        when(methods.selectList(any())).thenReturn(List.of(method, broken, foreign));
        when(revisions.selectList(any())).thenReturn(List.of(goodRevision, wrongRevision, foreignRevision));
        var api = new HttpApiAssetEntity(); api.setId(21L); api.setProjectId(7L); api.setAcceptedContractHash("api-hash");
        when(contracts.readStoredContract(api, "api-hash", null)).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        when(apis.selectList(any())).thenReturn(List.of(api));
        var result = reader.read(7L, List.of(new CatalogOwnerFingerprintReader.Identity("BUSINESS_METHOD", 21L),
                new CatalogOwnerFingerprintReader.Identity("BUSINESS_METHOD", 22L),
                new CatalogOwnerFingerprintReader.Identity("HTTP_API", 21L)));
        assertEquals(java.util.Set.of("BUSINESS_METHOD:21", "HTTP_API:21"), result.keySet());
        assertEquals("method-hash", result.get("BUSINESS_METHOD:21").contractHash());
        assertEquals("api-hash", result.get("HTTP_API:21").contractHash());
        verify(methods, times(1)).selectList(any()); verify(revisions, times(1)).selectList(any()); verify(apis, times(1)).selectList(any());
        verifyNoMoreInteractions(methods, revisions, apis);
    }

    @Test void absentRelatedOwnersNeedNoQueriesAndCannotCreateEvidence() {
        assertEquals(java.util.Map.of(), reader.read(7L, List.of()));
        verifyNoInteractions(methods, revisions, apis, contracts);
    }

    @Test void corruptAcceptedApiContractCannotProveCurrentGraphEvidence() {
        var api = new HttpApiAssetEntity(); api.setId(21L); api.setProjectId(7L); api.setAcceptedContractHash("claimed-hash");
        api.setAcceptedContractJson("malformed"); when(apis.selectList(any())).thenReturn(List.of(api));
        assertTrue(reader.read(7L, List.of(new CatalogOwnerFingerprintReader.Identity("HTTP_API", 21L))).isEmpty());
        verify(contracts).readStoredContract(api, "claimed-hash", "malformed"); verifyNoInteractions(methods, revisions);
    }

    private BusinessMethodAssetEntity method(Long id, Long project, Long revision) {
        var value = new BusinessMethodAssetEntity(); value.setId(id); value.setProjectId(project);
        value.setAcceptedRevisionId(revision); value.setStatus("ACCEPTED"); return value;
    }
    private BusinessMethodRevisionEntity revision(Long id, Long asset, String hash) {
        var value = new BusinessMethodRevisionEntity(); value.setId(id); value.setAssetId(asset); value.setContractHash(hash); return value;
    }
}
