package com.enterprise.ai.agent.tools;

import com.enterprise.ai.skill.generated.aitextretrieval.SkillAutoConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedSkillModuleIntegrationTest {

    @Test
    void generatedSkillRegistersAndExecutesThroughToolRegistry() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ai/retrieval/test", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"ok\",\"source\":\"retrieval\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("generated-skill-test", Map.of(
                            "skill.ai-text-retrieval.base-url", "http://localhost:" + server.getAddress().getPort()
                    ))
            );
            context.register(SkillAutoConfiguration.class, ToolRegistry.class);
            context.refresh();

            ToolRegistry toolRegistry = context.getBean(ToolRegistry.class);
            assertTrue(toolRegistry.contains("retrieval_test"));

            Object result = toolRegistry.execute("retrieval_test", Map.of(
                    "body_json", "{\"query\":\"合同风险\"}"
            ));

            assertEquals("{\"status\":\"ok\",\"source\":\"retrieval\"}", result);
            assertEquals("{\"query\":\"合同风险\"}", requestBody.get());
        } finally {
            server.stop(0);
        }
    }
}
