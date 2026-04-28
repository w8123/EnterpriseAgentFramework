package com.enterprise.ai.agent.mcp;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mcp_client")
public class McpClientEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** SHA-256(apiKey) hex；明文仅在 create 接口返回一次。 */
    private String apiKeyHash;

    /** apiKey 前 8 字符，便于运营辨识；不参与鉴权。 */
    private String apiKeyPrefix;

    /** JSON 数组字符串：roles，调用时作为 ToolACL 决策依据。 */
    private String rolesJson;

    /** JSON 数组字符串：限定 Client 可用的 tool/skill name；空数组 = 不限。 */
    private String toolWhitelistJson;

    private Boolean enabled;

    private LocalDateTime expiresAt;

    private LocalDateTime lastUsedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
