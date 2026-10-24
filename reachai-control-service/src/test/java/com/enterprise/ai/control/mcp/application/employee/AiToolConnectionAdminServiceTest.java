package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection.ConnectedSystem;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnectionStatus;
import com.enterprise.ai.control.mcp.domain.employee.AiToolKind;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiToolConnectionAdminServiceTest {

    private final AiToolConnectionServiceTest.FakeConnections connections = new AiToolConnectionServiceTest.FakeConnections();
    private final AiToolConnectionServiceTest.FakeAccess access = new AiToolConnectionServiceTest.FakeAccess();
    private final AiToolConnectionAdminService service = new AiToolConnectionAdminService(connections, access);

    @BeforeEach
    void setUp() {
        access.save(new McpEmployeeAccess(null, 11L, "yudao-erp", "芋道 ERP", true));
    }

    @Test
    void listsEveryEmployeeWithSystemNamesAndEffectiveState() {
        connect(7L, "1", "张三", LocalDateTime.now().plusDays(1));
        connect(8L, "2", "李四", LocalDateTime.now().minusMinutes(1));

        var active = service.list("yudao-erp", "ACTIVE", null, 20, 0);
        assertEquals(1, active.total());
        assertEquals("张三", active.items().get(0).userName());
        assertEquals("芋道 ERP", active.items().get(0).systems().get(0).name());

        var expired = service.list(null, "EXPIRED", null, 20, 0);
        assertEquals("李四", expired.items().get(0).userName());
        assertEquals("EXPIRED", expired.items().get(0).state());

        assertEquals(1, service.list(null, null, "李", 20, 0).total());
    }

    @Test
    void adminCanDisconnectAnyEmployeesConnection() {
        long id = connect(7L, "1", "张三", LocalDateTime.now().plusDays(1)).id();

        var revoked = service.revoke(id);

        assertEquals("REVOKED", revoked.state());
        assertEquals(AiToolConnectionStatus.REVOKED, connections.rows.get(id).state());
        assertEquals("AI_TOOL_CONNECTION_NOT_FOUND",
                assertThrows(McpDomainException.class, () -> service.revoke(99L)).code());
    }

    private AiToolConnection connect(long businessUserId, String externalUserId, String name, LocalDateTime expiresAt) {
        return connections.create(new AiToolConnection(null, "default", businessUserId, "yudao-erp", externalUserId,
                name, AiToolKind.CODEX, "rai_12345678", AiToolConnectionStatus.ACTIVE, expiresAt, null,
                LocalDateTime.now(), List.of(new ConnectedSystem("yudao-erp", 11L))), "hash-" + businessUserId);
    }
}
