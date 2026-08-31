package com.enterprise.ai.control.agentskill;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSkillBundleLimitTest {

    @Test
    void allowsLargerRepositoryDiscoveryWhileKeepingSelectedPackageStrict() throws Exception {
        AgentSkillPackageInspector inspector = new AgentSkillPackageInspector(
                1024, 4096, 2048, 16,
                8192, 16384, 8192, 64);
        byte[] repository = repositoryBundle();
        assertTrue(repository.length > 1024);

        AgentSkillPackageInspector.BundleDiscovery discovery = inspector.discoverBundle(repository);
        AgentSkillPackageInspector.SelectedPackage selected = inspector.selectFromBundle(
                repository, discovery.candidates().get(0).sourceRoot());

        assertEquals("demo", selected.inspection().name());
        assertTrue(selected.archive().length < 1024);
        assertEquals("demo", inspector.inspect(selected.archive()).name());
        assertThrows(AgentSkillException.class, () -> inspector.inspect(repository));
    }

    private byte[] repositoryBundle() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            byte[] noise = new byte[3000];
            new Random(7).nextBytes(noise);
            zip.putNextEntry(new ZipEntry("repo/noise.bin"));
            zip.write(noise);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("repo/skills/demo/SKILL.md"));
            zip.write("---\nname: demo\ndescription: Demo.\n---\nUse demo.\n"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
