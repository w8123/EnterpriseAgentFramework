package com.enterprise.ai.runtime.supervisor;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionView;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import java.lang.reflect.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ApprovalResumeAgentIdentityTest {
    private final RuntimeAgentExecutionService service = new RuntimeAgentExecutionService(null,null,null,null,null,null);
    private final RuntimeAgentExecutionView pinned = new RuntimeAgentExecutionView("id-1",7L,"mall","mall-agent","test",null,null,null,true,7L,null,null);
    private final WorkflowExecutionIdentity embed = WorkflowExecutionIdentity.fromEmbedSession("default",7L,"mall","user-1");
    private String resolve(Map<String,Object> saved, RuntimeAgentExecutionView agent, String id, WorkflowExecutionIdentity identity) throws Throwable {
        Method m = RuntimeAgentExecutionService.class.getDeclaredMethod("resumedAgentId",Map.class,RuntimeAgentExecutionView.class,String.class,WorkflowExecutionIdentity.class);
        m.setAccessible(true);
        try { return (String)m.invoke(service,saved,agent,id,identity); } catch (InvocationTargetException e) { throw e.getCause(); }
    }
    @Test void keepsEmbedAliasOrInternalIdWhenTheyMatchPinnedAgent() throws Throwable {
        assertEquals("mall-agent",resolve(Map.of("agentId","mall-agent"),pinned,"id-1",embed));
        assertEquals("id-1",resolve(Map.of("agentId","id-1"),pinned,"id-1",embed));
    }
    @Test void rejectsMissingForgedOtherAgentAndProject() {
        assertThrows(IllegalArgumentException.class, () -> resolve(Map.of(),pinned,"id-1",embed));
        assertThrows(IllegalArgumentException.class, () -> resolve(Map.of("agentId","other-agent"),pinned,"id-1",embed));
        assertThrows(IllegalArgumentException.class, () -> resolve(Map.of("agentId","mall-agent"),pinned,"other-id",embed));
        assertThrows(IllegalArgumentException.class, () -> resolve(Map.of("agentId","mall-agent"),pinned,"id-1",WorkflowExecutionIdentity.fromEmbedSession("default",8L,"other","user-1")));
    }
    @Test void nonEmbedRemainsPinnedToInternalId() throws Throwable {
        assertEquals("id-1",resolve(Map.of("agentId","other-agent"),pinned,"id-1",WorkflowExecutionIdentity.fromAgent("default",7L,"mall","user-1")));
        assertEquals("id-1",resolve(Map.of(),pinned,"id-1",null));
    }
}
