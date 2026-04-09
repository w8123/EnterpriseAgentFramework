package com.jishi.ai.agent.model.jishi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 极视角应用基本信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JishiAppInfo {

    private String id;

    private String name;

    private String description;

    private String status;

    private String apptype;

    @JsonProperty("enable_api")
    private Boolean enableApi;

    private List<String> tags;

    private List<InputParam> inputs;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InputParam {
        private String name;
        private String type;
        private String describe;
        private List<String> options;
    }
}
