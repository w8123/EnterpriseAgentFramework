package com.enterprise.ai.control.market;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_market_item")
public class ControlMarketItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String assetKind;
    private String assetId;
    private String assetKey;
    private Long projectId;
    private String projectCode;
    private String name;
    private String description;
    private String version;
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
