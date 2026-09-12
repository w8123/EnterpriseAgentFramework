package com.enterprise.ai.config;

import io.milvus.param.ConnectParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilvusConfigTest {

    @Test
    void addsAuthorizationWhenCredentialsAreConfigured() {
        ConnectParam connectParam = new MilvusConfig("127.0.0.1", 19530, "reachai", "secret")
                .createConnectParam();

        assertEquals("reachai", connectParam.getUserName());
        assertTrue(connectParam.getAuthorization() != null && !connectParam.getAuthorization().isBlank());
    }

    @Test
    void keepsSdkDefaultsWhenCredentialsAreAbsent() {
        ConnectParam connectParam = new MilvusConfig("127.0.0.1", 19530, "", "")
                .createConnectParam();

        assertEquals("", connectParam.getUserName());
    }

    @Test
    void rejectsPartialCredentials() {
        MilvusConfig config = new MilvusConfig("127.0.0.1", 19530, "reachai", "");

        assertThrows(IllegalStateException.class, config::createConnectParam);
    }
}
