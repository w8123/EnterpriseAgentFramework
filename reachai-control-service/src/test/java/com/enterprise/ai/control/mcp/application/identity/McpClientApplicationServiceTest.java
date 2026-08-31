package com.enterprise.ai.control.mcp.application.identity;

import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.mcp.application.port.McpClientRepository;
import com.enterprise.ai.control.mcp.application.publication.McpPublicationApplicationService;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.client.McpClient;
import com.enterprise.ai.control.mcp.domain.client.McpClientStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpClientApplicationServiceTest {

    private McpClientRepository repository;
    private McpPublicationApplicationService publicationService;
    private McpClientApplicationService service;
    private ControlToolAclDecisionService aclDecisionService;

    @BeforeEach
    void setUp() {
        repository = mock(McpClientRepository.class);
        publicationService = mock(McpPublicationApplicationService.class);
        aclDecisionService = mock(ControlToolAclDecisionService.class);
        when(aclDecisionService.knownRoleCodes()).thenReturn(Set.of("ops"));
        service = new McpClientApplicationService(repository, publicationService, aclDecisionService);
    }

    @Test
    void putWithNullExpiryClearsTheExistingExpiry() {
        LocalDateTime existingExpiry = LocalDateTime.now().plusDays(7);
        McpClient current = new McpClient(
                2L, 1L, "Cursor", 8L, "orders", "test", "tenant-a",
                "mcp_123456789012", "hash",
                List.of("ops"), List.of("lookup"), McpClientStatus.ACTIVE,
                true, existingExpiry, null, null, null);
        when(repository.findById(2L)).thenReturn(Optional.of(current));
        when(publicationService.availableToolNames(1L)).thenReturn(Set.of("lookup"));
        when(repository.save(any(McpClient.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        McpClient updated = service.update(
                1L, 2L, List.of("ops"), List.of("lookup"), true, null);

        assertNull(updated.expiresAt());
    }

    @Test
    void terminalCredentialCannotBeUpdatedOrReenabled() {
        McpClient revoked = new McpClient(
                2L, 1L, "Cursor", 8L, "orders", "test", "tenant-a",
                "mcp_123456789012", "hash",
                List.of("ops"), List.of("lookup"), McpClientStatus.REVOKED,
                false, null, null, null, null);
        when(repository.findById(2L)).thenReturn(Optional.of(revoked));
        when(publicationService.availableToolNames(1L)).thenReturn(Set.of("lookup"));

        McpDomainException failure = assertThrows(McpDomainException.class,
                () -> service.update(1L, 2L, List.of("ops"), List.of("lookup"), true, null));

        assertEquals("MCP_CLIENT_TERMINAL", failure.code());
    }
}
