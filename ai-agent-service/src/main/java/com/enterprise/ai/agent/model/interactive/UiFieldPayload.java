package com.enterprise.ai.agent.model.interactive;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiFieldPayload {
    private String key;
    private String label;
    private String type;
    private boolean required;
    @Builder.Default
    private List<UiFieldOptionPayload> options = new ArrayList<>();
}
