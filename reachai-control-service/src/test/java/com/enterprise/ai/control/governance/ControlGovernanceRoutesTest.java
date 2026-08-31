package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.mcp.api.management.McpCallLogController;
import com.enterprise.ai.control.mcp.api.management.McpClientController;
import com.enterprise.ai.control.mcp.api.management.McpHubManagementAccess;
import com.enterprise.ai.control.mcp.api.management.McpHubOverviewController;
import com.enterprise.ai.control.mcp.api.management.McpPublicationController;
import com.enterprise.ai.control.mcp.application.calllog.McpCallLogReader;
import com.enterprise.ai.control.mcp.application.identity.McpClientApplicationService;
import com.enterprise.ai.control.mcp.application.overview.McpHubOverviewReader;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.application.publication.McpPublicationApplicationService;
import com.enterprise.ai.control.mcp.domain.client.McpClient;
import com.enterprise.ai.control.mcp.domain.client.McpClientStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlGovernanceRoutesTest {

    @Test
    void listsToolAclRulesWithoutOptionalTargetKind() {
        ControlToolAclMapper mapper = mock(ControlToolAclMapper.class);
        ControlToolAclController controller = new ControlToolAclController(
                mapper, new ControlToolAclDecisionService(mapper));
        Page<ControlToolAclEntity> page = new Page<>(1, 20);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        ResponseEntity<Page<ControlToolAclEntity>> response = controller.page(1, 20, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(page, response.getBody());
        verify(mapper).selectPage(any(), any());
    }

    @Test
    void managesToolAclRoutesWithoutFallingThroughToRetiredProxy() {
        ControlToolAclMapper mapper = mock(ControlToolAclMapper.class);
        ControlToolAclController controller = new ControlToolAclController(
                mapper, new ControlToolAclDecisionService(mapper));
        ControlToolAclEntity rule = new ControlToolAclEntity();
        rule.setId(1L);
        rule.setRoleCode("admin");
        rule.setTargetKind("ALL");
        rule.setTargetName("*");
        rule.setPermission("ALLOW");
        rule.setEnabled(true);
        Page<ControlToolAclEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(rule));
        page.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(page);
        when(mapper.selectList(any())).thenReturn(List.of(rule));
        when(mapper.selectById(1L)).thenReturn(rule);
        when(mapper.selectOne(any())).thenReturn(null);

        ResponseEntity<Page<ControlToolAclEntity>> listed = controller.page(1, 20, "admin", "ALL");
        ResponseEntity<List<String>> roles = controller.roles();
        ResponseEntity<ControlToolAclEntity> created = controller.create(rule);
        ResponseEntity<ControlToolAclEntity> toggled = controller.toggle(1L, new ControlToolAclController.ToggleRequest(false));
        ResponseEntity<Map<String, Object>> batch = controller.grantBatch(new ControlToolAclController.GrantBatchRequest(
                "ops",
                "ALLOW",
                List.of(new ControlToolAclController.ToolAclTargetRef("TOOL", "orders.search")),
                "unit"));
        ResponseEntity<Map<String, String>> explain = controller.explain(new ControlToolAclController.ExplainRequest(
                List.of("admin"),
                List.of(new ControlToolAclController.ToolAclTargetRef("TOOL", "orders.search"))));
        ResponseEntity<Map<String, Object>> deleted = controller.delete(1L);

        assertEquals(HttpStatus.OK, listed.getStatusCode());
        assertEquals(1, listed.getBody().getRecords().size());
        assertEquals(List.of("admin"), roles.getBody());
        assertEquals("admin", created.getBody().getRoleCode());
        assertEquals(false, toggled.getBody().getEnabled());
        assertEquals(1, batch.getBody().get("count"));
        assertEquals("ALLOW", explain.getBody().get("orders.search"));
        assertEquals(true, deleted.getBody().get("ok"));
        verify(mapper, times(2)).insert(any());
        verify(mapper).updateById(rule);
        verify(mapper).deleteById(1L);
    }

    @Test
    void managesMcpHubRoutesThroughPublicationScopedControllers() {
        assertEquals("mcp-hub:credential:manage", McpHubManagementAccess.MANAGE_CREDENTIALS);
        McpPublicationApplicationService publicationService = mock(McpPublicationApplicationService.class);
        McpClientApplicationService clientService = mock(McpClientApplicationService.class);
        McpHubOverviewReader overviewReader = mock(McpHubOverviewReader.class);
        McpCallLogReader callLogReader = mock(McpCallLogReader.class);
        PlatformAuthAuditService auditService = mock(PlatformAuthAuditService.class);
        McpHubManagementAccess access = new McpHubManagementAccess();

        McpPublicationController publications = new McpPublicationController(
                publicationService, access, auditService);
        McpClientController clients = new McpClientController(clientService, access, auditService);
        McpHubOverviewController overview = new McpHubOverviewController(overviewReader, access);
        McpCallLogController callLogs = new McpCallLogController(callLogReader, access, auditService);

        HttpServletRequest request = authorizedRequest(List.of(
                McpHubManagementAccess.READ,
                McpHubManagementAccess.MANAGE_PUBLICATIONS,
                McpHubManagementAccess.MANAGE_IRREVERSIBLE,
                McpHubManagementAccess.MANAGE_CREDENTIALS,
                McpHubManagementAccess.MANAGE_CREDENTIAL_ROLES,
                McpHubManagementAccess.READ_PAYLOAD));

        McpPublication draft = new McpPublication(1L, "orders-pub", "outbound tools",
                McpPublicationStatus.DRAFT, null, null, null);
        when(publicationService.list(null, null, null, null))
                .thenReturn(new McpPublicationRepository.Page(List.of(draft), 1));
        when(publicationService.create("orders-pub", "outbound tools")).thenReturn(draft);
        McpPublicationApplicationService.DetailView published = new McpPublicationApplicationService.DetailView(
                new McpPublicationApplicationService.PublicationView(
                        1L, "orders-pub", "outbound tools", "PUBLISHED", 5L, null, LocalDateTime.now()),
                List.of(), List.of(), List.of(), List.of("orders.search"));
        when(publicationService.publish(1L, true)).thenReturn(published);

        McpPublicationRepository.Page listed = publications.list(request, null, null, null, null);
        assertEquals(1, listed.total());
        McpPublicationApplicationService.PublicationView created = publications.create(
                request, new McpPublicationController.CreateRequest("orders-pub", "outbound tools"));
        assertEquals("DRAFT", created.state());
        McpPublicationApplicationService.DetailView result = publications.publish(
                request, 1L, new McpPublicationController.PublishRequest(true));
        assertEquals("PUBLISHED", result.publication().state());
        assertEquals(5L, result.publication().currentRevisionId());
        verify(auditService, times(2)).record(any(), any(), any(), any(), any());

        McpClient newClient = new McpClient(2L, 1L, "Cursor",
                7L, "orders", "test", "tenant-a",
                "mcp_abcdef0123456789", "a2f9d4c8", List.of("ops"), List.of("orders.search"),
                McpClientStatus.ACTIVE, true, null, null, null, null);
        McpClientApplicationService.CreatedClient createdClient =
                new McpClientApplicationService.CreatedClient(newClient, "mcp_plaintext_key");
        when(clientService.create(1L, "Cursor", 7L, "orders", "test", "tenant-a",
                List.of("ops"), List.of("orders.search"), null))
                .thenReturn(createdClient);
        when(clientService.rotate(1L, 2L)).thenReturn(createdClient);
        when(clientService.revoke(1L, 2L)).thenReturn(newClient);
        when(clientService.update(1L, 2L, List.of("ops"), List.of("orders.search"), true, null))
                .thenReturn(newClient);

        Map<String, Object> keyView = clients.create(request, 1L,
                new McpClientController.CreateClientRequest("Cursor", 7L, "orders", "test", "tenant-a",
                        List.of("ops"),
                        List.of("orders.search"), null));
        assertNotNull(keyView.get("plaintextApiKey"));
        Map<String, Object> rotated = clients.rotate(request, 1L, 2L);
        assertNotNull(rotated.get("plaintextApiKey"));
        Map<String, Object> revoked = clients.revoke(request, 1L, 2L);
        assertEquals("ACTIVE", revoked.get("state"));
        Map<String, Object> updated = clients.update(request, 1L, 2L,
                new McpClientController.UpdateClientRequest(List.of("ops"), List.of("orders.search"),
                        true, null));
        assertEquals(true, updated.get("enabled"));
        verify(auditService, times(6)).record(any(), any(), any(), any(), any());

        McpHubOverviewReader.McpHubOverviewView overviewView =
                new McpHubOverviewReader.McpHubOverviewView(
                        3, 2, 4, 1, 120, 0.98, 250L, 30);
        when(overviewReader.read(7)).thenReturn(overviewView);
        assertEquals(overviewView, overview.overview(request, null));

        McpCallLogReader.Entry entry = new McpCallLogReader.Entry(
                9L, "OUTBOUND", 1L, 2L, "Cursor", "tools/call", "orders.search",
                7L, "orders", "test", "tenant-a",
                true, 42L, null, "{\"arguments\":{}}", "{\"result\":{}}", null,
                "trace-1", "run-1", "127.0.0.1", LocalDateTime.now());
        when(callLogReader.page(null, null, null, null, null, null, null, null, 50, 0))
                .thenReturn(new McpCallLogReader.Page(List.of(entry), 1, 50, 0));
        when(callLogReader.detail(9L)).thenReturn(entry);

        McpCallLogReader.Page page = callLogs.list(request, null, null, null, null,
                null, null, null, null, null, null);
        assertEquals(1, page.total());
        assertNull(page.items().get(0).requestBody());
        assertNull(page.items().get(0).responseBody());
        McpCallLogReader.Entry detail = callLogs.detail(request, 9L);
        assertEquals("{\"arguments\":{}}", detail.requestBody());
        verify(auditService).record(any(), eq("MCP_CALL_PAYLOAD_READ"), eq("MCP_CALL_LOG"),
                eq("9"), any());
    }

    @Test
    void rejectsMcpHubAccessWithoutPlatformPermissions() {
        McpHubManagementAccess access = new McpHubManagementAccess();

        ResponseStatusException unauthenticated = assertThrows(ResponseStatusException.class,
                () -> access.require(new MockHttpServletRequest(), McpHubManagementAccess.READ));
        assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.getStatusCode());

        HttpServletRequest readerOnly = authorizedRequest(List.of(McpHubManagementAccess.READ));
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> access.require(readerOnly, McpHubManagementAccess.MANAGE_PUBLICATIONS));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        McpPublicationController publications = new McpPublicationController(
                mock(McpPublicationApplicationService.class), access, mock(PlatformAuthAuditService.class));
        HttpServletRequest publicationManager = authorizedRequest(List.of(McpHubManagementAccess.MANAGE_PUBLICATIONS));
        ResponseStatusException irreversibleForbidden = assertThrows(ResponseStatusException.class,
                () -> publications.publish(publicationManager, 1L,
                        new McpPublicationController.PublishRequest(true)));
        assertEquals(HttpStatus.FORBIDDEN, irreversibleForbidden.getStatusCode());
    }

    private HttpServletRequest authorizedRequest(List<String> permissions) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        user.setUsername("admin");
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(
                user, "session-1", LocalDateTime.now().plusHours(1),
                List.of("admin"), permissions, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session);
        return request;
    }
}
