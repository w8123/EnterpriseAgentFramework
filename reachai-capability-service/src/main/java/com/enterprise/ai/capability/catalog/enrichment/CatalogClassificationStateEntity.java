package com.enterprise.ai.capability.catalog.enrichment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Records a deliberate empty selection as well as the revision of the owner's membership set. */
@Data
@TableName("capability_catalog_classification_state")
public class CatalogClassificationStateEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String subjectType;
    private Long subjectId;
    private Boolean manual;
    private Long revision;
}
