package com.enterprise.ai.control.agentskill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in acceptance probe for a real multi-Skill repository or plugin ZIP.
 * The archive is read only, scripts are never launched, and no candidate is persisted.
 */
class AgentSkillExternalBundleProbeTest {

    private static final String EXTERNAL_BUNDLE = "reachai.external-skill-bundle";
    private static final String EXPECTED_CANDIDATE = "reachai.external-skill-bundle-candidate";
    private static final String MIN_CANDIDATES = "reachai.external-skill-bundle-min-candidates";

    private final AgentSkillPackageInspector inspector = new AgentSkillPackageInspector(
            20 * 1024 * 1024,
            100 * 1024 * 1024,
            20 * 1024 * 1024,
            1024);

    @Test
    @EnabledIfSystemProperty(named = EXTERNAL_BUNDLE, matches = ".+")
    void discoversAndSelectsOneSkillFromARealExternalBundle() throws IOException {
        Path bundle = Path.of(System.getProperty(EXTERNAL_BUNDLE)).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(bundle), () -> "External Skill bundle does not exist: " + bundle);
        byte[] archive = Files.readAllBytes(bundle);

        AgentSkillPackageInspector.BundleDiscovery discovery = inspector.discoverBundle(archive);

        int minimumCandidates = Integer.parseInt(System.getProperty(MIN_CANDIDATES, "2"));
        assertEquals("reachai.agent-skill-bundle-discovery.v1", discovery.schema());
        assertEquals(AgentSkillPackageInspector.sha256(archive), discovery.bundleSourceSha256());
        assertTrue(discovery.multiSkill(), "The external probe must exercise a multi-Skill bundle");
        assertTrue(discovery.candidateCount() >= minimumCandidates,
                () -> "Expected at least " + minimumCandidates + " candidates but found "
                        + discovery.candidateCount());

        List<AgentSkillPackageInspector.BundleCandidate> selectable = discovery.candidates().stream()
                .filter(AgentSkillPackageInspector.BundleCandidate::selectable)
                .toList();
        assertFalse(selectable.isEmpty(), "The external bundle has no selectable Skill candidate");
        AgentSkillPackageInspector.BundleCandidate candidate = selectCandidate(selectable);

        AgentSkillPackageInspector.SelectedPackage first =
                inspector.selectFromBundle(archive, candidate.sourceRoot());
        AgentSkillPackageInspector.SelectedPackage second =
                inspector.selectFromBundle(archive, candidate.sourceRoot());

        assertArrayEquals(first.archive(), second.archive(),
                "Selecting the same external candidate must create a deterministic artifact");
        assertEquals(discovery.bundleSourceSha256(), first.bundleSourceSha256());
        assertEquals(candidate.name(), first.inspection().name());
        assertEquals("", first.inspection().sourceRoot());
        assertEquals(candidate.selectedSourceSha256(), first.inspection().sourceSha256());
        assertEquals(candidate.contentTreeSha256(), first.inspection().contentTreeSha256());
        assertEquals(candidate.fileCount(), first.inspection().files().size());
        assertTrue(first.inspection().files().stream()
                .anyMatch(file -> "SKILL.md".equals(file.path())));
        assertFalse(first.inspection().files().stream()
                        .anyMatch(file -> file.path().startsWith(candidate.sourceRoot() + "/")),
                "The selected artifact must be rewritten to a portable root layout");
    }

    private AgentSkillPackageInspector.BundleCandidate selectCandidate(
            List<AgentSkillPackageInspector.BundleCandidate> candidates) {
        String expectedName = System.getProperty(EXPECTED_CANDIDATE, "").trim();
        if (expectedName.isEmpty()) {
            return candidates.get(0);
        }
        return candidates.stream()
                .filter(candidate -> expectedName.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected selectable external Skill candidate was not found: " + expectedName));
    }
}
