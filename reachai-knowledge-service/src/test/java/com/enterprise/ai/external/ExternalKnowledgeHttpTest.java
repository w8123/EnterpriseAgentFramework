package com.enterprise.ai.external;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalKnowledgeHttpTest {

    @Test
    void refusesMetadataAndNonHttpTargets() {
        var http = new ExternalKnowledgeHttp(5, true);

        assertThatThrownBy(() -> http.checkedUri("http://169.254.169.254/latest")).hasMessageContaining("不允许");
        assertThatThrownBy(() -> http.checkedUri("file:///etc/passwd")).hasMessageContaining("http");
        assertThatThrownBy(() -> http.checkedUri("http://user:pw@127.0.0.1/")).hasMessageContaining("http");
        assertThat(http.checkedUri("http://127.0.0.1:9380").getPort()).isEqualTo(9380);
    }

    @Test
    void privateNetworkCanBeDisabled() {
        var http = new ExternalKnowledgeHttp(5, false);

        assertThatThrownBy(() -> http.checkedUri("http://10.0.0.5:9380")).hasMessageContaining("内网");
        assertThatThrownBy(() -> http.checkedUri("http://127.0.0.1")).hasMessageContaining("内网");
    }

    @Test
    void joinsPathsWithoutDoubleSlashes() {
        assertThat(ExternalKnowledgeHttp.join("https://ima.qq.com/", "/openapi/x")).isEqualTo("https://ima.qq.com/openapi/x");
    }
}
