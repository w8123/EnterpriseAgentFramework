package com.enterprise.ai.agent.skill.slot.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("slot_dict_dept")
public class SlotDeptEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父部门 ID，根部门为 null。 */
    private Long parentId;

    private String name;

    /** 全拼，启动期/CRUD 时自动生成（不强制；空时按 name 字面量匹配）。 */
    private String pinyin;

    /** 别名 / 历史名，逗号分隔。 */
    private String aliases;

    /** 限定该部门字典只在某个项目下生效；为空表示全局。 */
    private Long projectScope;

    private Boolean enabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
