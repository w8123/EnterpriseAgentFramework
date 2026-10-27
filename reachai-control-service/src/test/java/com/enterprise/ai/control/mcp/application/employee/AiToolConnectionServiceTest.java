package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.mcp.application.port.AiToolConnectionRepository;
import com.enterprise.ai.control.mcp.application.port.McpEmployeeAccessRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnectionStatus;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiToolConnectionServiceTest {

    private final FakeConnections connections = new FakeConnections();
    private final FakeAccess access = new FakeAccess();
    private final McpPublicationRepository publications = mock(McpPublicationRepository.class);
    private final BusinessUserSyncService sync = mock(BusinessUserSyncService.class);
    private final McpHubProperties properties = new McpHubProperties();
    private AiToolConnectionService service;

    private final EmployeeOwner zhangSan = new EmployeeOwner(
            "default", 7L, "yudao-erp", "yudao-erp", "1", "张三", List.of("erp_user"));

    @BeforeEach
    void setUp() {
        service = new AiToolConnectionService(connections, access, publications, sync, properties);
        access.save(new McpEmployeeAccess(null, 11L, "yudao-erp", "芋道 ERP", true));
        access.save(new McpEmployeeAccess(null, 12L, "crm", "客户管理", true));
        access.save(new McpEmployeeAccess(null, 13L, "draft-sys", "未发布系统", true));
        published(11L, 3);
        published(12L, 2);
        when(publications.findById(13L)).thenReturn(Optional.of(new McpPublication(
                13L, "draft_sys", "", McpPublicationStatus.DRAFT, null, null, null)));
        when(sync.activeAppIds(any(), anyLong())).thenReturn(Set.of("yudao-erp"));
    }

    @Test
    void offersOnlyPublishedSystemsTheEmployeeHasAnAccountIn() {
        var options = service.options(zhangSan);

        assertEquals(1, options.size());
        assertEquals("yudao-erp", options.get(0).projectCode());
        assertEquals("芋道 ERP", options.get(0).name());
        assertEquals(3, options.get(0).toolCount());
        assertTrue(options.get(0).current());
    }

    @Test
    void anotherSystemAppearsOnceTheEmployeeIsBoundThere() {
        when(sync.activeAppIds(any(), anyLong())).thenReturn(Set.of("yudao-erp", "crm"));

        var codes = service.options(zhangSan).stream().map(AiToolConnectionService.SystemOption::projectCode).toList();

        assertEquals(List.of("yudao-erp", "crm"), codes);
    }

    @Test
    void createStoresOnlyTheKeyHashAndReturnsTheSecretOnce() {
        var created = service.create(zhangSan, "claude_code", List.of("yudao-erp"));

        assertTrue(created.secret().startsWith("rai_"));
        assertEquals("CLAUDE_CODE", created.connection().toolKind());
        assertEquals("ACTIVE", created.connection().state());
        assertEquals("芋道 ERP", created.connection().systems().get(0).name());
        String storedHash = connections.hashes.get(created.connection().id());
        assertEquals(AiToolConnectionService.sha256Hex(created.secret()), storedHash);
        assertNotEquals(created.secret(), storedHash);
        assertTrue(created.secret().startsWith(created.connection().keyPrefix()));
        AiToolConnection stored = connections.rows.get(created.connection().id());
        assertEquals(7L, stored.businessUserId());
        assertEquals("1", stored.externalUserId());
        assertTrue(stored.expiresAt().isAfter(LocalDateTime.now().plusDays(89)));
    }

    @Test
    void systemsTheEmployeeCannotUseAreRejected() {
        McpDomainException notBound = assertThrows(McpDomainException.class,
                () -> service.create(zhangSan, "CODEX", List.of("crm")));
        assertEquals("AI_TOOL_CONNECTION_SYSTEM_NOT_AVAILABLE", notBound.code());

        McpDomainException unpublished = assertThrows(McpDomainException.class,
                () -> service.create(zhangSan, "CODEX", List.of("draft-sys")));
        assertEquals("AI_TOOL_CONNECTION_SYSTEM_NOT_AVAILABLE", unpublished.code());

        McpDomainException none = assertThrows(McpDomainException.class,
                () -> service.create(zhangSan, "CODEX", List.of()));
        assertEquals("AI_TOOL_CONNECTION_NO_SYSTEM", none.code());
        assertTrue(connections.rows.isEmpty());
    }

    @Test
    void activeConnectionsAreCapped() {
        properties.setMaxEmployeeConnections(1);
        service.create(zhangSan, "CODEX", List.of("yudao-erp"));

        McpDomainException limit = assertThrows(McpDomainException.class,
                () -> service.create(zhangSan, "CODEX", List.of("yudao-erp")));
        assertEquals("AI_TOOL_CONNECTION_LIMIT", limit.code());
    }

    @Test
    void onlyTheOwnerCanDisconnect() {
        var created = service.create(zhangSan, "CODEX", List.of("yudao-erp"));
        EmployeeOwner liSi = new EmployeeOwner("default", 8L, "yudao-erp", "yudao-erp", "2", "李四", List.of());

        McpDomainException notFound = assertThrows(McpDomainException.class,
                () -> service.revoke(liSi, created.connection().id()));
        assertEquals("AI_TOOL_CONNECTION_NOT_FOUND", notFound.code());

        var revoked = service.revoke(zhangSan, created.connection().id());
        assertEquals("REVOKED", revoked.state());
        assertFalse(service.list(zhangSan).isEmpty());
        assertTrue(service.list(liSi).isEmpty());
    }

    private void published(long publicationId, int toolCount) {
        when(publications.findById(publicationId)).thenReturn(Optional.of(new McpPublication(
                publicationId, "pub" + publicationId, "", McpPublicationStatus.PUBLISHED,
                publicationId * 100, null, null)));
        List<McpToolProjection> tools = new ArrayList<>();
        for (int i = 0; i < toolCount; i++) {
            tools.add(new McpToolProjection("tool" + i, "d", "{}", McpPublicationItemKind.CAPABILITY,
                    "ref" + i, null, "READ", null));
        }
        when(publications.findRevision(eq(publicationId), eq(publicationId * 100))).thenReturn(Optional.of(
                new McpPublicationRevision(publicationId * 100, publicationId, 1, tools, null, LocalDateTime.now())));
    }

    static final class FakeConnections implements AiToolConnectionRepository {
        final Map<Long, AiToolConnection> rows = new HashMap<>();
        final Map<Long, String> hashes = new HashMap<>();
        private long nextId = 1;

        @Override
        public AiToolConnection create(AiToolConnection c, String keyHash) {
            long id = nextId++;
            AiToolConnection saved = new AiToolConnection(id, c.tenantId(), c.businessUserId(), c.appId(),
                    c.externalUserId(), c.userName(), c.toolKind(), c.keyPrefix(), c.state(), c.expiresAt(),
                    null, c.createdAt(), c.systems());
            rows.put(id, saved);
            hashes.put(id, keyHash);
            return saved;
        }

        @Override
        public Optional<AiToolConnection> findById(long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<AiToolConnection> findByKeyHash(String keyHash) {
            return hashes.entrySet().stream().filter(e -> e.getValue().equals(keyHash))
                    .map(e -> rows.get(e.getKey())).findFirst();
        }

        @Override
        public List<AiToolConnection> findByOwner(String tenantId, long businessUserId) {
            return rows.values().stream().filter(c -> c.ownedBy(tenantId, businessUserId)).toList();
        }

        @Override
        public long countActiveByOwner(String tenantId, long businessUserId, LocalDateTime now) {
            return findByOwner(tenantId, businessUserId).stream().filter(c -> c.usable(now)).count();
        }

        @Override
        public void revoke(long id, LocalDateTime now) {
            state(id, AiToolConnectionStatus.REVOKED);
        }

        @Override
        public void expire(long id, LocalDateTime now) {
            state(id, AiToolConnectionStatus.EXPIRED);
        }

        @Override
        public void touchLastUsed(long id, LocalDateTime now) {
        }

        @Override
        public SearchPage search(SearchQuery query, LocalDateTime now) {
            List<AiToolConnection> matched = rows.values().stream()
                    .filter(c -> query.projectCode() == null || c.systems().stream()
                            .anyMatch(system -> system.projectCode().equals(query.projectCode())))
                    .filter(c -> query.state() == null || effectiveState(c, now).equals(query.state()))
                    .filter(c -> query.keyword() == null || (c.userName() != null && c.userName().contains(query.keyword()))
                            || c.externalUserId().equals(query.keyword()))
                    .sorted((a, b) -> Long.compare(b.id(), a.id()))
                    .toList();
            return new SearchPage(matched.stream().skip(query.offset()).limit(query.limit()).toList(), matched.size());
        }

        private static String effectiveState(AiToolConnection c, LocalDateTime now) {
            return c.state() == AiToolConnectionStatus.ACTIVE && c.expired(now)
                    ? AiToolConnectionStatus.EXPIRED.name() : c.state().name();
        }

        private void state(long id, AiToolConnectionStatus next) {
            AiToolConnection c = rows.get(id);
            rows.put(id, new AiToolConnection(c.id(), c.tenantId(), c.businessUserId(), c.appId(),
                    c.externalUserId(), c.userName(), c.toolKind(), c.keyPrefix(), next, c.expiresAt(),
                    c.lastUsedAt(), c.createdAt(), c.systems()));
        }
    }

    static final class FakeAccess implements McpEmployeeAccessRepository {
        final List<McpEmployeeAccess> rows = new ArrayList<>();

        @Override
        public List<McpEmployeeAccess> findEnabled() {
            return rows.stream().filter(McpEmployeeAccess::enabled).toList();
        }

        @Override
        public Optional<McpEmployeeAccess> findByProjectCode(String projectCode) {
            return rows.stream().filter(a -> a.projectCode().equals(projectCode)).findFirst();
        }

        @Override
        public Optional<McpEmployeeAccess> findByPublicationId(long publicationId) {
            return rows.stream().filter(a -> a.publicationId() == publicationId).findFirst();
        }

        @Override
        public McpEmployeeAccess save(McpEmployeeAccess access) {
            McpEmployeeAccess saved = new McpEmployeeAccess((long) rows.size() + 1, access.publicationId(),
                    access.projectId(), access.projectCode(), access.displayName(), access.enabled(),
                    access.writeApprovalRequired());
            rows.add(saved);
            return saved;
        }
    }
}
