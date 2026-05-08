package com.enterprise.ai.agent.market;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("market_item")
public class MarketItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** AGENT / SKILL。 */
    private String assetKind;

    private String assetId;

    private String assetKey;

    private Long projectId;

    private String projectCode;

    private String name;

    private String description;

    private String version;

    /** DRAFT / PENDING_APPROVAL / LISTED / REJECTED / UNLISTED。 */
    private String status;

    private String visibility;

    private String dependencyManifestJson;

    private String snapshotJson;

    private String submittedBy;

    private String approvedBy;

    private LocalDateTime approvedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
