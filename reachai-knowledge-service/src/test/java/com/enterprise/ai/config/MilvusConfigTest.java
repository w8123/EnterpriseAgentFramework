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

    @Test
    void unreachableMilvusFailsFastWithActionableMessage() throws Exception {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        MilvusConfig config = new MilvusConfig("127.0.0.1", closedPort, "", "");
        long started = System.nanoTime();

        com.enterprise.ai.common.exception.BizException failure =
                assertThrows(com.enterprise.ai.common.exception.BizException.class, config::connect);

        assertEquals(503, failure.getCode());
        assertTrue(failure.getMessage().startsWith("向量库 Milvus（127.0.0.1:" + closedPort + "）还没连上"));
        assertTrue(failure.getMessage().contains("MILVUS_HOST"));
        assertTrue(System.nanoTime() - started < java.time.Duration.ofSeconds(5).toNanos());
    }
}
