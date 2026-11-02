package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.knowledge.CapabilityProjectRequestVerificationGateway;
import com.enterprise.ai.control.runtime.RuntimeTrustedAgentExecutionGateway;
import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestSigner;
import com.enterprise.ai.reach.sdk.auth.ReachAiSigner;
import com.enterprise.ai.reach.spring.ReachAiEmbedPrincipal;
import com.enterprise.ai.reach.spring.ReachAiEmbedTokenClient;
import com.enterprise.ai.reach.spring.ReachAiEmbedTokenRequest;
import com.enterprise.ai.reach.spring.ReachAiRegistryProperties;
import com.enterprise.ai.reach.spring.ReachAiRegistryTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Starter signing, Control MVC routes, JWT verification and shared SQL tombstones. */
class PlatformEmbedRevocationIntegrationTest {
    @Test
    void separateSignedLoginsCannotReuseAStoredSessionAfterLogout() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:login_isolation_" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE control_embed_token_revocation (jti VARCHAR(128) PRIMARY KEY, reason VARCHAR(256))");
        jdbc.execute("""
                CREATE TABLE control_embed_session (id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(96), tenant_id VARCHAR(96),
                 app_id VARCHAR(96), project_code VARCHAR(96), agent_id VARCHAR(128), external_user_id VARCHAR(128),
                 external_user_name VARCHAR(128), global_user_id VARCHAR(128), business_session_id VARCHAR(128), page_key VARCHAR(160),
                 page_instance_id VARCHAR(128), route VARCHAR(512), origin VARCHAR(512), sdk_version VARCHAR(64),
                 bridge_actions_json CLOB, status VARCHAR(24), expires_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PlatformEmbedSessionMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PlatformEmbedSessionMapper.class);
        var revocations = new PlatformEmbedBusinessSessionRevocation(jdbc);
        var props = new PlatformEmbedTokenProperties(); props.setSecret("login-isolation-test-secret");
        var tokens = new PlatformEmbedTokenService(new ObjectMapper(), props, revocations);
        var sessions = new PlatformEmbedSessionService(mapper, new ObjectMapper());
        var controller = new PlatformEmbedPublicController(tokens, sessions, mock(PlatformEmbedChatEventService.class),
                mock(CapabilityProxyClient.class), mock(RuntimeProxyClient.class), mock(RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class), mock(PlatformPageActionEventMapper.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(new PlatformEmbedTokenOriginFilter(tokens)).build();
        String loginA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String loginB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        var principal = new PlatformEmbedTokenIssueCommand.BusinessPrincipal("user", "user", "User", java.util.List.of(), Map.of());
        var base = PlatformEmbedTokenIssueCommand.builder().tenantId("default").appId("erp").projectCode("erp")
                .agentId("agent").pageKey("orders").pageInstanceId("page-1").route("/orders")
                .origin("http://localhost").principal(principal);
        String tokenA = tokens.issue(base.businessSessionId(loginA).build()).token();
        String tokenB = tokens.issue(base.businessSessionId(loginB).build()).token();
        var created = mvc.perform(post("/api/embed/chat/sessions").header("Origin", "http://localhost")
                        .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageKey\":\"orders\",\"pageInstanceId\":\"page-1\",\"route\":\"/orders\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String sessionId = new ObjectMapper().readTree(created).path("data").path("sessionId").asText();
        assertFalse(sessionId.isBlank());
        String pending = "/api/embed/chat/sessions/" + sessionId + "/page-actions/pending";
        mvc.perform(get(pending).header("Origin", "http://localhost").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        mvc.perform(get(pending).header("Origin", "http://localhost").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isUnauthorized());
        revocations.revoke("erp", loginA);
        mvc.perform(get(pending).header("Origin", "http://localhost").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(pending).header("Origin", "http://localhost").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/embed/chat/sessions").header("Origin", "http://localhost")
                        .header("Authorization", "Bearer " + tokenB).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageKey\":\"orders\",\"pageInstanceId\":\"page-1\",\"route\":\"/orders\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void signedHostLogoutRevokesOnlyItsSessionAcrossControlInstances() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:embed_flow;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE control_embed_token_revocation (jti VARCHAR(128) PRIMARY KEY, reason VARCHAR(256))");
        var properties = new PlatformEmbedTokenProperties();
        properties.setSecret("integration-test-signing-key");
        var tokensA = new PlatformEmbedTokenService(new ObjectMapper(), properties, new PlatformEmbedBusinessSessionRevocation(jdbc));
        var tokensB = new PlatformEmbedTokenService(new ObjectMapper(), properties, new PlatformEmbedBusinessSessionRevocation(new JdbcTemplate(ds)));
        Set<String> consumedNonces = ConcurrentHashMap.newKeySet();
        var verifier = mock(CapabilityProjectRequestVerificationGateway.class);
        when(verifier.verify(any())).thenAnswer(invocation -> {
            CapabilityProjectRequestVerificationGateway.ProjectRequest r = invocation.getArgument(0);
            String expectedKey = r.projectCode().equals("erp") ? "erp-key" : "other-key";
            String expected = ReachAiSigner.sign("secret", ReachAiProjectRequestSigner.canonical(
                    r.method(), r.path(), r.projectCode(), r.appKey(), r.timestamp(), r.nonce(), r.bodySha256()));
            if (!expectedKey.equals(r.appKey()) || !expected.equals(r.signature()) || !consumedNonces.add(r.nonce())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            }
            return new CapabilityProjectRequestVerificationGateway.VerifiedProject(1L, r.projectCode(), 1L, "hash");
        });
        MockMvc mvcA = controller(tokensA, new PlatformEmbedBusinessSessionRevocation(jdbc), verifier);
        MockMvc mvcB = controller(tokensB, new PlatformEmbedBusinessSessionRevocation(new JdbcTemplate(ds)), verifier);
        ReachAiEmbedTokenClient erp = client("erp", "erp-key", mvcA);
        ReachAiEmbedTokenClient other = client("other", "other-key", mvcA);
        String firstSession = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String secondSession = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        long expires = Instant.now().getEpochSecond() + 60;
        var first = erp.exchange(request(firstSession, expires));
        var renewed = erp.exchange(request(firstSession, expires));
        var anotherLogin = erp.exchange(request(secondSession, expires));
        var anotherProject = other.exchange(request(firstSession, expires));
        assertTrue(first.getExpiresIn() <= 60);
        assertSessionStatus(mvcB, first.getToken(), 200);
        assertSessionStatus(mvcB, anotherLogin.getToken(), 200);
        assertThrows(IllegalStateException.class,
                () -> client("erp", "other-key", mvcA).revokeBusinessSession(firstSession));
        assertSessionStatus(mvcB, first.getToken(), 200);

        erp.revokeBusinessSession(firstSession);
        erp.revokeBusinessSession(firstSession); // safe repeated notification
        assertSessionStatus(mvcB, first.getToken(), 401);
        assertSessionStatus(mvcB, renewed.getToken(), 401);
        assertSessionStatus(mvcB, anotherLogin.getToken(), 200);
        assertSessionStatus(mvcB, anotherProject.getToken(), 200);
        assertThrows(IllegalStateException.class, () -> erp.exchange(request(firstSession, expires)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM control_embed_token_revocation", Integer.class));
    }

    private static MockMvc controller(PlatformEmbedTokenService tokens, PlatformEmbedBusinessSessionRevocation revocation,
                                      CapabilityProjectRequestVerificationGateway verifier) {
        var capability = mock(CapabilityProxyClient.class);
        when(capability.verifyEmbedTokenExchange(any(), any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok(Map.of("tokenTtlSeconds", 600)));
        var runtime = mock(RuntimeProxyClient.class);
        when(runtime.listAgents(isNull(), anyString())).thenAnswer(invocation -> {
            String project = invocation.getArgument(1);
            return ResponseEntity.ok((Object) java.util.List.of(Map.of(
                    "id", "agent", "keySlug", "bot", "projectCode", project, "enabled", true)));
        });
        var sessions = mock(PlatformEmbedSessionService.class);
        when(sessions.create(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            PlatformEmbedTokenClaims claims = invocation.getArgument(0);
            var session = new PlatformEmbedSessionEntity();
            session.setSessionId("embed-test"); session.setAgentId(claims.getAgentId());
            return session;
        });
        var controller = new PlatformEmbedPublicController(tokens, sessions, mock(PlatformEmbedChatEventService.class),
                capability, runtime, mock(RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedStreamRelay.class), mock(PlatformPageActionEventMapper.class));
        controller.setProjectRequestVerifier(verifier);
        controller.setBusinessSessionRevocation(revocation);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static ReachAiEmbedTokenClient client(String project, String key, MockMvc mvc) {
        var properties = new ReachAiRegistryProperties();
        properties.getProject().setCode(project);
        properties.getProject().setName(project);
        properties.getRegistry().setUrl("http://localhost");
        properties.getRegistry().setAppKey(key);
        properties.getRegistry().setAppSecret("secret");
        ReachAiRegistryTransport transport = (method, url, headers, body) -> {
            try {
                var builder = post(URI.create(url)).contentType(MediaType.APPLICATION_JSON).content((byte[]) body);
                headers.forEach((name, value) -> builder.header(name, value));
                var response = mvc.perform(builder).andReturn().getResponse();
                if (response.getStatus() >= 400) throw new IOException("request rejected");
                return response.getContentAsString();
            } catch (IOException e) { throw e; }
            catch (Exception e) { throw new IOException("test transport failed", e); }
        };
        return new ReachAiEmbedTokenClient(properties, transport);
    }

    private static ReachAiEmbedTokenRequest request(String session, long expiry) {
        return new ReachAiEmbedTokenRequest("bot", "page", "page-1", "/", "http://localhost",
                new ReachAiEmbedPrincipal("user", "user", "User", Collections.emptyList(), Collections.emptyMap(), session, expiry));
    }

    private static void assertSessionStatus(MockMvc mvc, String token, int expected) throws Exception {
        mvc.perform(post("/api/embed/chat/sessions").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pageInstanceId\":\"page-1\"}"))
                .andExpect(status().is(expected));
    }
}
