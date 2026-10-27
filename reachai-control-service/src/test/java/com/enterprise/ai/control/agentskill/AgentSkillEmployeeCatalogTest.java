package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillDetail;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSkillEmployeeCatalogTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 26, 9, 0);
    private final AgentSkillCatalogService catalogService = mock(AgentSkillCatalogService.class);
    private final AgentSkillEmployeeCatalog catalog = new AgentSkillEmployeeCatalog(catalogService);
    private final Map<String, String> systems = Map.of("yudao-erp", "芋道 ERP");

    @Test
    void onlyPublishedProjectSkillsOfConnectableSystemsAreListed() {
        when(catalogService.list(null, "PUBLISHED")).thenReturn(List.of(
                skill(1L, "PROJECT", "yudao-erp", 11L),
                skill(2L, "PROJECT", "crm", 12L),
                skill(3L, "PUBLIC", null, 13L),
                skill(4L, "PROJECT", "yudao-erp", null)));
        when(catalogService.version(1L, 11L)).thenReturn(version(11L, "1.0.1", "PUBLISHED"));

        var listed = catalog.list(systems);

        assertEquals(1, listed.size());
        assertEquals("芋道 ERP", listed.get(0).systemName());
        assertEquals("1.0.1", listed.get(0).version());
    }

    @Test
    void packageServesNewestPublishedVersionAndRefusesRevoked() {
        SkillSummary skill = skill(1L, "PROJECT", "yudao-erp", 11L);
        when(catalogService.detail(1L)).thenReturn(new SkillDetail(skill, List.of()));
        when(catalogService.version(1L, 11L)).thenReturn(version(11L, "1.0.1", "PUBLISHED"));
        when(catalogService.packageBytes(1L, 11L)).thenReturn(new byte[]{7});

        var pkg = catalog.packageFor(1L, systems);
        assertEquals("skill-1-1.0.1.zip", pkg.filename());
        assertArrayEquals(new byte[]{7}, pkg.body());

        when(catalogService.version(1L, 11L)).thenReturn(version(11L, "1.0.1", "REVOKED"));
        assertThrows(AgentSkillException.class, () -> catalog.packageFor(1L, systems));
        assertThrows(AgentSkillException.class, () -> catalog.packageFor(1L, Map.of("crm", "CRM")));
    }

    private static SkillSummary skill(Long id, String visibility, String projectCode, Long latestVersionId) {
        return new SkillSummary(id, "yudao-erp", "skill-" + id, "做法 " + id, "d", visibility, null, projectCode,
                "ACTIVE", latestVersionId, latestVersionId, 1, T0, null, null, null);
    }

    private static VersionView version(Long id, String version, String status) {
        return new VersionView(id, 1L, version, status, "RUN_CAPTURE", null, "sha", "tree", 1L, null, null, false,
                null, null, null, null, null, null, null, null, null, T0, T0);
    }
}
