package com.enterprise.ai.control.pageworkbench.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Generation rows exist only after an administrator reviews an action. */
@Mapper
public interface PageActionEffectGenerationMapper {
    @Select("SELECT revision FROM control_page_action_effect_generation WHERE action_id=#{actionId} FOR UPDATE")
    Long lock(@Param("actionId") Long actionId);

    @Insert("INSERT INTO control_page_action_effect_generation(action_id,page_id,revision) VALUES(#{actionId},#{pageId},0)")
    int create(@Param("actionId") Long actionId, @Param("pageId") Long pageId);

    @Update("UPDATE control_page_action_effect_generation SET revision=revision+1 WHERE action_id=#{actionId}")
    int invalidateAction(@Param("actionId") Long actionId);

    @Update("UPDATE control_page_action_effect_generation SET revision=revision+1 WHERE page_id=#{pageId}")
    int invalidatePage(@Param("pageId") Long pageId);
}
