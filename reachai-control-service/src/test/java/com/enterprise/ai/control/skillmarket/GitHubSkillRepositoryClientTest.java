package com.enterprise.ai.control.skillmarket;

import com.enterprise.ai.control.agentskill.AgentSkillException;
import com.enterprise.ai.control.agentskill.AgentSkillPackageInspector;
import com.enterprise.ai.control.skillmarket.GitHubSkillRepositoryClient.ProbeArtifact;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ProbeRequest;
import com.enterprise.ai.control.skillmarket.SkillMarketHttpTransport.HttpPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubSkillRepositoryClientTest {

    private static final String COMMIT = "a".repeat(40);

    private SkillMarketHttpTransport transport;
    private GitHubSkillRepositoryClient client;

    @BeforeEach
    void setUp() {
        SkillMarketProperties properties = new SkillMarketProperties();
        transport = mock(SkillMarketHttpTransport.class);
        AgentSkillPackageInspector inspector = new AgentSkillPackageInspector(
                20 * 1024 * 1024L, 100 * 1024 * 1024L, 20 * 1024 * 1024L, 1024,
                64 * 1024 * 1024L, 256 * 1024 * 1024L, 64 * 1024 * 1024L, 8192);
        client = new GitHubSkillRepositoryClient(properties, transport, inspector, new ObjectMapper());
    }

    @Test
    void resolvesDefaultBranchToCommitAndDiscoversExpectedSkill() throws Exception {
        byte[] bundle = bundle();
        when(transport.get(any(), any(), any(), anyLong())).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if (uri.getPath().endsWith("/repos/vercel-labs/agent-skills")) {
                return payload(200, uri, """
                        {"private":false,"default_branch":"main","html_url":"https://github.com/vercel-labs/agent-skills",
                        "description":"Official skills","stargazers_count":30000,"archived":false,
                        "updated_at":"2026-08-24T00:00:00Z","license":{"spdx_id":"MIT"}}
                        """.getBytes(StandardCharsets.UTF_8));
            }
            if (uri.getPath().contains("/commits/")) {
                return payload(200, uri, ("{\"sha\":\"" + COMMIT + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return payload(200, URI.create("https://codeload.github.com/vercel-labs/agent-skills/zip/" + COMMIT),
                    bundle);
        });

        ProbeArtifact probe = client.probe(new ProbeRequest(
                "https://github.com/vercel-labs/agent-skills", "SKILLS_SH",
                "vercel-labs/agent-skills/demo", "demo"));

        assertEquals(COMMIT, probe.result().repository().commitSha());
        assertEquals("MIT", probe.result().repository().license());
        assertEquals(1, probe.result().candidateCount());
        assertTrue(probe.result().suggestedSourceRoot().endsWith("/skills/demo"));
        assertEquals("demo", probe.result().candidates().get(0).name());
    }

    @Test
    void rejectsNonGithubUserSuppliedSourcesBeforeAnyNetworkRequest() {
        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> client.probe(new ProbeRequest(
                        "https://example.com/skills.zip", "GITHUB", null, null)));

        assertEquals("SKILL_MARKET_SOURCE_UNSUPPORTED", failure.code());
        verify(transport, never()).get(any(), any(), any(), anyLong());
    }

    @Test
    void rejectsPrivateRepositoriesInsteadOfReusingAmbientCredentials() {
        when(transport.get(any(), any(), any(), anyLong()))
                .thenReturn(payload(200, URI.create("https://api.github.com/repos/acme/private"),
                        "{\"private\":true,\"default_branch\":\"main\"}"
                                .getBytes(StandardCharsets.UTF_8)));

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> client.probe(new ProbeRequest(
                        "https://github.com/acme/private", "GITHUB", null, null)));

        assertEquals("SKILL_MARKET_SOURCE_UNSUPPORTED", failure.code());
    }

    private byte[] bundle() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("vercel-labs-agent-skills-123/README.md"));
            zip.write("repo".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("vercel-labs-agent-skills-123/skills/demo/SKILL.md"));
            zip.write("---\nname: demo\ndescription: Demonstration Skill.\nlicense: MIT\n---\n# Demo\n"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private HttpPayload payload(int status, URI uri, byte[] body) {
        return new HttpPayload(status, uri, Map.of(), body);
    }
}
