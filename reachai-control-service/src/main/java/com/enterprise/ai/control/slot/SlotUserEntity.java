package com.enterprise.ai.control.slot;

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

    private String employeeNo;

    private String aliases;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
