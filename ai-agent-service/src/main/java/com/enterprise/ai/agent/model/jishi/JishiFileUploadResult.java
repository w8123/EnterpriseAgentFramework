package com.enterprise.ai.agent.model.jishi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 极视角文件上传响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JishiFileUploadResult {

    private String id;

    private String name;

    private Long size;

    private String extension;

    @JsonProperty("file_path")
    private String filePath;
}
