package com.enterprise.ai.retrieval;

import com.enterprise.ai.domain.dto.RetrievalTestResponse;
import com.enterprise.ai.security.FileAccessSnapshot;
import com.enterprise.ai.security.PermissionService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalRetrievalAuthorizationTest {

    @Test
    void externalPassagesAreReleasedOnlyWhenTheirBaseIsOpen() {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.resolveAuthorizedChunks(any(), any())).thenReturn(List.of());
        var authorization = new KnowledgeRetrievalAuthorization(permissions);
        var open = RetrievalTestResponse.RetrievalItem.builder().chunkId("ext:a:1").externalProvider("RAGFLOW").externalKnowledgeBaseId(1L).build();
        var closed = RetrievalTestResponse.RetrievalItem.builder().chunkId("ext:b:1").externalProvider("IMA").externalKnowledgeBaseId(2L).build();
        var snapshot = new FileAccessSnapshot(null, Map.of(), Set.of(1L));

        var kept = authorization.retain(snapshot, new ArrayList<>(List.of(open, closed)));

        assertThat(kept).containsExactly(open);
        var core = KnowledgeRetrievalCoreResponse.RetrievalItem.builder().externalProvider("RAGFLOW").externalKnowledgeBaseId(1L).build();
        assertThat(authorization.isCurrent(snapshot, List.of(core))).isTrue();
        var coreClosed = KnowledgeRetrievalCoreResponse.RetrievalItem.builder().externalProvider("IMA").externalKnowledgeBaseId(2L).build();
        assertThat(authorization.isCurrent(snapshot, List.of(core, coreClosed))).isFalse();
    }
}
