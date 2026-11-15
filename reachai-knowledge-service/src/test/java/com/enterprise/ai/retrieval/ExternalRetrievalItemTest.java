package com.enterprise.ai.retrieval;

import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.external.ExternalKnowledgeHit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalRetrievalItemTest {

    @Test
    void externalPassagesUseNamespacedIdentifiers() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(9L);
        kb.setCode("ext_policy");
        kb.setExternalProvider("RAGFLOW");

        var item = DefaultKnowledgeRetrievalEngine.externalItem(kb, new ExternalKnowledgeHit("c1", "d1", "制度.pdf", "退货 7 天", 0.8f), 0);

        assertThat(item.getChunkDbId()).isNull();
        assertThat(item.getChunkId()).isEqualTo("ext:ext_policy:c1");
        assertThat(item.getFileId()).isEqualTo("ext:ragflow:d1");
        assertThat(item.getFileName()).isEqualTo("制度.pdf");
        assertThat(item.getExternalKnowledgeBaseId()).isEqualTo(9L);
        assertThat(item.getScore()).isEqualTo(0.8f);
    }
}
