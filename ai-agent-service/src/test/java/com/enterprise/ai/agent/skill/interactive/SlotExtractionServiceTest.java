package com.enterprise.ai.agent.skill.interactive;

import com.enterprise.ai.agent.llm.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlotExtractionServiceTest {

    @Test
    void mergeFromUserText_matchesDictLabel() {
        LlmService llm = mock(LlmService.class);
        when(llm.chat(anyString(), anyString())).thenReturn("{}");
        SlotExtractionService svc = new SlotExtractionService(llm, new ObjectMapper());

        InteractiveFormSpec spec = InteractiveFormSpec.builder()
                .targetTool("t")
                .fields(List.of(FieldSpec.builder()
                        .key("shiftType")
                        .label("班次")
                        .type("select")
                        .required(true)
                        .source(FieldSourceSpec.builder().kind("DICT").dictCode("SHIFT_TYPE").build())
                        .build()))
                .build();
        Map<String, List<FieldOptionSpec>> opts = Map.of(
                "shiftType", List.of(FieldOptionSpec.builder().value("NIGHT").label("夜班").build())
        );
        Map<String, Object> slots = new LinkedHashMap<>();
        svc.mergeFromUserText(slots, "我要申请夜班班组", spec, opts);
        assertEquals("NIGHT", slots.get("shiftType"));
    }

    @Test
    void validateField_numberInvalid() {
        SlotExtractionService svc = new SlotExtractionService(mock(LlmService.class), new ObjectMapper());
        FieldSpec f = FieldSpec.builder()
                .key("n")
                .label("N")
                .type("number")
                .required(true)
                .source(FieldSourceSpec.builder().kind("NONE").build())
                .build();
        assertNull(svc.validateField(f, "12"));
        assertEquals("需要数字", svc.validateField(f, "abc"));
    }
}
