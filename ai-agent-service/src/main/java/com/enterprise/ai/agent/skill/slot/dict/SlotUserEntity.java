package com.enterprise.ai.agent.skill.slot.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("slot_dict_user")
public class SlotUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deptId;

    private String name;

    private String pinyin;

    /** 工号（可空，唯一时优先匹配）。 */
    private String employeeNo;

    /** 别名 / 英文名，逗号分隔。 */
    private String aliases;

    private Boolean enabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
