package com.enterprise.ai.external;

import com.enterprise.ai.domain.KnowledgeBaseSettings;
import com.enterprise.ai.domain.dto.ExternalKnowledgeConnectionRequest;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalKnowledgeServiceTest {
    private final ExternalKnowledgeSecretCipher cipher = new ExternalKnowledgeSecretCipher("", "test-service-secret");
    private final KnowledgeBaseRepository bases = mock(KnowledgeBaseRepository.class);
    private final KnowledgeBaseLookup lookup = mock(KnowledgeBaseLookup.class);
    private final AtomicReference<ExternalKnowledgeConnection> lastSearch = new AtomicReference<>();
    private final ExternalKnowledgeProvider fake = new ExternalKnowledgeProvider() {
        @Override public String type() { return "RAGFLOW"; }
        @Override public String displayName() { return "RAGFlow"; }
        @Override public List<ExternalKnowledgeConnection.RemoteDataset> listDatasets(ExternalKnowledgeConnection connection) {
            lastSearch.set(connection);
            return List.of(new ExternalKnowledgeConnection.RemoteDataset("ds1", "制度"));
        }
        @Override public List<ExternalKnowledgeHit> search(ExternalKnowledgeConnection connection, String query, int topK, float threshold) {
            lastSearch.set(connection);
            return List.of(new ExternalKnowledgeHit("c1", "d1", "制度.pdf", "内容", 0.8f));
        }
    };
    private final ExternalKnowledgeService service = new ExternalKnowledgeService(List.of(fake), cipher, bases, lookup);

    private ExternalKnowledgeConnectionRequest draft(String apiKey) {
        var request = new ExternalKnowledgeConnectionRequest();
        request.setProvider("ragflow");
        request.setBaseUrl("http://10.0.0.5:9380");
        request.setApiKey(apiKey);
        var dataset = new ExternalKnowledgeConnectionRequest.Dataset();
        dataset.setId("ds1");
        dataset.setName("制度");
        request.setDatasets(List.of(dataset));
        return request;
    }

    private KnowledgeBase saved() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(7L);
        kb.setCode("ext_policy");
        service.apply(kb, service.newConnection(draft("secret-key")));
        return kb;
    }

    @Test
    void storesKeyEncryptedAndNeverShowsIt() {
        KnowledgeBase kb = saved();

        assertThat(kb.getSourceType()).isEqualTo(KnowledgeBaseSettings.SOURCE_EXTERNAL);
        assertThat(kb.getExternalProvider()).isEqualTo("RAGFLOW");
        assertThat(kb.getExternalSecret()).startsWith("aesgcm:v1:").doesNotContain("secret-key");
        assertThat(kb.getExternalConfig()).doesNotContain("secret-key").contains("ds1");
        var view = service.view(kb);
        assertThat(view.isApiKeyConfigured()).isTrue();
        assertThat(view.toString()).doesNotContain("secret-key");

        service.search(kb, "问题", 5, 0.2f);
        assertThat(lastSearch.get().apiKey()).isEqualTo("secret-key");
    }

    @Test
    void testReusesSavedKeyOnlyForTheSameAddress() {
        KnowledgeBase kb = saved();
        when(lookup.requireByCode("ext_policy")).thenReturn(kb);
        var sameAddress = draft(null);
        sameAddress.setKbCode("ext_policy");

        assertThat(service.testConnection(sameAddress)).hasSize(1);
        assertThat(lastSearch.get().apiKey()).isEqualTo("secret-key");

        var otherAddress = draft(null);
        otherAddress.setKbCode("ext_policy");
        otherAddress.setBaseUrl("http://attacker.example.com");
        assertThatThrownBy(() -> service.testConnection(otherAddress)).hasMessageContaining("重新填写密钥");
    }

    @Test
    void savingRequiresDatasetsAndKnownProvider() {
        var noDatasets = draft("k");
        noDatasets.setDatasets(List.of());
        assertThatThrownBy(() -> service.newConnection(noDatasets)).hasMessageContaining("至少选择");

        var unknown = draft("k");
        unknown.setProvider("fastgpt");
        assertThatThrownBy(() -> service.newConnection(unknown)).hasMessageContaining("不支持");
    }

    @Test
    void updateKeepsSavedKeyWhenBlank() {
        KnowledgeBase kb = saved();
        when(lookup.requireByCode("ext_policy")).thenReturn(kb);
        AtomicReference<KnowledgeBase> written = new AtomicReference<>();
        when(bases.update(any(KnowledgeBase.class), any())).thenAnswer(call -> {
            written.set(call.getArgument(0));
            return 1;
        });

        service.updateConnection("ext_policy", draft(""));

        assertThat(cipher.decrypt(written.get().getExternalSecret())).isEqualTo("secret-key");
    }
}
