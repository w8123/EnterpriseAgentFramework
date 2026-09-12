package com.enterprise.ai.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeSignedMultipartHttpTest {
    private static final String SECRET = "signed-multipart-local-test-secret-32-bytes";
    private static final String ROUTE = "/internal/knowledge/console/document-import/jobs";
    @TempDir Path temporary;

    @Test
    void signedMultipartBindsFileAndChineseFieldsThroughRealTomcatAndMvc() throws Exception {
        String content = "签名原件：中文回读";
        byte[] body = multipart(content);
        var response = send(body, body);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains(content));
        assertTrue(response.body().contains("知识库甲"));
        assertTrue(response.body().contains("upload.txt"));
        assertEquals("42", new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).path("actor").asText());
    }

    @Test
    void alteredMultipartIsRejectedBeforeController() throws Exception {
        var response = send(multipart("changed"), multipart("original"));
        assertEquals(401, response.statusCode());
    }

    @Test
    void signedBodySpoolingStillBindsTheEntireFile() throws Exception {
        String content = "字".repeat(400_000);
        byte[] body = multipart(content);
        var response = send(body, body);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains(content));
    }

    @Test
    void servletFileLimitStillRejectsSignedUploads() throws Exception {
        byte[] body = multipart("x".repeat(2_097_153));
        assertEquals(413, send(body, body).statusCode());
    }

    @Test
    void servletRequestLimitStillRejectsSignedUploads() throws Exception {
        byte[] body = multipart("x".repeat(3_145_728));
        assertEquals(413, send(body, body).statusCode());
    }

    @Test
    void projectCredentialMultipartUsesTheSameVerifiedBody() throws Exception {
        byte[] body = multipart("项目签名原件");
        var response = send(body, body, true);
        assertEquals(200, response.statusCode());
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        assertEquals("项目签名原件", result.path("content").asText());
        assertEquals("credential:3", result.path("actor").asText());
    }

    private HttpResponse<String> send(byte[] actual, byte[] signed) throws Exception {
        return send(actual, signed, false);
    }

    private HttpResponse<String> send(byte[] actual, byte[] signed, boolean project) throws Exception {
        var nonce = mock(KnowledgeInternalNonceStore.class);
        when(nonce.tryConsume(any(), any(), any())).thenReturn(true);
        var filter = new KnowledgeBizIndexConsoleAuthFilter(SECRET, "", 300, 4_194_304, nonce);
        var multipartConfig = new MultipartConfigElement(temporary.toString(), 2_097_152, 3_145_728, 1024);
        filter.configureMultipart(multipartConfig);
        var projectFilter = new KnowledgeProjectIngressAuthFilter(SECRET, "", 300, 4_194_304, nonce);
        projectFilter.configureMultipart(multipartConfig);
        String route = project ? "/internal/knowledge/project-ingress/projects/orders/biz-index/orders_idx/batch" : ROUTE;
        String identitySource = project ? InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL
                : InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION;
        String tenant = project ? "orders" : "default", actor = project ? "credential:3" : "42";
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getLoopbackAddress());
        factory.setBaseDirectory(temporary.resolve("tomcat").toFile());
        var context = new AnnotationConfigWebApplicationContext();
        context.register(WebConfiguration.class);
        var server = factory.getWebServer(servletContext -> {
            var servlet = servletContext.addServlet("mvc", new DispatcherServlet(context));
            servlet.setLoadOnStartup(1);
            servlet.addMapping("/");
            servlet.setMultipartConfig(multipartConfig);
            servletContext.addFilter("signature", project ? projectFilter : filter)
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
        });
        try {
            server.start();
            String timestamp = Long.toString(System.currentTimeMillis()), requestNonce = UUID.randomUUID().toString();
            String digest = InternalServiceHmac.bodySha256Hex(signed);
            String canonical = InternalServiceHmac.canonical("POST", route,
                    InternalServiceAuthHeaders.CALLER_CONTROL, identitySource,
                    tenant, actor, timestamp, requestNonce, digest);
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + route))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Content-Type", "multipart/form-data; boundary=ReachAI-Test-Boundary")
                    .header(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_CONTROL)
                    .header(InternalServiceAuthHeaders.IDENTITY_SOURCE, identitySource)
                    .header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, tenant)
                    .header(InternalServiceAuthHeaders.IDENTITY_USER_ID, actor)
                    .header(InternalServiceAuthHeaders.TIMESTAMP, timestamp)
                    .header(InternalServiceAuthHeaders.NONCE, requestNonce)
                    .header(InternalServiceAuthHeaders.BODY_SHA256, digest)
                    .header(InternalServiceAuthHeaders.SIGNATURE, InternalServiceHmac.sign(SECRET, canonical))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(actual)).build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } finally {
            server.stop();
            context.close();
            try (var files = Files.list(temporary)) {
                assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith("reachai-knowledge-parts-")),
                        "Multipart files must be reclaimed on success and rejection");
            }
        }
    }

    private static byte[] multipart(String content) {
        return ("--ReachAI-Test-Boundary\r\nContent-Disposition: form-data; name=\"knowledgeBaseCode\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n知识库甲\r\n"
                + "--ReachAI-Test-Boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload.txt\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + content + "\r\n--ReachAI-Test-Boundary--\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Configuration
    @EnableWebMvc
    static class WebConfiguration {
        @Bean StandardServletMultipartResolver multipartResolver() { return new StandardServletMultipartResolver(); }
        @Bean UploadController uploadController() { return new UploadController(); }
    }

    @RestController
    static class UploadController {
        @PostMapping({ROUTE, "/internal/knowledge/project-ingress/projects/orders/biz-index/orders_idx/batch"})
        Map<String, String> upload(@RequestParam("file") MultipartFile file,
                                   @RequestParam("knowledgeBaseCode") String code,
                                   jakarta.servlet.http.HttpServletRequest request) throws Exception {
            Object actor = request.getAttribute(KnowledgeBizIndexConsoleAuthFilter.VERIFIED_ACTOR_ATTRIBUTE);
            if (actor == null) actor = request.getAttribute(KnowledgeProjectIngressAuthFilter.VERIFIED_CREDENTIAL_ATTRIBUTE);
            return Map.of("content", new String(file.getBytes(), StandardCharsets.UTF_8), "code", code,
                    "filename", file.getOriginalFilename(), "actor", String.valueOf(actor));
        }
    }
}
