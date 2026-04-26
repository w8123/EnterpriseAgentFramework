package com.enterprise.ai.agent.skill.interactive;

import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.interactive.UiSubmitPayload;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.skill.SkillMetadata;
import com.enterprise.ai.skill.SideEffectLevel;
import com.enterprise.ai.skill.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class InteractiveFormSkillExecutorTest {

    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private InteractiveDictLookup dictLookup;
    @Mock
    private SlotExtractionService slotExtractionService;
    @Mock
    private SkillInteractionMapper skillInteractionMapper;

    private ObjectMapper objectMapper;
    private InteractiveFormSkillExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        executor = new InteractiveFormSkillExecutor(
                toolRegistry, dictLookup, slotExtractionService, skillInteractionMapper, objectMapper);
    }

    @Test
    void start_throwsWhenMissingTrace() {
        InteractiveFormSkill skill = dummySkill();
        assertThrows(IllegalStateException.class,
                () -> executor.start(skill, Map.of(), null));
    }

    @Test
    void start_suspendsWithFormWhenMissingSlots() {
        when(skillInteractionMapper.selectCount(any())).thenReturn(0L);
        when(dictLookup.options("SHIFT_TYPE")).thenReturn(List.of(
                FieldOptionSpec.builder().value("NIGHT").label("夜班").build()));

        InteractiveFormSpec spec = InteractiveFormSpec.builder()
                .targetTool("fixture_target_tool")
                .batchSize(2)
                .confirmTitle("确认")
                .successTemplate("ok")
                .fields(List.of(
                        FieldSpec.builder().key("teamName").label("名称").type("text").required(true)
                                .source(FieldSourceSpec.builder().kind("NONE").build()).build(),
                        FieldSpec.builder().key("shiftType").label("班次").type("select").required(true)
                                .source(FieldSourceSpec.builder().kind("DICT").dictCode("SHIFT_TYPE").build()).build()
                ))
                .build();
        InteractiveFormSkill skill = new InteractiveFormSkill(
                "s1", "d", null, List.of(), SkillMetadata.defaultFor(SideEffectLevel.WRITE), spec, executor);

        ToolExecutionContext ctx = ToolExecutionContext.builder()
                .traceId("tr-1")
                .userId("u1")
                .sessionId("ses")
                .build();

        InteractionSuspendedException ex = assertThrows(InteractionSuspendedException.class,
                () -> executor.start(skill, Map.of(), ctx));
        assertNotNull(ex.getPayload());
        assertEquals("form", ex.getPayload().getComponent());
        verify(skillInteractionMapper).insert(any(SkillInteractionEntity.class));
    }

    @Test
    void resume_cancel() throws Exception {
        SkillInteractionEntity row = new SkillInteractionEntity();
        row.setId("ix1");
        row.setTraceId("tr");
        row.setUserId("u1");
        row.setSkillName("s1");
        row.setStatus(SkillInteractionStatus.PENDING);
        row.setExpiresAt(LocalDateTime.now().plusHours(1));
        SlotStateDocument st = SlotStateDocument.builder()
                .phase(SlotStateDocument.PHASE_COLLECT)
                .slots(new java.util.LinkedHashMap<>(Map.of("a", "b")))
                .build();
        row.setSlotState(objectMapper.writeValueAsString(st));
        row.setSpecSnapshot(objectMapper.writeValueAsString(InteractiveFormSpec.builder()
                .targetTool("fixture_target_tool")
                .fields(List.of())
                .build()));

        when(skillInteractionMapper.selectById("ix1")).thenReturn(row);

        AgentResult r = executor.resume("ix1", UiSubmitPayload.builder().action("cancel").build(),
                "u1", List.of(), "ses");
        assertTrue(r.isSuccess());
        assertTrue(r.getAnswer().contains("取消"));
        verify(skillInteractionMapper).updateById(row);
        assertEquals(SkillInteractionStatus.CANCELLED, row.getStatus());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void resume_submit_passesNestedArgsToTargetTool() throws Exception {
        FieldSpec teamName = FieldSpec.builder()
                .key("teamName").label("名称").type("text").required(true)
                .source(FieldSourceSpec.builder().kind("NONE").build()).build();
        FieldSpec deptId = FieldSpec.builder()
                .key("deptId").label("部门").type("text").required(true)
                .source(FieldSourceSpec.builder().kind("NONE").build()).build();
        FieldSpec body = FieldSpec.builder()
                .key("body").label("请求体").type("text").required(false)
                .source(FieldSourceSpec.builder().kind("NONE").build())
                .children(List.of(teamName, deptId))
                .build();

        InteractiveFormSpec spec = InteractiveFormSpec.builder()
                .targetTool("nested_tool")
                .fields(List.of(body))
                .build();

        SkillInteractionEntity row = new SkillInteractionEntity();
        row.setId("ix-nested");
        row.setTraceId("tr");
        row.setUserId("u1");
        row.setSkillName("s1");
        row.setStatus(SkillInteractionStatus.PENDING);
        row.setExpiresAt(LocalDateTime.now().plusHours(1));
        SlotStateDocument st = SlotStateDocument.builder()
                .phase(SlotStateDocument.PHASE_CONFIRM)
                .slots(new LinkedHashMap<>(Map.of(
                        "teamName", "A队",
                        "deptId", "d99"
                )))
                .build();
        row.setSlotState(objectMapper.writeValueAsString(st));
        row.setSpecSnapshot(objectMapper.writeValueAsString(spec));

        when(skillInteractionMapper.selectById("ix-nested")).thenReturn(row);
        when(toolRegistry.execute(eq("nested_tool"), any())).thenReturn("done");

        AgentResult r = executor.resume("ix-nested",
                UiSubmitPayload.builder().action("submit").build(),
                "u1", List.of(), "ses");

        assertTrue(r.isSuccess());
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(toolRegistry).execute(eq("nested_tool"), cap.capture());
        Map<String, Object> sent = cap.getValue();
        assertTrue(sent.containsKey("body"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) sent.get("body");
        assertEquals("A队", inner.get("teamName"));
        assertEquals("d99", inner.get("deptId"));
    }

    private InteractiveFormSkill dummySkill() {
        InteractiveFormSpec spec = InteractiveFormSpec.builder()
                .targetTool("fixture_target_tool")
                .fields(List.of(FieldSpec.builder()
                        .key("k")
                        .label("L")
                        .type("text")
                        .required(false)
                        .source(FieldSourceSpec.builder().kind("NONE").build())
                        .build()))
                .build();
        return new InteractiveFormSkill("x", "d", null, List.of(), SkillMetadata.defaultFor(SideEffectLevel.WRITE),
                spec, executor);
    }
}
