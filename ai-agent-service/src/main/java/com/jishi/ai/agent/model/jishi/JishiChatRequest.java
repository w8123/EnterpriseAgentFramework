package com.jishi.ai.agent.model.jishi;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 极视角对话API请求体
 *
 * @see <a href="POST /console/api/app_ability/public/{appId}/chat">发送对话消息</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JishiChatRequest {

    /** 用户输入/提问内容 */
    private String query;

    /** 用户标识，需保证在应用内唯一 */
    private String user;

    /** 会话ID，基于已有对话继续时必传 */
    @JsonProperty("session_id")
    private String sessionId;

    /** blocking（默认直接输出）或 streaming（流式输出） */
    @JsonProperty("response_mode")
    @Builder.Default
    private String responseMode = "blocking";

    /** App定义的各变量值，默认空对象 */
    @Builder.Default
    private Map<String, Object> inputs = Map.of();

    /** 上传的文件列表 */
    private List<FileItem> files;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileItem {

        /** local_file 或 remote_url */
        @JsonProperty("transfer_method")
        private String transferMethod;

        /** 文件地址（上传后的绝对地址或远程URL） */
        private String url;
    }
}
