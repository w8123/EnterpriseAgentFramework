package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.port.McpPlatformMemoryPort;
import com.enterprise.ai.control.memory.PersonalMemoryController;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver;
import com.enterprise.ai.control.memory.PersonalMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MemoryMcpPlatformAdapterTest {
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void aSavedMemoryIsRecordedAsAiToolWithTheIdempotencyKey() {
        var service = mock(PersonalMemoryService.class);
        var resolver = mock(PersonalMemoryIdentityResolver.class);
        var adapter = new MemoryMcpPlatformAdapter(mock(PersonalMemoryController.class), service, resolver,
                new PlatformDelegatedRequestScope(), new ObjectMapper());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/mcp/platform")));
        var actor = new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"), "s", LocalDateTime.now().plusHours(1),
                List.of(), List.of(), List.of());
        var args = new LinkedHashMap<String, Object>();
        args.put("content", "查库存默认看 1 号仓"); args.put("clientRequestId", "platform-ai:3:inv"); args.put("tags", List.of("库存"));

        adapter.call(actor, McpPlatformMemoryPort.Operation.REMEMBER, null, args);

        ArgumentCaptor<PersonalMemoryService.RememberCommand> command = ArgumentCaptor.forClass(PersonalMemoryService.RememberCommand.class);
        verify(service).remember(any(), command.capture());
        assertEquals(PersonalMemoryService.SOURCE_AI_TOOL, command.getValue().source());
        assertEquals("platform-ai:3:inv", command.getValue().clientRequestId());
        assertEquals(List.of("库存"), command.getValue().tags());
        verify(resolver).resolve(any(), eq(null));
    }
}
