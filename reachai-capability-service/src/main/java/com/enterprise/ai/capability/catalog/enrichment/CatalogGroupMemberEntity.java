package com.enterprise.ai.capability.catalog.enrichment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("capability_catalog_group_member")
public class CatalogGroupMemberEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long groupId;
    private String subjectType;
    private Long subjectId;
}
