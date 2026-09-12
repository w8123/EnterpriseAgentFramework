package com.enterprise.ai.control.agentskill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuiltinAgentSkillSourceTest {
    private static final byte[] BINARY = {31, -117, 8, 0, 13, 10, -1, 0};

    @Test
    void textCheckoutLineEndingsDoNotChangeBuiltinPackageIdentity() throws IOException {
        byte[] lf = packageWithText("name: example\nsecond: line\n");
        byte[] crlf = packageWithText("name: example\r\nsecond: line\r\n");

        assertArrayEquals(lf, crlf, "Windows and Unix checkouts must publish identical bytes for one version");
    }

    @Test
    void binaryResourcesRetainTheirExactBytes() throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(packageWithText("line\r\n")))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("reachai-onboarding/artifacts/payload.tgz")) {
                    assertArrayEquals(BINARY, zip.readAllBytes());
                    return;
                }
            }
        }
        throw new AssertionError("Binary fixture is missing from the archive");
    }

    @Test
    void bundledBytesMatchTheirReleasedVersions() throws Exception {
        JsonNode releases;
        try (var stream = getClass().getResourceAsStream("/builtin-agent-skill-releases.json")) {
            assertNotNull(stream);
            releases = new ObjectMapper().readTree(stream);
        }
        BuiltinAgentSkillSource source = new BuiltinAgentSkillSource();
        var inspector = new AgentSkillPackageInspector(20 * 1024 * 1024L, 100 * 1024 * 1024L,
                20 * 1024 * 1024L, 1024);
        for (var descriptor : source.descriptors()) {
            String identity = descriptor.name() + "@" + descriptor.version();
            JsonNode release = releases.path(identity);
            assertTrue(release.isObject(), () -> "Record a new release before publishing " + identity);
            var inspection = inspector.inspect(source.packageBytes(descriptor.name()));
            assertEquals(release.path("sourceSha256").asText(), inspection.sourceSha256(),
                    () -> "Builtin package bytes changed without a version bump: " + identity);
            assertEquals(release.path("contentTreeSha256").asText(), inspection.contentTreeSha256(), identity);
        }
    }

    private byte[] packageWithText(String text) throws IOException {
        PathMatchingResourcePatternResolver resolver = mock(PathMatchingResourcePatternResolver.class);
        when(resolver.getResources(anyString())).thenReturn(new Resource[] {
                resource("SKILL.md", text.getBytes(StandardCharsets.UTF_8)),
                resource("agents/openai.yaml", text.getBytes(StandardCharsets.UTF_8)),
                resource("scripts/install.ps1", text.getBytes(StandardCharsets.UTF_8)),
                resource("references/contract.json", text.getBytes(StandardCharsets.UTF_8)),
                resource("artifacts/payload.tgz", BINARY)
        });
        BuiltinAgentSkillSource source = new BuiltinAgentSkillSource();
        ReflectionTestUtils.setField(source, "resources", resolver);
        return source.packageBytes("reachai-onboarding");
    }

    private Resource resource(String path, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override
            public URI getURI() {
                return URI.create("file:/fixture/ai-assist/skills/reachai-onboarding/" + path);
            }
        };
    }
}
