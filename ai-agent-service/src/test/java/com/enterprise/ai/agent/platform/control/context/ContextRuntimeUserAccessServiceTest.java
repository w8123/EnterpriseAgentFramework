package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.agent.platform.control.auth.PlatformPrincipal;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextRuntimeUserAccessServiceTest {

    private final ContextRuntimeUserMappingMapper mappingMapper = mock(ContextRuntimeUserMappingMapper.class);
    private final ContextRuntimeUserAccessService service = new ContextRuntimeUserAccessService(mappingMapper);

    @Test
    void rejectsPrincipalWithoutRuntimeUserReviewPermissionBeforeMappingLookup() {
        PlatformPrincipal principal = new PlatformPrincipal(
                100L, "alice", "Alice", Set.of("OPERATOR"), Set.of("platform:write"));

        boolean allowed = service.canReviewRuntimeUser(principal, "default", "runtime-user-a", null, null);

        assertFalse(allowed);
        verify(mappingMapper, never()).selectCount(any(Wrapper.class));
    }

    @Test
    void rejectsReviewerWithoutActiveMapping() {
        PlatformPrincipal principal = new PlatformPrincipal(
                100L, "alice", "Alice", Set.of("CONTEXT_OPERATOR"),
                Set.of(ContextRuntimeUserAccessService.REVIEW_PERMISSION));
        when(mappingMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        boolean allowed = service.canReviewRuntimeUser(principal, "default", "runtime-user-a", null, null);

        assertFalse(allowed);
        verify(mappingMapper).selectCount(any(Wrapper.class));
    }

    @Test
    void allowsReviewerWithPermissionAndActiveMapping() {
        PlatformPrincipal principal = new PlatformPrincipal(
                100L, "alice", "Alice", Set.of("CONTEXT_OPERATOR"),
                Set.of(ContextRuntimeUserAccessService.REVIEW_PERMISSION));
        when(mappingMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        boolean allowed = service.canReviewRuntimeUser(principal, "default", "runtime-user-a", "demo-project", null);

        assertTrue(allowed);
        verify(mappingMapper).selectCount(any(Wrapper.class));
    }
}
