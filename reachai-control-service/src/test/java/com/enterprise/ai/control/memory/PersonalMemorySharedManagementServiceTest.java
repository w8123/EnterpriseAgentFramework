package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.memory.PersonalMemoryIdentityLinkLookup.LinkedOwner;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonalMemorySharedManagementServiceTest {
    @Test void removedLinkDuringListDoesNotReturnPreviouslyReadMemory() {
        var owner = PersonalMemoryPrincipal.business("tenant", "alice", "erp");
        var peer = PersonalMemoryPrincipal.business("tenant", "bob", "crm");
        var links = mock(PersonalMemoryIdentityLinkLookup.class);
        var memory = mock(PersonalMemoryService.class);
        when(links.linkedOwners(owner)).thenReturn(List.of(new LinkedOwner(9, 12, peer)), List.of());
        when(memory.listPersonalForSharing(eq(peer), isNull(), eq(50), eq(0)))
                .thenReturn(new PersonalMemoryService.MemoryPage(List.of(), 0, 50, 0));
        var service = new PersonalMemorySharedManagementService(links, memory);
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.list(owner, 9, null, 50, 0)).getStatusCode().value());
    }
}
