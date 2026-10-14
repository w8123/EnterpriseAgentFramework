package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.*;
import com.enterprise.ai.capability.catalog.httpapi.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CapabilityScanRevisionServiceTest {
    @Test
    void catchesSourceAndMembershipChangesEvenWithIdenticalSecondResolutionTimestamps() {
        var observations = mock(ScanProjectToolMapper.class);
        var inventories = mock(HttpApiInventoryStateMapper.class);
        var members = mock(HttpApiInventoryMemberMapper.class);
        var bindings = mock(HttpApiSourceBindingMapper.class);
        var service = new CapabilityScanRevisionService(observations, inventories, members, bindings);
        var time = LocalDateTime.of(2026,10,13,12,0,0);
        var project = new ScanProjectEntity(); project.setId(7L); project.setProjectCode("orders"); project.setUpdateTime(time);
        var source = new ScanProjectToolEntity(); source.setId(11L); source.setProjectId(7L); source.setParametersJson("[]"); source.setUpdateTime(time);
        var binding = new HttpApiSourceBindingEntity(); binding.setId(21L); binding.setAssetId(31L); binding.setProjectId(7L); binding.setSourceContractHash("hash"); binding.setObservedAt(time);
        var member = new HttpApiInventoryMemberEntity(); member.setId(41L); member.setBindingId(21L); member.setInventoryToken("confirmed"); member.setObservedAt(time);
        when(observations.selectList(any())).thenReturn(List.of(source));
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        when(members.selectList(any())).thenReturn(List.of(member));
        String initial = service.revision(project);
        assertEquals(initial, service.revision(project));
        source.setParametersJson("[{\"name\":\"changed\"}]");
        assertNotEquals(initial, service.revision(project));
        source.setParametersJson("[]");
        member.setInventoryToken("unconfirmed");
        assertNotEquals(initial, service.revision(project));
        member.setInventoryToken("confirmed");
        project.setScanPath("different-project-root");
        assertNotEquals(initial, service.revision(project));
    }
}
