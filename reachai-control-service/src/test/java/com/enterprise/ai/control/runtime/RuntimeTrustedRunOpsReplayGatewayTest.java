package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeTrustedRunOpsReplayGatewayTest {
    @Test void actualHttpRequestBindsPathActorTenantAndExactUtf8CommandToHmac() throws Exception {
        String secret="replay-local-test-secret-at-least-32-bytes";
        String path="/internal/runtime/runops/traces/trace-1/replay";
        var received=new AtomicReference<Map<String,Object>>();
        var failure=new AtomicReference<Throwable>();
        var mapper=new ObjectMapper();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(path,exchange->{
            try {
                byte[] body=exchange.getRequestBody().readAllBytes();
                var h=exchange.getRequestHeaders();
                assertEquals("platform:7:operator",h.getFirst(InternalServiceAuthHeaders.IDENTITY_USER_ID));
                assertEquals("default",h.getFirst(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
                assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,h.getFirst(InternalServiceAuthHeaders.IDENTITY_SOURCE));
                String digest=InternalServiceHmac.bodySha256Hex(body);
                assertEquals(digest,h.getFirst(InternalServiceAuthHeaders.BODY_SHA256));
                String canonical=InternalServiceHmac.canonical("POST",path,
                        h.getFirst(InternalServiceAuthHeaders.CALLER),h.getFirst(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                        h.getFirst(InternalServiceAuthHeaders.IDENTITY_TENANT_ID),h.getFirst(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                        h.getFirst(InternalServiceAuthHeaders.TIMESTAMP),h.getFirst(InternalServiceAuthHeaders.NONCE),digest);
                assertEquals(InternalServiceHmac.sign(secret,canonical),h.getFirst(InternalServiceAuthHeaders.SIGNATURE));
                received.set(mapper.readValue(body,Map.class));
            } catch(Throwable ex) { failure.set(ex); }
            byte[] response="{\"success\":true,\"replayTraceId\":\"new-trace\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var gateway=new RuntimeTrustedRunOpsReplayGateway(new InternalServiceAuthSigner(secret),mapper,
                    "http://127.0.0.1:"+server.getAddress().getPort());
            var command=Map.<String,Object>of("messageOverride","只读字典查询","roles",List.of("PROJECT_OWNER"));
            var response=gateway.replay("trace-1",command,"platform:7:operator");
            if(failure.get()!=null) throw new AssertionError(failure.get());
            assertEquals(200,response.getStatusCode().value());
            assertEquals("new-trace",response.getBody().get("replayTraceId"));
            assertEquals(command,received.get());
        } finally { server.stop(0); }
    }
}
